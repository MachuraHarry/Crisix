# Crisix — Audit-Funde & Optimierungspotential

> Stand: 27.05.2026, basierend auf vollständigem Code-Review aller ~45 Kotlin-Dateien.

---

## 🔴 Kritisch

### 1. Krypto ist komplett kaputt

**Datei:** `app/…/internet/NoisePacketCrypto.kt`

**`encryptWithKey()`** (Zeile 570-572):
```kotlin
val tag = ByteArray(TAG_LEN)
java.security.SecureRandom().nextBytes(tag)
```
Der Poly1305-Authentifizierungs-Tag wird per `SecureRandom` generiert statt aus Chiffrat + Key berechnet.  
→ **Jeder Angreifer kann das Chiffrat beliebig modifizieren und einen eigenen Tag setzen**  
→ Chiffrat hat **keinerlei Integritätssicherung**

**`inv()`** (Zeile 698-701):
```kotlin
private fun inv(a: LongArray): LongArray {
    return a.copyOf() // Vereinfachte Inversion
}
```
Die modulare Inverse wird nie berechnet – `a.copyOf()` gibt den Input unverändert zurück.  
→ Der X25519 Montgomery-Ladder-Ausgang ist **komplett falsch**  
→ **Kein echter Schlüsselaustausch** – die gesamte Noise-XX-Verschlüsselung ist wirkungslos

Kommentar räumt ein: _"vereinfacht – in Produktion würde man AEAD verwenden"_. Diese "Vereinfachung" macht die gesamte Kryptographie **nutzlos**.

### 2. Privater Schlüssel im Klartext in SharedPreferences

**Dateien:** `InternetTransport.kt` (Z. 396–412), `CrisixApp.kt` (Z. 82–101)

Der Ed25519-Private-Key (64 Byte) wird als Base64 in `SharedPreferences("crisix_identity")` gespeichert.  
`CryptoHelper.saveToAndroidKeyStore()` existiert, wird aber **nie aufgerufen**.  
`CryptoHelper.loadFromAndroidKeyStore()` gibt hart `null` zurück (Z. 272).  

→ Jede App mit ADB-Backup-Zugriff kann den Key extrahieren  
→ **Android KeyStore wird komplett ignoriert**

### 3. Duplikat-Quellbaum `Crisix/`

**Ort:** `/Crisix/` (eigenständiges Git-Repo mit eigener `build.gradle.kts`)

- ~40+ duplizierte Kotlin-Dateien
- Enthält veraltete Transporte (`BluetoothTransport`, `SmsTransport`, `LoRaTransport`), die im aktiven `app/` fehlen
- Fixes im aktiven Baum gammeln im Duplikat
- **Muss gelöscht oder aktiv ausgeschlossen werden**

### 4. WifiTransport korrumpiert Binärdaten

**Datei:** `WifiTransport.kt`, Zeile 98

```kotlin
return String(charArray).toByteArray()
```
`readMessage()` liest in ein `CharArray` via `BufferedReader`, konvertiert zu `String` und zurück zu `ByteArray`.  
→ Korrumpiert Binärdaten (Bilder, verschlüsselte Payloads, Protokoll-Nachrichten)  
→ `WifiTransport` gibt `supportsImages = true` an, kann aber gar keine Binärdaten transportieren

### 5. RelayTransport generiert bei jedem Start neue Zufalls-ID

**Datei:** `RelayTransport.kt`, Zeile 77

```kotlin
private val deviceId: String = UUID.randomUUID().toString()
```

Ignoriert den Ed25519-Fingerprint komplett. Jeder Start erzeugt eine neue Identität.

---

## 🟡 Major

### 6. DummyTransport schluckt Nachrichten lautlos

**Datei:** `DummyTransport.kt`

```kotlin
override val type: TransportType = TransportType.INTERNET
override suspend fun isAvailable(): Boolean = true
```

- Gibt sich als `INTERNET`-Typ aus → wird vor dem echten InternetTransport priorisiert
- `isAvailable() = true` → immer aktiv
- `send()` gibt immer `success(Unit)` zurück, sendet aber **nichts**
- Führt zu **lautlosem Nachrichtenverlust**

### 7. WifiTransport.send() ist via TransportManager unbrauchbar

**Datei:** `WifiTransport.kt` / `TransportManager.kt`

`TransportManager.sendMessage()` ruft `wifiTransport.send(peerId, data)` auf – aber `peerId` ist der rohe Fingerprint (ohne `@ip`).  
`WifiTransport.send()` parst `fingerprint@ip` über `parsePeerAddress()` → findet nichts → **immer Fehler**.

### 8. Leaking Coroutines (MainScope)

**Datei:** `CrisixApp.kt`, Zeilen 348, 413, 522

```kotlin
kotlinx.coroutines.MainScope().launch { ... }
```

Drei Stellen erzeugen ad-hoc `MainScope()`-Instanzen in Composable-Callbacks.  
→ **Nie gecancelled** → Speicher-Leck bei Recomposition

**Fix:** `val scope = rememberCoroutineScope()` und `scope.launch { ... }`

### 9. deliveryUpdates SharedFlow kann Updates droppen

**Datei:** `TransportManager.kt`, Zeile 51

```kotlin
private val _deliveryUpdates = MutableSharedFlow<DeliveryUpdate>(extraBufferCapacity = 64)
```

Bei 64 aufgelaufenen Updates ohne Collector-Verarbeitung → **Updates werden lautlos verworfen**.  
→ Sollte `Channel(CONFLATED)` oder `SharedFlow(replay=1)` sein

### 10. ~5000 Zeilen toter Code

Folgende Dateien werden nirgends instanziiert oder aufgerufen:

| Datei | Zeilen | Status |
|---|---|---|
| `HyperswarmDhtNode.kt` | ~800 | Unused |
| `HyperswarmProtocol.kt` | ~500 | Unused |
| `DhtNode.kt` | ~700 | Unused |
| `PeerDiscoveryManager.kt` | ~700 | Unused |
| `PeerRelay.kt` | ~600 | Unused |

### 11. TransportManager.sendMessage() fragmentierte Fallback-Logik

**Datei:** `TransportManager.kt`, Zeilen 276–353

Aktuelle Reihenfolge: WifiTransport → activeTransport → InternetTransport → activeTransport (erneut) → DNS-Tunnel  

- Der `activeTransport` wird **zweimal** probiert (unnötig)
- Der `transportResult` auf Zeile 352 kann von einem **vorherigen, fehlgeschlagenen Versuch** stammen
- Schwer nachvollziehbar und wartungsintensiv

---

## 🔵 Minor

### 12. println() statt Android-Logging

`TransportManager.kt`, `WifiTransport.kt`, `RelayTransport.kt`, `DummyTransport.kt`, `CrisixApp.kt` — insgesamt **50+ Stellen** mit `println()`.  
Kein Tag-Filtering, keine Log-Level, kein `Timber`.

### 13. Keine Unit-Tests

- `ExampleUnitTest.kt`: nur `2 + 2 == 4`
- `InternetTransportTest.kt`: leer/skeletal
- `ExampleInstrumentedTest.kt`: Platzhalter

**Null Tests** für: `TransportManager`, `CrisixProtocol`, `DnsTunnelTransport`, `WifiTransport`, `Libp2pManager`, `NoisePacketCrypto`, `CryptoHelper`, `ContactRepository`, `MainlineDhtNode`.

### 14. CrisixApp.kt überladen (709 Zeilen)

Eine Datei macht alles:
- Key-Generierung und -Laden
- Transport-Initialisierung (4 Transporte)
- Message-Listener-Wiring
- Delivery-Update-Collection
- Chat-List-Computation
- NavHost mit 10 Destinationen
- QR-Code-Parsing
- Echo-Chat-Logik

→ Sollte in ViewModel + kleinere Composables aufgeteilt werden.

### 15. Magic Numbers hartkodiert

| Wert | Datei | Zeile |
|---|---|---|
| `POLL_INTERVAL_MS = 5000` | `DnsTunnelTransport.kt` | 54 |
| `253 - suffix.length - 5` (DNS-Limit) | `DnsTunnelTransport.kt` | 488 |
| `socket.connect(…, 5000)` | `WifiTransport.kt` | 152 |
| `stream.socket.soTimeout = 60_000` | `Libp2pManager.kt` | 371 |
| `delay(500)` (Polling für localPeerId) | `CrisixApp.kt` | 250 |

### 16. Fehlende @Volatile-Annotation

**Datei:** `WifiTransport.kt`, Zeilen 49–53

`isRunning` wird von mehreren Coroutines gelesen/geschrieben aber ist nicht `@Volatile`.

### 17. ServerSocket-Leck in WifiTransport.stop()

**Datei:** `WifiTransport.kt`, Zeilen 290–291

`serverSocket` wird nur in der `accept()`-Schleife geschlossen, nicht in `stop()`.  
Wenn `stop()` während blockierendem `accept()` aufgerufen wird → **Socket-Leck**.

### 18. TransportType hat 3 ungenutzte Einträge

`BLUETOOTH_MESH`, `SMS`, `LORA` haben keine Implementierung im aktiven `app/`-Baum.  
Cluttern die Priority-Order in `TransportManager`.

### 19. Base32-Implementierung ineffizient

**Datei:** `DnsTunnelTransport.kt`, Zeilen 90–116

Handgeschriebenes Base32 mit String-Konkatenation und `toInt(2)`-Parsing.  
- O(n) mit schwerer String-Allokation
- Kein Padding (`=` nach RFC 4648)
- Sollte durch `Apache Commons Codec` oder eigene optimierte Implementierung ersetzt werden

### 20. Alle UI-Strings hartcodiert auf Deutsch

`LocaleHelper` existiert, aber es gibt keine `strings.xml`-Ressourcen.  
→ Keine Übersetzung möglich

---

## 🟢 Vorschläge

### S1. `Crisix/`-Duplikat löschen

Kein Grund, den veralteten Baum zu behalten. Alle relevanten Änderungen sind im `app/`-Baum.

### S2. Tote Dateien entfernen

```bash
rm app/.../internet/HyperswarmDhtNode.kt
rm app/.../internet/HyperswarmProtocol.kt
rm app/.../internet/DhtNode.kt
rm app/.../internet/PeerDiscoveryManager.kt
rm app/.../internet/PeerRelay.kt
```

### S3. DummyTransport.disablen

`type` auf neuen `DUMMY`-Enum setzen oder `isAvailable() = false`.

### S4. `print()` → `Timber` oder `Log`

Alle 50+ Stellen ersetzen.

### S5. `rememberCoroutineScope()` statt `MainScope()`

Drei Stellen in `CrisixApp.kt`.

### S6. `Dispatchers.Default` für CPU-lastige Operationen

`CryptoHelper` Key-Generierung, `MainlineDhtNode` Kademlia, `NoisePacketCrypto`.

### S7. Android KeyStore verwenden

`saveToAndroidKeyStore()` aufrufen, `loadFromAndroidKeyStore()` implementieren.

### S8. Krypto korrigieren oder entfernen

Entweder echten X25519 + ChaCha20-Poly1305 implementieren (über `javax.crypto` oder `tink`) – oder die Noise-Komponente ausbauen, wenn Sicherheit nicht benötigt wird.

---

## Fazit

**Höchste Priorität:**
1. `DummyTransport` deaktivieren (verursacht lautlosen Nachrichtenverlust)
2. `WifiTransport.send()` fixen (`@ip`-Format)
3. Coroutine-Leaks schließen (`rememberCoroutineScope`)
4. Krypto korrigieren oder ausbauen (keine "Sicherheit" vorspielen)

**Schnelle Erfolge:**
- `Crisix/`-Duplikat löschen
- Tote Dateien entfernen
- `println()` durch `Log` ersetzen
