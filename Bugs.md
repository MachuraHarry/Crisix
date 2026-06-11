# Crisix AI — Tiefenanalyse: Bugs & Crash-Risiken

> Erstellt am 09.06.2026 — Vollständige Codebase-Analyse (~100+ Kotlin-Dateien)

---

## KRITISCH (Absturz, Datenverlust, Sicherheitsbruch)

### 1. ImageCompressor — Zugriff auf recycled Bitmap → Crash

**Datei:** `util/ImageCompressor.kt:34,42`

In der `compress()`-Schleife wird `bitmap.recycle()` aufgerufen und danach in der nächsten Iteration erneut `bitmap.width` gelesen → `IllegalStateException`. **Jeder Bildversand, der ein Verkleinern erfordert, crashed beim zweiten Schleifendurchlauf.**

```kotlin
// util/ImageCompressor.kt (Kontext):
while (...) {
    // ...
    bitmap.width  // Zeile 34 — Zugriff auf ggf. bereits recycled bitmap
    // ...
    bitmap.recycle()  // Zeile 42 — recycle() wird in der Schleife aufgerufen
}
```

### 2. Defragmenter — Race Condition auf `receivedCount`

**Datei:** `transport/Defragmenter.kt:44`

`buffer.receivedCount++` ist nicht threadsicher. Zwei gleichzeitige Chunks für dieselbe Nachricht können einen Inkrement verlieren → die Reassemblierung wird nie abgeschlossen, Nachrichten bleiben als Fragment-Halde im Speicher hängen.

```kotlin
// Defragmenter.kt:44
buffer.receivedCount++ // Nicht atomar — Race Condition
```

### 3. DoubleRatchet — Keine Threadsicherheit bei `ratchetEncrypt`

**Datei:** `crypto/DoubleRatchet.kt:73–93`

Kein `synchronized`. Zwei Threads, die an denselben Peer verschlüsseln, können (a) denselben `sendingMessageIndex` lesen, (b) denselben `messageKey` ableiten, (c) denselben Nonce generieren → **identische (Key, Nonce)-Paare in AES-GCM**. Das bricht die Verschlüsselung komplett.

```kotlin
// DoubleRatchet.kt:73–93
fun ratchetEncrypt(...) {
    // Kein synchronized — zwei Threads lesen gleichen Index
    sessionState.sendingMessageIndex++ // Nicht atomar
    // Beide Threads leiten gleichen Key ab → Nonce-Reuse in AES-GCM
}
```

### 4. OutOfOrderMessageHandler — Falscher Key für Entschlüsselung

**Datei:** `crypto/OutOfOrderMessageHandler.kt:130–131`

Verwendet den **rohen `chainKey`** als AES-GCM-Key, aber der Sender hat mit `messageKey = HKDF(chainKey, ...)` verschlüsselt. Die Key-Ableitung fehlt → **Out-of-Order-Nachrichten können NIE entschlüsselt werden.** Die gesamte Out-of-Order-Logik ist kaputt.

```kotlin
// OutOfOrderMessageHandler.kt:130–131
CryptoHelper.aesGcmDecrypt(ciphertext, cachedKey.chainKey, nonce)
//                                      ^^^^^^^^^^^^^^^^
//      Hier müsste messageKey = HKDF(chainKey, ...) stehen
```

### 5. E2eeManager — SPKI-Format statt Raw X25519 Key

**Datei:** `crypto/E2eeManager.kt:604–613`

`privParams.generatePublicKey().encoded` liefert SPKI-Format (~44 Bytes), aber als raw 32-Byte X25519-Public-Key verwendet → der DH-Vergleich in `ratchetDecrypt` schlägt fehl → E2EE-Sessions korrumpieren nach Key-Rotation.

```kotlin
// E2eeManager.kt:604–613
val spkiKey = privParams.generatePublicKey().encoded
//      ^^^^ SPKI-encoded (~44 Bytes), nicht raw 32-Byte X25519
// Wird als X25519 public key verwendet → DH-Vergleich fehlschlagend
```

### 6. MessageProcessor — Nachrichten-ID-Kollision

**Datei:** `message/MessageProcessor.kt:352,389,425,504,581`

Alle unverschlüsselten Nachrichten nutzen `"incoming-$now"` als ID. Zwei Nachrichten in derselben Millisekunde (z.B. zwei Transporte gleichzeitig) bekommen dieselbe ID → eine überschreibt die andere in der DB → **Datenverlust**.

```kotlin
// MessageProcessor.kt:352
val messageId = "incoming-${System.currentTimeMillis()}"
//           ^^ Zwei Nachrichten in derselben ms → ID-Kollision → DB-Überschreibung
```

### 7. X3DHSession — Secret Keys im Logcat

**Datei:** `crypto/X3DHSession.kt:240–248,301–305,390–398,448–451`

DH-Shared-Secrets, Root-Keys und Chain-Keys werden per `Log.d` ausgegeben. Jede App mit `READ_LOGS` oder ADB-Zugriff kann sämtliche E2EE-Keys exfiltrieren.

```kotlin
// X3DHSession.kt:240–248
Log.d("X3DHSession", "DH1 shared secret: ${sharedSecret1.contentToString()}")
Log.d("X3DHSession", "DH2 shared secret: ${sharedSecret2.contentToString()}")
Log.d("X3DHSession", "rootKey: ${rootKey.contentToString()}")
// ⚠️ Secret Keys im Logcat!
```

---

## HOCH (Hohes Crash-Risiko, ANR, Speicherfehler)

### 8. AudioPlayer — Unbehandelte Exceptions

**Datei:** `util/AudioPlayer.kt:23–24,35–36`

`setDataSource()` und `prepare()` werfen `IOException` ohne try-catch → führt zum Coroutine-Crash und leaked den MediaPlayer.

```kotlin
// AudioPlayer.kt:23–24
mediaPlayer = MediaPlayer().apply {
    setDataSource(filePath)  // wirft IOException — ungehandelt
    prepare()                // wirft IOException — ungehandelt
}
```

### 9. ImageCompressor — Null-Bitmap nach Decode

**Datei:** `util/ImageCompressor.kt:28`

`BitmapFactory.decodeStream()` kann null zurückgeben, aber `bitmap.width` bei Zeile 34 wird ohne Null-Check aufgerufen → **NPE-Crash**.

```kotlin
// ImageCompressor.kt:28
val bitmap = BitmapFactory.decodeStream(inputStream)
//                 ^^ kann null sein!
// ImageCompressor.kt:34
val width = bitmap.width  // NPE wenn decodeStream null zurückgab
```

### 10. NotificationHelper — NPE bei getSystemService

**Datei:** `util/NotificationHelper.kt:35`

`getSystemService(NOTIFICATION_SERVICE)` als `NotificationManager` gecastet (force-cast) — kann null zurückgeben → NPE auf manchen Geräten.

### 11. NotificationActionReceiver — `runBlocking` auf Main-Thread

**Datei:** `util/NotificationActionReceiver.kt:25`

`runBlocking` im `BroadcastReceiver.onReceive` (Main-Thread) mit DB-I/O → **ANR** bei langsamer Datenbank.

### 12. Libp2pManager — Keine GrößenvorValidierung → OutOfMemoryError

**Datei:** `transport/internet/Libp2pManager.kt:377–387`

`ByteArray(length)` mit einem vom Netzwerk kommenden Längenfeld. Ein bösartiger Peer sendet `Int.MAX_VALUE` → `OutOfMemoryError` → **App-Absturz**.

```kotlin
// Libp2pManager.kt:377–387
val length = inputStream.readInt()  // kommt vom Netzwerk
if (length > 10 * 1024 * 1024) { /* validieren */ }
val data = ByteArray(length)  // ⚠️ length kann Int.MAX_VALUE sein → OOM
```

### 13. MainlineDhtNode — Unbegrenzte Child-Coroutines bei DHT-Flood

**Datei:** `transport/internet/MainlineDhtNode.kt:442`

Jedes empfangene UDP-Paket startet einen neuen Coroutine. Bei einer DHT-Flood → unbegrenzt Child-Coroutines → **OOM**.

### 14. BleTransport — BLE-Writes in enger Schleife ohne Callback-Wait

**Datei:** `transport/BleTransport.kt:702–751`

`gatt.writeCharacteristic()` in for-Schleife ohne auf `onCharacteristicWrite` zu warten. Der BLE-Stack hat einen begrenzten Puffer → Chunks werden verworfen → **Datenverlust bei BLE-Übertragungen**.

### 15. RelayTransport — Neuer OkHttpClient pro Verbindung

**Datei:** `transport/RelayTransport.kt:200`

Jeder Connect erzeugt einen neuen `OkHttpClient` mit eigenem Thread-Pool. `isAvailable()` (alle 5s) erzeugt ebenfalls einen neuen Client. Über Stunden sammeln sich hunderte Thread-Pools an → **Thread-Leak, OOM**.

```kotlin
// RelayTransport.kt:200 — NEUER OkHttpClient PRO Connect
val client = OkHttpClient.Builder()
    .pingInterval(30, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)
    .build()  // ⚠️ Jeder Aufruf = neuer Thread-Pool
```

### 16. NatTraversal — Ungescancelter CoroutineScope

**Datei:** `transport/internet/NatTraversal.kt:148`

`CoroutineScope(Dispatchers.IO)` wird erstellt aber nie gecancelt. Der UDP-Listener läuft für immer → Ressourcen-Leck.

### 17. AiModelManager — `runBlocking` in `cancel()` → ANR

**Datei:** `ai/AiModelManager.kt:579–581`

Wenn Nutzer die KI-Antwort abbricht (vom Main-Thread), ruft dies `stopPrediction()` → `runBlocking` → blockiert UI-Thread für bis zu 5 Sekunden → **ANR**.

```kotlin
// AiModelManager.kt:579–581
fun stopPrediction() {
    runBlocking {  // ⚠️ Blockiert den aufrufenden Thread (potenziell Main-Thread)
        llama.stopCompletion(id)
    }
}
```

### 18. AiModelManager — Model-File wird während laufender Inference überschrieben

**Datei:** `ai/AiModelManager.kt:217–243`

`selectLocalModel()` überschreibt `modelFile` bedingungslos. Wenn eine Inference läuft, wird das Model unter dem aktiven Engine-Handle ausgetauscht → **nativer Crash**.

---

## MITTEL (Funktionsfehler, Subtileres Fehlverhalten)

### 19. AppDatabase — Destructive Migration Fallback

**Datei:** `data/AppDatabase.kt:79`

`.fallbackToDestructiveMigration()` — wenn eine DB-Migration nicht abgedeckt ist, werden **alle Tabellen gelöscht und neu erstellt**. Alle Nachrichten, Chats, Kontakte → unwiederbringlich weg.

### 20. SmsReceiver — Multipart-Buffer-Key-Kollision

**Datei:** `transport/SmsReceiver.kt:77`

10-Sekunden-Zeitfenster als Buffer-Key. Zwei Multipart-SMS vom selben Absender im selben Fenster vermischen ihre Parts → korrumpierte Nachrichten.

### 21. LocaleHelper — Sprachwechsel funktioniert nicht

**Datei:** `util/LocaleHelper.kt:50–53`

`updateLocale()` setzt nur `Locale.setDefault()`, erstellt aber keine neue `Configuration`. Die richtige Methode `wrapContext()` existiert (Zeile 59–66), wird aber **nie aufgerufen**. Sprachwechsel hat keine Wirkung.

### 22. AiChatViewModel — `downloadModel()` lädt Modell nie automatisch

**Datei:** `ui/viewmodel/AiChatViewModel.kt:96–103`

Prüft sofort nach `downloadModel()` (was asynchron ist) auf `isComplete` — wird nie true sein. `controller.load()` wird nie aufgerufen → Modell muss manuell geladen werden.

### 23. CrisixApp — `phoneNumberResolver` Closure-Capture-Bug

**Datei:** `ui/navigation/CrisixApp.kt:376–382`

`SmsTransport.phoneNumberResolver` captured `savedContacts` zum Zeitpunkt der Initialisierung (leere Liste), nicht live. Kontakte werden nie im SMS-Resolver aktualisiert.

### 24. CrisixApp — Endloser Busy-Wait-Loop

**Datei:** `ui/navigation/CrisixApp.kt:511–523`

```kotlin
while (Libp2pManager.localPeerId.isBlank()) { delay(500) }
```

Wenn `localPeerId` nie gesetzt wird, läuft dieser Loop **für immer** → Coroutine-Leak.

### 25. MessageRepository — `cleanExpiredMessages` löscht alle Nachrichten

**Datei:** `data/MessageRepository.kt:179–183`

Wenn *irgendeine* Nachricht im Chat abläuft (`deleted > 0`), wird `clearChatMessages()` aufgerufen, das **alle** Nachrichten löscht — auch die noch nicht abgelaufenen.

### 26. TransportManager — Concurrent Modification von `pendingAcks`

**Datei:** `transport/TransportManager.kt:794,908`

`sendMessageLocked` und `checkUnackedMessages` modifizieren `pendingAcks` gleichzeitig → Delivery-Updates gehen verloren.

### 27. WifiAwareTransport — NetworkCallback nie unregistriert

**Datei:** `transport/WifiAwareTransport.kt:315–355`

`connectivityManager.requestNetwork(request, cb)` registriert einen Callback, der nie in `unregisterNetworkCallback()` gespeichert oder entfernt wird → Ressourcen-Leck.

### 28. PeerDiscovery — Scope nicht gecancelt in `stop()`

**Datei:** `transport/internet/PeerDiscovery.kt:60–121`

`stop()` setzt `isRunning = false`, cancelt aber nicht den CoroutineScope. Der 5-Minuten-Delay-Loop läuft weiter → Hintergrundarbeit läuft ewig.

### 29. AiHardwareProfile — Dead Code für Vulkan/GPU-Erkennung

**Datei:** `ai/AiHardwareProfile.kt:150–168`

Prüft `Build.DEVICE`/`Build.BOARD` gegen SoC-Namen wie `"snapdragon 865"`, aber diese Felder enthalten Geräte-Codenamen (z.B. `"oriole"`), nicht SoC-Bezeichner. Die gesamte Vulkan/GPU-Erkennung ist wirkungslos.

### 30. WifiTransport — Unendlicher Read-Loop ohne Timeout

**Datei:** `transport/WifiTransport.kt:99–116`

Nachdem die Länge gelesen wurde, wartet `readMessage` ohne Socket-Timeout auf die eigentlichen Daten. Ein langsamer/bösartiger Peer kann den Leser für immer blockieren.

### 31. DnsTunnelTransport — Neuer DatagramSocket pro Query

**Datei:** `transport/DnsTunnelTransport.kt:355`

Jeder DNS-Query erstellt einen neuen `DatagramSocket()`. Bei 5-Sekunden-Polling → hunderte Sockets pro Stunde → kann auf Android die ephemeral ports erschöpfen.

### 32. CrisixForegroundService — `startForeground` Exception in Boot-Worker-Schleife

**Datei:** `service/CrisixForegroundService.kt:75`

`START_STICKY` + `startForeground()` nach System-Restart → wenn App im Hintergrund, wirft `ForegroundServiceStartNotAllowedException` → `stopSelf()` → System startet neu (STICKY) → Endlosschleife aus Restart/Stop.

### 33. SessionTransportMapper — `pickByScore` Dead Code

**Datei:** `transport/SessionTransportMapper.kt:113–118`

Methode wird nie aufgerufen.

### 34. KeyRotationManager — cleanupOldSpks löscht nie

**Datei:** `crypto/KeyRotationManager.kt:269–272`

Die Schleife logged nur, entfernt aber keine Einträge aus dem JSONArray. Alte Signed-PreKeys akkumulieren für immer in SharedPreferences.

### 35. ContactRepository — `name.hashCode().absoluteValue` Überlauf

**Datei:** `data/ContactRepository.kt:181`

`Int.MIN_VALUE.absoluteValue` überläuft und bleibt `Int.MIN_VALUE` → negative Modulo → `ArrayIndexOutOfBoundsException` bei der Avatar-Farbauswahl (sehr selten, 1:4 Mrd.).

### 36. AiPromptTruncator — Fängt alle Exceptions von TokenCounter

**Datei:** `ai/AiPromptTruncator.kt:28`

`catch (e: Exception)` maskiert native Crashes und Serialisierungsfehler → falsche Token-Schätzungen ohne Fehlersichtbarkeit.

### 37. CrisixForegroundService — `close()` ohne try-catch in `onDestroy`

**Datei:** `service/CrisixForegroundService.kt:82`

Wenn `AiModelManager.close()` eine Exception wirft, propagiert sie aus `onDestroy()` → Prozess-Crash.

### 38. UpdateManager — Hardcodierte Certificate-Pins

**Datei:** `update/UpdateManager.kt:63–69`

Wenn GitHub sein TLS-Zertifikat rotiert (historisch passiert), schlagen **alle** Update-Checks permanent fehl.

---

## NIEDRIG / INFO (Kosmetisch, CW-frei)

### 39. X3DHSession — OPK wird konsumiert bevor Handshake abgeschlossen

**Datei:** `crypto/X3DHSession.kt:149–150`

`ownOneTimePreKeys.removeFirst()` beim Erstellen des PreKey-Bundles. Wenn der Handshake nie abgeschlossen wird (Netzwerkfehler), ist der OPK unwiederbringlich verloren.

### 40. ContactImportExport — Ein korrupter Kontakt killt den gesamten Import

**Datei:** `data/Contact.kt:61`

`listFromJson()` hat keinen try-catch pro Eintrag. Ein einziger korrupter JSON-Kontakt → gesamter Import schlägt fehl.

### 41. MessageRepository — DAO-Aufrufe ohne Transaktion

**Datei:** `data/MessageRepository.kt:136–139, 153–156, 178–183, 186–204, 206–218`

Mehrere zusammengehörige DAO-Calls (z.B. delete + insert) sind nicht in `@Transaction` gewrapped. Bei Prozess-Tod zwischen den Calls → inkonsistenter DB-Zustand.

### 42. MainlineDhtNode — Port < 1024 auf Non-Rooted Devices

**Datei:** `transport/internet/MainlineDhtNode.kt:150–153`

Bind an Port 6881 (Standard DHT). Auf nicht gerooteten Android-Geräten kann Port < 1024 fehlschlagen → Fallback zu Port 0 (nicht erreichbar) ist korrekt, aber nicht dokumentiert.

### 43. EncryptedSessionStorage — Silent Security Downgrade

**Datei:** `crypto/EncryptedSessionStorage.kt:70–73`

Wenn Android KeyStore nicht verfügbar, Fallback zu Plaintext-SharedPreferences. Sessions bleiben im Klartext, bis Migration manuell erneut angestoßen wird.

### 44. AiModelManager — OkHttpClient nie heruntergefahren

**Datei:** `ai/AiModelManager.kt:103–106, 668`

Der für Model-Downloads genutzte `OkHttpClient` wird nie via `dispatcher().executorService().shutdown()` heruntergefahren. Verbindungs-Pool-Threads bleiben bis Prozess-Ende.

### 45. MessageSender — `userProfile` kann uninitialisiert sein

**Datei:** `message/MessageSender.kt:39`

`private var userProfile: UserProfile = UserProfile()` — Standard-Konstruktor mit leeren/default Werten. Wenn `sendText()` vor `setUserProfile()` aufgerufen wird, wird ein leerer Profilname gesendet.

### 46. AudioRecorder — `prepare()`/`start()` ohne try-catch

**Datei:** `util/AudioRecorder.kt:28–29`

`MediaRecorder.prepare()` und `start()` ohne Exception-Handling → Crash bei Aufnahmefehlern.

### 47. MainActivity — BiometricPrompt kann auf älteren APIs fehlschlagen

**Datei:** `MainActivity.kt:119,154`

`setAllowedAuthenticators(BIOMETRIC_STRONG | DEVICE_CREDENTIAL)` benötigt API 30+. Auf älteren Android-Versionen schlägt die Authentifizierung still.

### 48. NotificationHelper — `pendingMessages` Map wächst unbegrenzt

**Datei:** `util/NotificationHelper.kt:32`

Kein Mechanismus zum Prunen alter Chat-Einträge. `pendingMessages` akkumuliert über die gesamte App-Lebensdauer → potenzielles Memory Leak.

### 49. MessageProcessor — Unzusammenhängende E2EE-Zustände bei Fehlern

**Datei:** `message/MessageProcessor.kt:227,256`

Wenn `e2eeManager.handleHandshake()` wirft, wurde bereits `e2eeManager.setSessionActive()` aufgerufen → Session in inkonsistentem Zustand.

---

## ÜBERGREIFENDE THEMEN / ARCHITEKTUR-PROBLEME

### A. Thread-Sicherheit in Transport-Komponenten
Mehrere Transporte (Ble, Wifi, WifiAware, SMS) schreiben teils von Callback-Threads und teils von Coroutine-Threads aus in gemeinsame Datenstrukturen ohne Synchronisation.

### B. Fehlende Timeouts bei Netzwerk-I/O
`WifiTransport.readMessage()`, `Libp2pManager.readFully()`, `MainlineDhtNode.startReceiveLoop` haben keine oder inadäquate Timeouts → können Coroutines dauerhaft blockieren.

### C. Kein zentrales Lifecycle-Management für Transports
`stop()`-Methoden hinterlassen oft Ressourcen (Coroutines, Sockets, Callbacks) aktiv, weil Scopes nicht gecancelt oder Callbacks nicht deregistriert werden.

### D. UI-State direkt aus `remember` statt ViewModel
`CrisixApp.kt` erstellt mehrere AI-Komponenten (`AiChatViewModel`, `AiInferenceController`, etc.) in `remember` statt via `viewModel()`. Diese überleben keinen Configuration-Change.

### E. Cryptography: Nonce-Reuse durch fehlende Thread-Sicherheit
Das Double-Ratchet-Protokoll ist korrekt implementiert (abgesehen von OutOfOrderMessageHandler), aber die fehlende Synchronisation macht Nonce-Reuse praktisch garantiert bei parallelem Zugriff.

### F. Kein Schutz vor Peer-Identity-Spoofing
Die `Self-Connection`-Erkennung in `BleTransport.kt:502–503` vergleicht nur Strings. Ein Relay-Transport, der die Nachricht zurückspiegelt, wird nicht erkannt.

---

## ZUSAMMENFASSUNG

| Schweregrad | Anzahl | Schwerpunkte |
|---|---|---|
| **Kritisch** | 7 | Crypto-Race, Bitmap-Crash, Datenverlust, Key-Leak im Log |
| **Hoch** | 11 | NPE-Crashes, ANR, OOM, Thread-Leaks, Modell-Crash |
| **Mittel** | 20 | Logikfehler, Ressourcen-Leaks, Dead Code, Konfigurationsfehler |
| **Niedrig/Info** | 11 | Kosmetisch, seltene Edge Cases, unkritische Lecks |

**Top 3 dringendste Fixes:**

1. **DoubleRatchet Threadsicherheit** (`crypto/DoubleRatchet.kt:73–93`) — alle `ratchetEncrypt`/`ratchetDecrypt`-Aufrufe synchronisieren
2. **ImageCompressor Bitmap-Recycle-Loop** (`util/ImageCompressor.kt:34,42`) — `recycle()` erst nach der Schleife
3. **Defragmenter `receivedCount` Atomic** (`transport/Defragmenter.kt:44`) — `AtomicInteger` statt `var`
