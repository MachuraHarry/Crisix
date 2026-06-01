# Crisix — Verbesserungsplan

## 1. CrisixApp.kt splitten (CRITICAL)

**Datei:** `app/src/main/java/com/messenger/crisix/ui/navigation/CrisixApp.kt` (ca. 2.400 Zeilen)

### 1.1 Zielarchitektur

```
ui/navigation/
  CrisixApp.kt          → Navigation-Graph + Top-Level-Composable (Ziel: <400 Zeilen)
ui/viewmodel/
  CrisixViewModel.kt    → App-weiter State-Holder (Transport-State, Messages, Contacts, E2EE-Status)
ui/state/
  CrisixAppState.kt     → Data Class für gesamten App-Zustand
transport/
  TransportInitializer.kt → Transport-Registrierung und -Start
message/
  MessageProcessor.kt   → Eingehende Nachrichten verarbeiten (Text, Bild, Voice, Binary)
  MessageSender.kt      → Ausgehende Nachrichten (inkl. E2EE, Fragmentation)
e2ee/
  E2EEHandshakeOrchestrator.kt → Handshake-Koordination (bisher in CrisixApp.kt)
```

### 1.2 Schritt-für-Schritt

**Schritt 1.2.1 — `CrisixAppState.kt` erstellen**
- Paket: `com.messenger.crisix.ui.state`
- Felder:
  - `allMessages: Map<String, List<Message>>`
  - `currentMessages: List<Message>`
  - `currentChatPeerId: String`
  - `discoveredPeers: List<Peer>`
  - `connectionStatuses: Map<TransportType, ConnectionStatus>`
  - `activeTransport: Transport?`
  - `incomingNames: Map<String, String>`
  - `unreadCounts: Map<String, Int>`
  - `savedContacts: List<Contact>`
  - `e2eeSessions: Map<String, Boolean>`
  - `pendingHandshakes: Map<String, HandshakeInitData>`
  - `transportSettings: Map<TransportType, Boolean>`
  - `userProfile: UserProfile`
  - `isSetupComplete: Boolean`
  - `deviceId: String`
  - `pinnedChatIds: Set<String>`

**Schritt 1.2.2 — `CrisixViewModel.kt` erstellen**
- Paket: `com.messenger.crisix.ui.viewmodel`
- Konstruktor-Abhängigkeiten: `MessageRepository`, `ContactRepository`, `Context` (für SharedPreferences)
- Exposed: `val state: StateFlow<CrisixAppState>`
- Methoden:
  - `fun updateMessages(peerId: String, messages: List<Message>)`
  - `fun addMessage(peerId: String, message: Message)`
  - `fun removeMessage(messageId: String)`
  - `fun setCurrentChat(peerId: String)`
  - `fun updateDiscoveredPeers(peers: List<Peer>)`
  - `fun updateConnectionStatuses(statuses: Map<TransportType, ConnectionStatus>)`
  - `fun updateE2eeSession(peerId: String, hasSession: Boolean)`
  - `fun addPendingHandshake(peerId: String, data: HandshakeInitData)`
  - `fun togglePinChat(chatId: String)`
  - `fun deleteChat(chatId: String)`
  - `fun loadFromPrefs()`, `fun saveToPrefs()`

**Schritt 1.2.3 — `TransportInitializer.kt` erstellen**
- Paket: `com.messenger.crisix.transport`
- Enthält `fun initializeTransports(context: Context, deviceId: String): TransportManager`
- Extrahiert aus CrisixApp.kt Zeilen ~437–474:
  - `WifiTransport`-Erstellung und -Registrierung
  - `BleTransport`-Erstellung und -Registrierung
  - `InternetTransport`-Erstellung und -Registrierung
  - `DnsTunnelTransport`-Erstellung und -Registrierung
  - `RelayTransport`-Erstellung und -Registrierung
  - Transport-Startlogik
- Hardcoded Server-URLs durch `BuildConfig`-Felder ersetzen:
  ```kotlin
  // build.gradle.kts
  buildConfigField("String", "DNS_TUNNEL_SERVER", "\"crisix-dns.onrender.com\"")
  buildConfigField("String", "RELAY_URL", "\"wss://crisix-dns.onrender.com/ws\"")
  ```

**Schritt 1.2.4 — `MessageProcessor.kt` erstellen**
- Paket: `com.messenger.crisix.message`
- Extrahiert aus CrisixApp.kt:
  - `registerMessageListener` (Zeilen ~462–1100): Callback für eingehende Nachrichten
  - Bild-Verarbeitung (image type handler)
  - Voice-Verarbeitung (voice type handler)
  - Text-Verarbeitung (default type handler)
  - Binary-Encrypted-Verarbeitung (`crisix_e2ee_binary` type handler)
  - E2EE-Handshake-Verarbeitung (`crisix_e2ee_handshake` type handler)
  - ACK-Verarbeitung
  - Ping/Pong-Verarbeitung
  - `handleIncomingNotification()`-Hilfsfunktion
  - `markSentAsDelivered()`-Hilfsfunktion
- Parameter: `CrisixViewModel`, `TransportManager`, `E2eeManager`, `AckValidator`, `MessageRepository`, `processedIncomingIds`
- Callbacks: `onNotificationNeeded(senderId, senderName, preview)`

**Schritt 1.2.5 — `MessageSender.kt` erstellen**
- Paket: `com.messenger.crisix.message`
- Extrahiert aus CrisixApp.kt:
  - `onSendMessage`-Logik aus ChatDetailScreen-Callback (Zeilen ~1900–2100)
  - Bild-Sende-Logik (Zeilen ~1780–1840)
  - Voice-Sende-Logik (Zeilen ~1860–1920)
  - E2EE-Handshake-Initiierung beim Senden
  - `isRealPeer`-Prüfung
  - Message-Payload-Konstruktion (JSON)
  - Fragmentation via TransportManager

**Schritt 1.2.6 — `E2EEHandshakeOrchestrator.kt` erstellen**
- Paket: `com.messenger.crisix.e2ee`
- Extrahiert aus CrisixApp.kt:
  - `handleReceivedHandshake()` (Zeilen ~584–690)
  - `completeHandshakeAsInitiator()` (Zeilen ~1013–1096)
  - ACK-Senden bei Handshake-Abschluss
  - Session-Persistierung nach erfolgreichem Handshake
  - QR-Handshake-Logik
- Parameter: `E2eeManager`, `AckValidator`, `CrisixViewModel`, `TransportManager`
- Callbacks: `onSessionEstablished(peerId)`, `onHandshakeFailed(peerId, reason)`

**Schritt 1.2.7 — `CrisixApp.kt` aufräumen**
- Nach allen Extraktionen sollte die Datei nur noch enthalten:
  - `rememberNavController()`
  - `CrisixViewModel`-Erstellung via `viewModel()`
  - `TransportInitializer.initializeTransports()`
  - `MessageProcessor`- und `MessageSender`-Instanziierung
  - `E2EEHandshakeOrchestrator`-Instanziierung
  - Navigation-Graph (`NavHost` mit `composable`-Blöcken)
  - Update-Check-Dialog
  - QR-Code-Parsing-Helper
- Entfernen: `getMessagePreview()` privat (bereits erledigt), `handleIncomingNotification()` privat
- Entfernen: alle in andere Dateien verschobenen Imports

---

## 2. fallbackToDestructiveMigration entfernen (CRITICAL)

**Datei:** `app/src/main/java/com/messenger/crisix/data/AppDatabase.kt:29`

### Umsetzung

1. `fallbackToDestructiveMigration()` durch echte `Migration`-Objekte ersetzen
2. Für jede DB-Version (aktuell 8) eine Migration-Klasse definieren:
```kotlin
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ALTER TABLE falls nötig, sonst leer
    }
}
```
3. `Room.databaseBuilder(...).addMigrations(MIGRATION_7_8).build()`
4. Falls kein Schema-Export existiert: `exportSchema = true` setzen und `room.schemaLocation` in build.gradle.kts konfigurieren

---

## 3. OneTimePreKeys verschlüsseln (CRITICAL)

**Datei:** `app/src/main/java/com/messenger/crisix/crypto/E2eeManager.kt:984-1023`

### Umsetzung

1. `EncryptedSessionStorage` (existiert bereits) auch für OneTimePreKeys nutzen
2. Statt `prefs.edit().putString("otpk_$id", Base64.encodeToString(keyBytes, Base64.NO_WRAP))`:
```kotlin
encryptedStorage.save("otpk_$id", keyBytes) // nutzt Android Keystore-backed AES
```
3. Beim Laden: `encryptedStorage.load("otpk_$id")` statt `Base64.decode(prefs.getString(...), ...)`
4. Optional: Anzahl der OneTimePreKeys in Plaintext-Prefs speichern (kein Secret), um schnelle Verfügbarkeitsprüfung zu ermöglichen

---

## 4. Exception-Schlucken fixen (HIGH)

**Betroffene Dateien:** 53 Instanzen von `catch (_: Exception) {}`

### Umsetzung (pro Datei)

**BleTransport.kt** (25 Instanzen, höchste Priorität):
```kotlin
// Statt:
} catch (_: Exception) {}

// Immer:
} catch (e: Exception) {
    Log.w(TAG, "BLE operation failed: ${e.message}", e)
    // Ggf. State updaten: _connectionState.value = ConnectionState.ERROR
}
```

**WifiTransport.kt** (11 Instanzen):
- Gleiches Muster, mit `Log.w(TAG, "WiFi operation failed", e)`

**DnsTunnelTransport.kt** (6 Instanzen):
- Gleiches Muster, mit `Log.w(TAG, "DNS operation failed", e)`

**TransportManager.kt** (4 Instanzen):
- Gleiches Muster

**Libp2pManager.kt** (4 Instanzen):
- Gleiches Muster

### Automatisierungshilfe
```bash
# Finden aller betroffenen Stellen:
rg "catch \(_: Exception\) \{\}" --type kotlin -l
# → BleTransport.kt, WifiTransport.kt, DnsTunnelTransport.kt, TransportManager.kt, Libp2pManager.kt
```

---

## 5. `!!`-Assertions ersetzen (HIGH)

**Betroffene Dateien:** 21 Instanzen

### Umsetzung

**E2eeManager.kt** (12 Instanzen):
```kotlin
// Statt:
val identityKey = loadIdentityKey()!!  // Crash wenn null

// Immer mit Fallback:
val identityKey = loadIdentityKey() ?: run {
    Log.e(TAG, "Identity key missing, reinitializing")
    generateNewIdentityKey()
    loadIdentityKey() ?: throw IllegalStateException("Cannot initialize identity key")
}
```

**Libp2pManager.kt** (4 Instanzen):
```kotlin
// Statt:
keyPair!!.public.raw

// Mit Fallback:
val kp = keyPair ?: return
kp.public.raw
```

**EncryptedSessionStorage.kt** (3 Instanzen):
- `encryptedPrefs` sollte als `lateinit var` oder mit Lazy-Initialisierung gehandhabt werden

---

## 6. Permission-Bug fixen (HIGH)

**Datei:** `app/src/main/java/com/messenger/crisix/ui/screens/PermissionSetupScreen.kt:93-123`

### Umsetzung

```kotlin
// FALSCH: Alle Launcher gleichzeitig feuern
fun requestAllPermissions() {
    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
    // ... nur der letzte launch wird wirksam
}

// RICHTIG: Sequentiell anfordern via Callback-Kette
fun requestAllPermissions() {
    requestNextPermission(0)
}

fun requestNextPermission(index: Int) {
    val permissions = listOf(/* geordnete Liste */)
    if (index >= permissions.size) {
        allPermissionsGranted = true
        return
    }
    // Je nach Permission-Typ den passenden Launcher aufrufen,
    // im Callback dann requestNextPermission(index + 1)
}
```

---

## 7. Hardcoded Server-URLs in BuildConfig auslagern (MEDIUM)

**Betroffene Dateien:**
- `CrisixApp.kt:459` — `"crisix-dns.onrender.com"`
- `CrisixApp.kt:466` — `"wss://crisix-dns.onrender.com/ws"`
- `DnsTunnelTransport.kt:62` — `"crisix-dns.onrender.com"`
- `RelayTransport.kt:18` — `"wss://crisix-dns.onrender.com/ws"`
- `SettingsScreen.kt:107-108` — `"192.168.178.32"`, `54232`

### Umsetzung

1. In `app/build.gradle.kts`:
```kotlin
defaultConfig {
    buildConfigField("String", "DNS_TUNNEL_SERVER", "\"crisix-dns.onrender.com\"")
    buildConfigField("String", "RELAY_URL", "\"wss://crisix-dns.onrender.com/ws\"")
    buildConfigField("String", "DEFAULT_RELAY_HOST", "\"192.168.178.32\"")
    buildConfigField("int", "DEFAULT_RELAY_PORT", "54232")
}
```

2. Alle Hartcodierungen durch `BuildConfig.DNS_TUNNEL_SERVER` etc. ersetzen
3. `import com.messenger.crisix.BuildConfig` in betroffenen Dateien hinzufügen

---

## 8. Dark-Mode-Toggle einbauen (MEDIUM)

**Dateien:**
- `app/src/main/java/com/messenger/crisix/ui/theme/Theme.kt`
- `app/src/main/java/com/messenger/crisix/ui/screens/SettingsScreen.kt`

### Umsetzung

1. In `Theme.kt`:
```kotlin
@Composable
fun CrisixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colorScheme, content = content)
}
```

2. Light-Color-Scheme definieren (analog zum existierenden dunklen Scheme)
3. In `SettingsScreen.kt`: Toggle-Switch "Dark Mode" mit Persistenz in `setupPrefs`
4. In `MainActivity.kt`: Theme-Auswahl aus `setupPrefs` lesen und an `CrisixTheme` übergeben
5. `Recomposition`-Test: Theme-Wechsel soll ohne Activity-Neustart funktionieren

---

## 9. SMS/LoRa aus UI entfernen oder implementieren (MEDIUM)

**Betroffene Dateien:**
- `app/src/main/java/com/messenger/crisix/ui/screens/SettingsScreen.kt:591`
- `app/src/main/java/com/messenger/crisix/ui/screens/TransportSetupScreen.kt:115`
- `app/src/main/java/com/messenger/crisix/transport/TransportType.kt`

### Umsetzung (Option A: Ausblenden)

1. In `TransportSetupScreen.kt` und `SettingsScreen.kt`: SMS/LoRa-Toggles mit `if (false)` ausblenden oder ganz entfernen
2. `DummyTransport.kt` nach `src/test/` verschieben oder löschen
3. Kommentar "Coming Soon" entfernen

### Umsetzung (Option B: Implementieren)

1. **SMS**: `SmsTransport.kt` erstellen, `SmsManager` aus Android SDK nutzen
   - SEND: `smsManager.sendTextMessage(phoneNumber, null, base64Message, ...)`
   - RECEIVE: `BroadcastReceiver` für `SMS_RECEIVED`
   - Payload-Größenlimit: 160 Zeichen pro SMS → Chunking nötig
2. **LoRa**: Abhängig von externem LoRa-Modul (SPI/USB) — sehr hardware-spezifisch
   - Wahrscheinlich nicht praktikabel für generische Android-Geräte
   - → Empfehlung: Aus UI entfernen bis Hardware-Unterstützung vorhanden

---

## 10. ViewModels für weitere Screens (MEDIUM)

### 10.1 ChatDetailViewModel

**Neue Datei:** `app/src/main/java/com/messenger/crisix/ui/viewmodel/ChatDetailViewModel.kt`

- State:
  - `messages: List<Message>` (per PagingData)
  - `chatPeerId: String`
  - `chatName: String`
  - `hasE2eeSession: Boolean`
  - `isHandshaking: Boolean`
  - `transportCapabilities: TransportCapabilities`
- Methoden:
  - `fun loadMessages(chatId: String)` — Paging aus `MessageRepository`
  - `fun sendTextMessage(text: String, replyTo: String?)`
  - `fun sendImage(uri: Uri)`
  - `fun sendVoice(audioBytes: ByteArray, durationMs: Long)`
  - `fun deleteMessage(messageId: String)`
  - `fun markAsRead()`

### 10.2 SettingsViewModel

**Neue Datei:** `app/src/main/java/com/messenger/crisix/ui/viewmodel/SettingsViewModel.kt`

- State:
  - `transportSettings: Map<TransportType, Boolean>`
  - `userProfile: UserProfile`
  - `currentLanguage: String`
  - `relayHost: String`, `relayPort: Int`
  - `updateState: UpdateState`
- Methoden:
  - `fun toggleTransport(type: TransportType)`
  - `fun updateProfile(name: String, status: String, color: Int)`
  - `fun changeLanguage(lang: String)`
  - `fun checkForUpdate()`
  - `fun updateRelayConfig(host: String, port: Int)`

### 10.3 ConnectionsViewModel

**Neue Datei:** `app/src/main/java/com/messenger/crisix/ui/viewmodel/ConnectionsViewModel.kt`

- State:
  - `connectionStatuses: Map<TransportType, ConnectionStatus>`
  - `discoveredPeers: List<Peer>`
  - `dnsTestResult: String?`
- Methoden:
  - `fun refreshStatus()`
  - `fun testDnsTunnel()`
  - `fun reconnectTransport(type: TransportType)`

---

## 11. Tests schreiben (MEDIUM)

### 11.1 ViewModel-Tests

**Neue Datei:** `app/src/test/java/com/messenger/crisix/ui/viewmodel/ChatListViewModelTest.kt`

```kotlin
class ChatListViewModelTest {
    @Test
    fun `computeChats sorts pinned first`()
    @Test
    fun `computeChats filters by search query`()
    @Test
    fun `computeChats resolves contact names over peer names`()
    @Test
    fun `deleteChat removes from repository`()
}
```

### 11.2 UI-Tests (Compose)

**Neue Datei:** `app/src/androidTest/java/com/messenger/crisix/ui/ChatListScreenTest.kt`

```kotlin
class ChatListScreenTest {
    @Test
    fun `shows empty state when no chats`()
    @Test
    fun `shows CTA button in empty state`()
    @Test
    fun `filters chats by search query`()
    @Test
    fun `shows pinned chats first`()
    @Test
    fun `swipe triggers undo snackbar`()
}
```

### 11.3 E2eeManager-Tests

**Neue Datei:** `app/src/test/java/com/messenger/crisix/crypto/E2eeManagerTest.kt`

- Test: Identity-Key-Generierung und -Persistenz
- Test: OneTimePreKey-Generierung und -Upload
- Test: Session-Etablierung (X3DH + DoubleRatchet)
- Test: Session-Persistenz und -Wiederherstellung
- Test: Key-Rotation

---

## 12. Weitere Quick Wins (LOW)

| # | Thema | Aktion |
|---|-------|--------|
| 12.1 | `NotificationHelper.kt` hartcodierte Strings | "Gelesen", "Nachrichten", "$count neue Nachrichten" → strings.xml |
| 12.2 | `DummyTransport.kt` | Nach `src/test/` verschieben oder löschen |
| 12.3 | `ExampleUnitTest.kt`, `ExampleInstrumentedTest.kt` | Löschen |
| 12.4 | `AudioBubble` hardcoded Snackbar-Texte | → strings.xml |
| 12.5 | `OnboardingScreen`: Logo-Referenz prüfen | Existiert `R.drawable.crisix_logo`? |
| 12.6 | DB `exportSchema = true` setzen | In `AppDatabase.kt` und build.gradle.kts |
| 12.7 | `LocaleHelper.updateLocale()` deprecated API | `createConfigurationContext()` nutzen |
| 12.8 | `ChatDetailScreen` empty state verbessern | Guidance-Text statt nur "No messages" |

---

## Umsetzungsreihenfolge

| Rang | Punkt | Aufwand | Begründung |
|------|-------|---------|------------|
| 1 | Exception-Schlucken fixen (#4) | 1–2h | Riesiger Debugging-Gewinn, rein mechanisch |
| 2 | `!!`-Assertions ersetzen (#5) | 1h | Crash-Sicherheit |
| 3 | Permission-Bug (#6) | 30min | Funktionaler Bug |
| 4 | Hardcoded URLs (#7) | 30min | Sauberkeit, Konfigurierbarkeit |
| 5 | SMS/LoRa ausblenden (#9) | 15min | Verwirrung vermeiden |
| 6 | Quick Wins (#12) | 1h | Viele kleine Verbesserungen |
| 7 | OneTimePreKeys (#3) | 1h | Sicherheitslücke schließen |
| 8 | CrisixApp.kt splitten (#1) | 4–6h | Größter Architektur-Gewinn |
| 9 | ViewModels (#10) | 3–4h | State-Management pro Screen |
| 10 | DB-Migration (#2) | 2h | Datenverlust verhindern |
| 11 | Tests (#11) | 3–4h | Regressionen verhindern |
| 12 | Dark Mode (#8) | 1h | UX-Feature |
