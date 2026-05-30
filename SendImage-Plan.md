# SendImage-Plan: Bild-Support auf allen Transportwegen

Stand: 30.05.2026 — Phase 1, 3, 5 implementiert ✅

## Status Quo

| Aspekt | Status |
|--------|--------|
| UI Photo Picker | ✅ funktioniert |
| UI Anzeige (AsyncImage) | ✅ funktioniert |
| Lokale Persistenz in DB | ✅ `imageUri` wird gespeichert |
| **Tatsächliches Senden über Transport** | **❌ Fehlt komplett** |
| Empfangen & Decodieren auf Gegenseite | **❌ Fehlt komplett** |
| Chunking für große Dateien | **❌ Fehlt komplett** |
| BLE (≤400 Bytes) | **❌ Kein Chunking, `supportsImages=false`** |

**Kernproblem**: `onSendImage` in `CrisixApp.kt:515` erzeugt nur eine lokale `Message` mit `imageUri`, ruft aber **nie** `transportManager.sendMessage()` auf. Bilddaten werden nie übertragen.

---

## Phase 1: ImageMessage-Protokoll (JSON-Erweiterung)

**Ziel**: Bilder als Base64 im bestehenden JSON-Format mitschicken.

### Senden (`CrisixApp.kt` — `onSendImage`)

```kotlin
// Bisher: Nur lokal persistieren, nie senden
// Neu:
onSendImage = { uri ->
    val now = System.currentTimeMillis()
    val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
    val msgId = "img${now}"

    scope.launch(Dispatchers.IO) {
        // 1. URI → Bytes (mit Kompression)
        val inputStream = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
        val imageBytes = output.toByteArray()

        // 2. Base64 kodieren
        val b64 = Base64.getEncoder().encodeToString(imageBytes)

        // 3. JSON-Payload bauen
        val jsonMessage = JSONObject().apply {
            put("type", "image")
            put("data", b64)
            put("mime", "image/jpeg")
            put("timestamp", timeStamp)
            put("messageId", msgId)
            put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
        }

        // 4. Über Transport senden (wie Text-Nachrichten auch)
        transportManager.sendMessage(normChatId, jsonMessage.toString().toByteArray(), uiMessageId = msgId)
    }
}
```

### Empfangen (`CrisixApp.kt` — Incoming-Listener)

```kotlin
// Bisher: Nur "type" == "message" parsen
// Neu: Auch "type" == "image" parsen
val json = JSONObject(messageText)
when (json.optString("type")) {
    "message" -> {
        // existing text handling
    }
    "image" -> {
        // 1. Base64 decoden
        val imageBytes = Base64.getDecoder().decode(json.getString("data"))
        val mime = json.optString("mime", "image/jpeg")

        // 2. In app-internes Verzeichnis schreiben
        val imagesDir = File(context.filesDir, "images")
        imagesDir.mkdirs()
        val localFile = File(imagesDir, "${msgId}.jpg")
        localFile.writeBytes(imageBytes)

        // 3. FileProvider-URI erzeugen
        val localUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)

        // 4. Message mit imageUri = localUri erstellen
        val newMessage = Message(
            id = msgId,
            text = "",
            isFromMe = false,
            timestamp = timeStamp,
            timestampMillis = now,
            status = MessageStatus.DELIVERED,
            imageUri = localUri.toString(),
        )
        // In DB + allMessages speichern
    }
}
```

### Betroffene Dateien
- `CrisixApp.kt` — Sende- + Empfangslogik
- `ChatDetailScreen.kt` — unverändert (nutzt bereits `message.imageUri`)

---

## Phase 2: Image-Kompression & Größen-Limit

**Ziel**: Bilder vor dem Senden auf max 500 KB / 1024px komprimieren.

### ImageCompressor.kt (neu)

```kotlin
package com.messenger.crisix.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object ImageCompressor {
    suspend fun compress(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1024,
        quality: Int = 80
    ): ByteArray = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()

        val (width, height) = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val ratio = minOf(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
            Pair((bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt())
        } else {
            Pair(bitmap.width, bitmap.height)
        }

        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()

        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
        scaled.recycle()

        output.toByteArray()
    }
}
```

### Grenzen pro Transport

| Transport | Max Image Size | Strategie |
|-----------|---------------|-----------|
| WifiTransport | 10 MB | Direkt, kein Chunking nötig |
| InternetTransport | 10 MB | Direkt |
| RelayTransport | 5 MB | Base64-Overhead (~33%), aber WebSocket verkraftet das |
| BleTransport | **Chunking nötig** (≤400 Bytes/Nachricht) | → Phase 3 |
| DnsTunnelTransport | ❌ Nicht unterstützt | |
| DummyTransport | ❌ Nicht unterstützt | |

---

## Phase 3: BLE-Chunking für Images

**Ziel**: Bilder in 350-Byte-Blöcke zerlegt über BLE übertragen.

### Chunk-Protokoll

```
[Flags: 1 Byte] [MessageId: 4 Bytes] [ChunkIndex: 1 Byte] [Daten: max 344 Bytes]
```

- **Flags**: 0x01 = erster Chunk, 0x02 = letzter Chunk, 0x00 = mittlerer Chunk
- **MessageId**: fortlaufende ID für Zuordnung
- **ChunkIndex**: Position im Gesamtbild
- **Daten**: 344 Bytes pro Chunk (von 400 verfügbar, Rest für Header + Base64-Overhead)

### Änderungen in `BleTransport.kt`

**Senden** (`send()`):
```kotlin
if (data.size > MAX_CHUNK_SIZE) {
    val chunks = data.chunked(MAX_CHUNK_SIZE)
    chunks.forEachIndexed { index, chunk ->
        val header = ByteArray(6).apply {
            this[0] = when {
                index == 0 -> 0x01
                index == chunks.lastIndex -> 0x02
                else -> 0x00
            }
            // Bytes 1-4: messageId (Int)
            val idBytes = java.nio.ByteBuffer.allocate(4).putInt(messageId).array()
            System.arraycopy(idBytes, 0, this, 1, 4)
            this[5] = index.toByte()
        }
        val chunkPayload = header + chunk
        super.send(peerId, chunkPayload) // rekursiv
    }
}
```

**Empfangen** (`processIncomingMessage()`):
```kotlin
private val chunkBuffer = mutableMapOf<Int, ByteArray>()

// In processIncomingMessage:
val flags = data[0]
val messageId = java.nio.ByteBuffer.wrap(data, 1, 4).getInt()
val chunkIndex = data[5].toInt() and 0xFF
val chunkData = data.copyOfRange(6, data.size)

chunkBuffer[messageId] = (chunkBuffer[messageId] ?: byteArrayOf()) + chunkData

if (flags and 0x02 != 0) { // letzter Chunk
    val fullData = chunkBuffer.remove(messageId)!!
    messageListeners.forEach { it(senderPeerId, fullData) }
}
```

### Betroffene Dateien
- `BleTransport.kt` — Chunking-Logik in `send()` + `processIncomingMessage()`
- `capabilities` — `supportsImages = true` setzen (nach Chunking-Implementierung)

---

## Phase 4: Room-DB + File-Persistenz

**Ziel**: Empfangene Bilder überleben App-Neustart.

### Speicherort
```
context.filesDir/images/<messageId>.jpg
```

### `MessageEntity.kt` — kein neues Feld nötig
`imageUri` speichert bereits den URI — nach Empfang wird dort der `FileProvider`-URI abgelegt (statt des ursprünglichen `content://`-URIs vom Sender).

### Laden beim App-Start
In `CrisixApp.kt` `LaunchedEffect`:
```kotlin
// existingMessages aus Room laden — imageUri zeigt dann auf lokale Datei
// kein zusätzlicher Code nötig, da FileProvider-URI dauerhaft gültig ist
```

---

## Phase 5: FileProvider registrieren

**Ziel**: AsyncImage kann auf lokale Dateien in `filesDir/images/` zugreifen.

### `AndroidManifest.xml`
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

### `res/xml/file_paths.xml` (neu)
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path name="images" path="images/" />
</paths>
```

---

## Phasen-Zusammenfassung

| Phase | Aufwand | Beschreibung |
|-------|---------|-------------|
| **1** | ~1h | JSON + Base64 + Send-Empfangs-Logik → Bilder fliegen über Wifi/Internet/Relay |
| **2** | ~0,5h | ImageCompressor → keine megabyte-großen Payloads |
| **3** | ~2h | BLE-Chunking → auch BLE kann Bilder empfangen |
| **4** | ~1h | Room + File-Persistenz → Bild-Cache überlebt App-Neustart |
| **5** | ~0,5h | FileProvider → Bilder sicher via AsyncImage anzeigbar |

**Gesamt**: ~5h

---

## Nicht abgedeckt (vorerst)
- **DnsTunnelTransport**: 200-Zeichen-Limit → Bilder unmöglich
- **SMS / LoRa**: Keine Binärunterstützung
- **Fortschrittsanzeige**: Kein Progress-Bar beim Senden/Empfangen großer Bilder
- **Video/Audio**: separate Phasen später
