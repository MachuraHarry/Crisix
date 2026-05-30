# SendeVoice-Plan: Sprachnachrichten auf allen Transportwegen

Stand: 30.05.2026

## Status Quo

| Aspekt | Status |
|--------|--------|
| **`AdaptiveInputBar` Mikrofon-Button** | ✅ Vorhanden, aber `onVoiceClick = {}` ist No-Op |
| **`supportsAudio` im TransportCapabilities** | ❌ Überall `false` |
| **`Message` Data Class** | ❌ Kein `audioUri`-Feld |
| **Room-DB `MessageEntity`** | ❌ Kein `audioUri`-Feld |
| **BLE-Chunking** | ✅ bereits implementiert (Phase 3) |
| **Bild-Persistenz** | ✅ `filesDir/images/` + FileProvider |
| **Audio-Aufnahme** | ❌ Fehlt komplett |
| **Audio-Wiedergabe** | ❌ Fehlt komplett |
| **RECORD_AUDIO Permission** | ❌ Fehlt im Manifest |

---

## Phase 1: Permission + Manifest

**Ziel**: App darf Mikrofon nutzen.

**`AndroidManifest.xml`**:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-feature android:name="android.hardware.microphone" android:required="false" />
```

**`CrisixApp.kt`**: Runtime-Permission für `RECORD_AUDIO` (analog zu BLE-Permissions).

---

## Phase 2: AudioRecorder (Utility)

**Ziel**: Einheitliche Recording-API (Hold-to-Record).

**`util/AudioRecorder.kt`** (neu):
```kotlin
object AudioRecorder {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    suspend fun startRecording(context: Context, outputDir: File): File {
        val file = File(outputDir, "voice_${System.currentTimeMillis()}.aac")
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioBitRate(32000) // ~4KB/Sekunde → 1 Min = ~240KB
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        outputFile = file
        return file
    }

    suspend fun stopRecording(): ByteArray {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        return outputFile?.readBytes() ?: byteArrayOf()
    }

    fun cancelRecording() {
        mediaRecorder?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaRecorder = null
        outputFile?.delete()
        outputFile = null
    }
}
```

**Format-Entscheidung**: AAC statt AMR oder Opus.
- AAC ist auf Android nativ (kein Opus-Codec nötig)
- ~4 KB/s → 1 Minute = ~240 KB (gut transportierbar)
- Höhere Qualität als AMR

---

## Phase 3: AudioPlayer (Utility)

**Ziel**: Abspielen von empfangenen Sprachnachrichten.

**`util/AudioPlayer.kt`** (neu):
```kotlin
object AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null

    fun play(context: Context, uri: Uri, onCompletion: () -> Unit) {
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(context, uri)
            prepare()
            start()
            setOnCompletionListener { onCompletion() }
        }
    }

    fun stop() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
    }

    val isPlaying: Boolean get() = mediaPlayer?.isPlaying == true
}
```

---

## Phase 4: Hold-to-Record UI

**Ziel**: WhatsApp-ähnliches Mikrofon-Erlebnis.

### AdaptiveInputBar ändern

- `onVoiceClick` (einmaliges Tippen) → wird zu `onVoiceStart` / `onVoiceEnd` (Drücken/Loslassen)
- Langes Drücken auf Mikrofon → startet Aufnahme
- Loslassen → sendet oder verwirft
- Wischen nach links → verwirft (optional)

```kotlin
// Statt:
IconButton(onClick = onVoiceClick)

// Neu: GestureDetector mit press/release
Box(
    modifier = Modifier
        .size(40.dp)
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { onVoiceStart() },
                onPress = { /* haptic feedback */ }
            )
        }
) {
    // Mikrofon-Icon
}
```

### Recording-Overlay (optional)

Ein `Popup` oder animiertes Overlay zeigt an:
- Rote Aufnahme-Anzeige
- Mikrofon-Icon + "0:12" Sekunden-Zähler
- Wischen zum Abbrechen

---

## Phase 5: VoiceMessage-Protokoll

**Ziel**: Sprachnachrichten wie Bilder via JSON + Base64 versenden.

### Senden (`CrisixApp.kt` — `onVoiceMessage`)

```kotlin
// Analog zu onSendImage:
onVoiceMessage = { audioBytes, durationMs ->
    val msgId = "voice${System.currentTimeMillis()}"
    val b64 = Base64.getEncoder().encodeToString(audioBytes)
    val jsonMessage = JSONObject().apply {
        put("type", "voice")
        put("data", b64)
        put("mime", "audio/aac")
        put("durationMs", durationMs)
        put("messageId", msgId)
        put("sender", userProfile.name)
    }
    transportManager.sendMessage(normChatId, jsonMessage.toString().toByteArray(), uiMessageId = msgId)
}
```

### Empfangen (`CrisixApp.kt` — Incoming-Listener)

```kotlin
// Analog zu image-Handling:
when (json.optString("type")) {
    "voice" -> {
        val audioBytes = Base64.getDecoder().decode(json.getString("data"))
        val durationMs = json.optLong("durationMs", 0L)

        val audioDir = File(context.filesDir, "audio")
        audioDir.mkdirs()
        val localFile = File(audioDir, "$msgId.aac")
        localFile.writeBytes(audioBytes)
        val localUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)

        val newMessage = Message(
            id = msgId,
            text = "",
            isFromMe = false,
            timestamp = timeStamp,
            timestampMillis = now,
            status = MessageStatus.DELIVERED,
            audioUri = localUri.toString(),
            audioDurationMs = durationMs,
        )
        // In DB + allMessages speichern
    }
}
```

### FileProvider-Erweiterung

**`res/xml/file_paths.xml`**:
```xml
<files-path name="audio" path="audio/" />
```

---

## Phase 6: Message + MessageEntity um audioUri erweitern

**`ChatDetailScreen.kt` — `Message` Data Class**:
```kotlin
data class Message(
    // ... existing fields ...
    val audioUri: String? = null,
    val audioDurationMs: Long = 0L,
)
```

**`MessageEntity.kt`**:
```kotlin
val audioUri: String? = null,
val audioDurationMs: Long = 0,
```

**Room-DB**: Version 2→3 (bzw. fällt `fallbackToDestructiveMigration` schon).

---

## Phase 7: AudioBubble (UI-Komponente)

**Ziel**: Chat-Blase mit Play-Button + Waveform/Dauer + Timeline.

```kotlin
@Composable
fun AudioBubble(
    audioUri: String,
    durationMs: Long,
    isFromMe: Boolean,
) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
    ) {
        // Play/Pause-Button
        IconButton(onClick = {
            if (isPlaying) AudioPlayer.stop()
            else AudioPlayer.play(context, Uri.parse(audioUri)) { isPlaying = false }
            isPlaying = !isPlaying
        }) {
            Icon(
                painter = if (isPlaying) painterResource(R.drawable.ic_pause)
                else painterResource(R.drawable.ic_play),
                contentDescription = null,
            )
        }

        // Waveform / Progress-Bar (vereinfacht: LinearProgressIndicator)
        Column(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    return "%d:%02d".format(sec / 60, sec % 60)
}
```

**Benötigte Icons**: `ic_play.xml`, `ic_pause.xml` (neu).

---

## Phase 8: Transport-Capabilities aktivieren

Für jeden Transport `supportsAudio = true` setzen (außer DNS, SMS, LoRa):

| Transport | Audio | Begründung |
|-----------|-------|------------|
| WifiTransport | ✅ ~240 KB/Minute, kein Problem |
| InternetTransport | ✅ ~240 KB/Minute |
| RelayTransport | ✅ Base64 + WebSocket |
| BleTransport | ✅ Chunking (Phase 3) → ~240 KB = ~600 Chunks, langsam aber machbar |
| DnsTunnelTransport | ❌ 200-Zeichen-Limit |
| DummyTransport | ❌ |

---

## Phasen-Zusammenfassung

| Phase | Aufwand | Beschreibung |
|-------|---------|-------------|
| **1** | ~0,5h | RECORD_AUDIO Permission + Manifest + Runtime-Request |
| **2** | ~1h | `AudioRecorder.kt` — Aufnahme als AAC, Start/Stop/Cancel |
| **3** | ~0,5h | `AudioPlayer.kt` — Wiedergabe via MediaPlayer |
| **4** | ~1,5h | Hold-to-Record UI in AdaptiveInputBar + Recording-Overlay |
| **5** | ~1h | JSON-Protokoll `type: voice` + Sende-/Empfangslogik |
| **6** | ~0,5h | `Message.audioUri` + `MessageEntity.audioUri` + Room-Migration |
| **7** | ~1,5h | `AudioBubble`-Komponente (Play/Pause, Progress, Duration) |
| **8** | ~0,5h | `supportsAudio = true` für alle Transporte |

**Gesamt**: ~7h

---

## Abhängigkeiten

```
Phase 1 (Permission)
    ↓
Phase 2 (AudioRecorder) ──→ Phase 4 (Hold-to-Record UI)
                                  ↓
Phase 5 (Protokoll: Senden/Empfangen)
    ↓
Phase 6 (Message + MessageEntity + Room)
    ↓
Phase 7 (AudioBubble UI)
    ↓
Phase 3 (AudioPlayer) ───┘
    ↓
Phase 8 (Capabilities)
```

---

## Nicht abgedeckt (vorerst)
- **Push-to-Talk**: Echtzeit-Audio-Streaming (separate Architektur nötig)
- **Voice-Activity-Detection**: Automatische Stille-Erkennung
- **Waveform-Visualisierung**: Echte Audiowellenform statt einfachem Progress-Bar
- **Audio-Nachrichten löschen**: Vor dem Senden anhören + verwerfen
- **Opus-Codec**: Bessere Kompression aber externer Codec nötig
