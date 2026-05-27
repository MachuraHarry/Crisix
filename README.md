# Crisix – Dezentraler Krisen-Messenger

**Crisix** ist ein dezentraler Android-Messenger für Krisensituationen. Wenn das Internet ausfällt, wechselt Crisix automatisch auf alternative Transportwege – Bluetooth, Wi-Fi Direct, SMS, DNS-Tunnel oder LoRa – **ohne zentrale Server, ohne SIM-Karte, ohne Internet**.

---

## 📋 Inhaltsverzeichnis

- [Philosophie](#-philosophie)
- [Architektur](#-architektur)
- [Transport-Layer](#-transport-layer)
- [Peer-to-Peer-Netzwerk](#-peer-to-peer-netzwerk)
- [Kryptografie & Sicherheit](#-kryptografie--sicherheit)
- [Projektstruktur](#-projektstruktur)
- [Entwicklung & Build](#-entwicklung--build)
- [DNS-Tunnel-Server](#-dns-tunnel-server)
- [Fehlerbehebung](#-fehlerbehebung)

---

## 🧠 Philosophie

Crisix folgt dem **Offline-First-Prinzip**:

> **"Erst das lokale Netzwerk, dann das Internet, dann kreative Wege."**

Die App priorisiert Transportwege nach ihrer **Unabhängigkeit**:
1. **Bluetooth** – Keine Infrastruktur nötig, Reichweite ~10m
2. **Wi-Fi Direct** – Kein Router nötig, Reichweite ~100m
3. **SMS** – Kein Internet nötig, aber Mobilfunk nötig
4. **Lokales WLAN** – Router nötig, aber kein Internet
5. **Internet (P2P)** – Internet nötig, aber kein Server
6. **DNS-Tunnel** – Internet nötig, funktioniert hinter restriktiven Firewalls
7. **LoRa** – Keine Infrastruktur nötig, Reichweite ~10km (experimentell)

---

## 🏗 Architektur

```
┌─────────────────────────────────────────────────────────────┐
│                      Crisix App                             │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Jetpack Compose)                                 │
│  ┌───────────┬───────────┬───────────┬───────────────────┐  │
│  │ ChatList  │ Contacts  │ QR-Scanner│ ConnectionsScreen │  │
│  └───────────┴───────────┴───────────┴───────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│  TransportManager (Automatische Transportwahl)              │
│  ┌──────────┬──────────┬──────┬────────┬────────┬───────┐  │
│  │Bluetooth │ WiFi-Dir │ SMS  │ WLAN   │Internet│ DNS-  │  │
│  │Transport │ Transport│ Trans│ Trans  │  P2P   │Tunnel │  │
│  └──────────┴──────────┴──────┴────────┴────────┴───────┘  │
├─────────────────────────────────────────────────────────────┤
│  P2P-Netzwerk (InternetTransport)                           │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Libp2pManager (TCP-Server, Ed25519, Streams)        │   │
│  │  PeerDiscovery (DHT + mDNS + NAT-Traversal)          │   │
│  │  MainlineDhtNode (Kademlia DHT, BEP 5)               │   │
│  │  NatTraversal (STUN, Hole Punching)                  │   │
│  └──────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│  Datenhaltung                                               │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  ContactRepository (verschlüsselte Kontaktliste)     │   │
│  │  CryptoHelper (Ed25519, AES-GCM, Schlüsselpaare)    │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 🚇 Transport-Layer

### Transport-Interface

Jeder Transport implementiert das `Transport`-Interface:

```kotlin
interface Transport {
    val type: TransportType
    val capabilities: TransportCapabilities
    
    suspend fun isAvailable(): Boolean
    suspend fun start()
    suspend fun stop()
    suspend fun send(peerId: String, data: ByteArray): Result<Unit>
    fun registerListener(listener: (String, ByteArray) -> Unit)
    fun discoverPeers(): Flow<Peer>
    fun getStatusDetail(): Pair<Int, String>
}
```

### TransportManager

Der `TransportManager` ist das zentrale Steuerungsmodul. Er:
- Startet alle verfügbaren Transporte parallel
- Wählt automatisch den besten Transport für jede Nachricht
- Überwacht die Transportverfügbarkeit (ConnectivityManager + eigene Prüfung)
- Bietet einen **manuellen Override** für erzwungene Transportwahl

**Transport-Auswahlstrategie:**
1. Prüfe, ob der gewünschte Transport verfügbar ist
2. Falls nicht, wähle den nächstbesten verfügbaren Transport
3. Priorität: Bluetooth > Wi-Fi Direct > SMS > WLAN > Internet > DNS-Tunnel > LoRa

### WifiTransport (Lokales WLAN)

- Nutzt **TCP-Sockets** im lokalen Netzwerk
- **mDNS** (Multicast DNS) zur automatischen Peer-Erkennung
- **UDP-Broadcast** als Fallback, wenn mDNS blockiert ist
- **Netzwerkscan** über das Subnetz (z.B. `192.168.178.1-255`)
- Port: Dynamisch (OS-Vergabe), Port 54230 als Standard

**Protokoll:** TCP mit Längenpräfix (4 Bytes Länge + Daten)

### InternetTransport (P2P über Internet)

Siehe [Peer-to-Peer-Netzwerk](#-peer-to-peer-netzwerk).

### BluetoothTransport

- Nutzt Android Bluetooth Sockets (RFCOMM)
- Automatische Kopplung über Bluetooth-Discovery
- Verschlüsselung auf App-Ebene (nicht nur Bluetooth-eigen)

### SmsTransport

- Nutzt Android `SmsManager` API
- Nachrichten werden Base64-kodiert und in SMS aufgeteilt
- Maximal 160 Zeichen pro SMS-Teil (GSM 7-Bit)
- Automatische Fragmentierung und Reassemblierung

### DnsTunnelTransport

- **DNS-Tunnel** für Umgebungen, die nur DNS-Verkehr erlauben (Captive Portals, Firewalls)
- Kodiert Nachrichten als DNS-Anfragen an `crisix-dns.onrender.com`
- Der Server dekodiert die Anfragen und leitet sie weiter
- Funktioniert auch hinter strikten Firewalls

### LoRaTransport (Experimentell)

- Nutzt Android `HardwarePropertiesManager` für LoRa-Hardware (falls vorhanden)
- Extrem niedrige Bandbreite (~300 Bit/s)
- Nur für Textnachrichten geeignet

---

## 🌍 Peer-to-Peer-Netzwerk

Das Herzstück von Crisix ist das **serverlose P2P-Netzwerk** über das Internet.

### Identität

Jeder Peer hat ein **Ed25519-Schlüsselpaar**:
- **Privater Schlüssel** (64 Bytes): Wird lokal gespeichert (SharedPreferences)
- **Öffentlicher Schlüssel** (32 Bytes): Dient als Identität
- **Peer-ID / Fingerprint**: SHA-256 des öffentlichen Schlüssels (64 Hex-Zeichen)

```kotlin
// Generierung
val keyPair = CryptoHelper.generateKeyPair()
val fingerprint = CryptoHelper.publicKeyToFingerprint(keyPair.publicKey)
// → "abdfd19333dba2f05d29de7afc16819ada3bec6130134b86270726e3387af710"
```

### Libp2pManager (TCP-Layer)

Der `Libp2pManager` ist ein Singleton, der:
- Einen **TCP-Server** auf einem dynamischen Port startet
- **Eingehende Verbindungen** akzeptiert und den Handshake durchführt
- **Ausgehende Verbindungen** zu Peers herstellt
- **Streams** für die bidirektionale Kommunikation verwaltet

**Handshake-Protokoll:**
1. Beide Seiten senden SOFORT ihre Peer-ID (2 Bytes Länge + UTF-8 String)
2. Gleichzeitiges Senden vermeidet Race Conditions
3. Nach dem Handshake: Nachrichtenaustausch mit Längenpräfix (4 Bytes + Daten)

### PeerDiscovery

Kombiniert drei Verfahren:

#### 1. Kademlia DHT (Global – Mainline DHT / BEP 5)

- **Eigener DHT-Knoten** auf Port 6881 (wie BitTorrent)
- **Bootstrap** über öffentliche DHT-Seeds: `router.bittorrent.com`, `dht.transmissionbt.com`, `router.utorrent.com`
- **Globales Topic** (`DhtConfig.GLOBAL_TOPIC`): Alle Crisix-Geräte registrieren sich im selben Topic
- **Announce**: Peer-ID + IP/Port werden im Topic gespeichert
- **Lookup**: Peers können über ihre Peer-ID gefunden werden

```kotlin
// Registrierung in der DHT
dhtNode.announce(
    topicBytes = DhtConfig.GLOBAL_TOPIC,
    peerId = localPeerId,
    publicHost = publicIp,  // via STUN ermittelt
    publicPort = publicPort
)

// Peer-Suche
val peerInfo = dhtNode.findPeer(targetPeerId)
```

#### 2. mDNS (Lokal)

- Multicast DNS für Geräte im selben lokalen Netzwerk
- Dienstname: `_crisix._tcp.local`
- Keine Konfiguration erforderlich
- Funktioniert auch ohne Internet

#### 3. NAT-Traversal

- **STUN**: Ermittelt die öffentliche IP/Port
- **UDP Hole Punching**: Öffnet Löcher in NATs für direkte Verbindungen
- Fallback: Wenn Hole Punching fehlschlägt, wird die Verbindung über die DHT vermittelt

### MainlineDhtNode

Implementiert einen **Kademlia DHT-Knoten** nach BEP 5 (BitTorrent-Protokoll):
- **Routing-Tabelle**: Verwaltet bekannte Knoten (max. 200)
- **Find Node**: Sucht nach Knoten in der DHT
- **Announce Peer**: Registriert einen Peer in einem Topic
- **Find Peer**: Sucht nach Peers in einem Topic
- **Node-ID**: SHA-1 der IP (wie BitTorrent)

---

## 🔐 Kryptografie & Sicherheit

### Schlüsselverwaltung

- **Ed25519** für digitale Signaturen und Identität
- Schlüsselpaar wird beim ersten Start generiert
- Persistenz in `SharedPreferences` (Base64-kodiert)
- **Einheitliche ID**: Die Peer-ID ist IMMER der Fingerprint des Public Keys

### Nachrichtenverschlüsselung

- **AES-256-GCM** für Nachrichteninhalte
- **Ephemeral Key Exchange** (X25519) für jede Sitzung
- **Perfect Forward Secrecy** durch temporäre Sitzungsschlüssel

### Protokoll

```kotlin
data class CrisixMessage(
    val messageId: String,      // UUID
    val senderId: String,       // Fingerprint des Senders
    val recipientId: String,    // Fingerprint des Empfängers
    val type: MessageType,      // CHAT_MESSAGE, ACK, FILE, etc.
    val payload: ByteArray,     // Verschlüsselter Inhalt
    val timestamp: Long         // Unix-Zeitstempel
)
```

---

## 📁 Projektstruktur

```
app/src/main/java/com/messenger/crisix/
├── MainActivity.kt                    # Einstiegspunkt, EdgeToEdge, Kamera-Berechtigung
├── CrisixApp.kt                       # (in ui/navigation/) Haupt-App-Komponente
│
├── data/
│   ├── Contact.kt                     # Datenklasse für Kontakte
│   └── ContactRepository.kt           # Persistenz (SharedPreferences + JSON)
│
├── transport/
│   ├── Transport.kt                   # Transport-Interface
│   ├── TransportManager.kt            # Zentrale Transportsteuerung
│   ├── TransportType.kt               # Enum: BLUETOOTH, WIFI_DIRECT, SMS, WIFI, INTERNET, DNS_TUNNEL, LORA
│   ├── TransportCapabilities.kt       # Fähigkeiten eines Transports
│   ├── Peer.kt                        # Datenklasse für Peers
│   │
│   ├── WifiTransport.kt               # Lokaler WLAN-Transport (TCP + mDNS)
│   ├── BluetoothTransport.kt          # Bluetooth-Transport (RFCOMM)
│   ├── SmsTransport.kt                # SMS-Transport
│   ├── DnsTunnelTransport.kt          # DNS-Tunnel-Transport
│   ├── LoRaTransport.kt               # LoRa-Transport (experimentell)
│   │
│   └── internet/
│       ├── InternetTransport.kt       # P2P-Transport über Internet
│       ├── Libp2pManager.kt           # TCP-Server, Streams, Handshake
│       ├── PeerDiscovery.kt           # DHT + mDNS + NAT-Traversal
│       ├── MainlineDhtNode.kt         # Kademlia DHT (BEP 5)
│       ├── MainlineDhtClient.kt       # DHT-Client für Lookups
│       ├── DhtNode.kt                 # Abstrakter DHT-Knoten
│       ├── DhtConfig.kt              # DHT-Konfiguration (Topics, Ports)
│       ├── BootstrapNodes.kt          # Öffentliche DHT-Seeds
│       ├── NatTraversal.kt            # STUN + Hole Punching
│       ├── CryptoHelper.kt            # Ed25519, AES-GCM, Schlüssel
│       └── CrisixProtocol.kt          # Nachrichtenformat, Serialisierung
│
└── ui/
    ├── navigation/
    │   ├── NavRoutes.kt               # Routen-Definitionen
    │   └── CrisixApp.kt               # Navigation, QR-Verarbeitung
    │
    └── screens/
        ├── ChatListScreen.kt          # Chat-Übersicht
        ├── ContactListScreen.kt       # Kontaktliste
        ├── ContactDetailScreen.kt     # Kontaktdetails + Chat
        ├── MyIdScreen.kt              # Eigene ID (QR-Code anzeigen)
        ├── QrCodeScannerScreen.kt     # QR-Code-Scanner (CameraX + ML Kit)
        └── ConnectionsScreen.kt       # Verbindungsstatus
```

---

## 🛠 Entwicklung & Build

### Voraussetzungen

- **Android Studio** Ladybug (2024.2.1+) oder neuer
- **JDK 17+**
- **Android SDK** 36 (Android 16)
- **Kotlin** 2.0+
- **Gradle** 8.9+

### Build

```bash
# Debug-Build
./gradlew assembleDebug

# Release-Build
./gradlew assembleRelease

# APK installieren
./gradlew installDebug
```

### Wichtige Abhängigkeiten

```kotlin
// build.gradle.kts (app)
dependencies {
    // Jetpack Compose (UI)
    implementation("androidx.compose.ui:ui:1.7.0")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    
    // CameraX (QR-Scanner)
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-mlkit-vision:1.4.0")
    
    // ML Kit (Barcode-Erkennung)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    
    // Kotlinx Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

### Berechtigungen (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
```

---

## 🌐 DNS-Tunnel-Server

Der DNS-Tunnel-Server läuft auf **Render.com** und ist ein separater Dienst:

```
dns-tunnel-server/
├── dns_server.py        # Python-DNS-Server (Twisted)
├── Dockerfile           # Container-Build
├── render.yaml          # Render.com-Konfiguration
├── requirements.txt     # Python-Abhängigkeiten
└── RENDER_DEPLOY.md     # Deployment-Anleitung
```

**Funktionsweise:**
1. Client kodiert Nachricht als Subdomain: `[base64].crisix-dns.onrender.com`
2. DNS-Anfrage wird an den Server gesendet
3. Server dekodiert die Subdomain und extrahiert die Nachricht
4. Server leitet die Nachricht an den Ziel-Peer weiter (via P2P)
5. Antwort wird als DNS-Antwort (TXT-Record) zurückgesendet

---

## 🔍 Fehlerbehebung

### Häufige Probleme

#### "Peer nicht in der DHT gefunden"

**Ursache:** Der Peer hat sich noch nicht in der DHT registriert, oder die DHT-Suche war zu früh.

**Lösung:**
- Warte 30-60 Sekunden nach dem App-Start (DHT-Bootstrap dauert)
- Stelle sicher, dass Internetverbindung besteht
- Prüfe die Logs auf `✅ Mainline-DHT-Knoten gestartet`

#### "EHOSTUNREACH (No route to host)"

**Ursache:** Die Geräte sind in verschiedenen Subnetzen oder Firewalls blockieren den Port.

**Lösung:**
- Stelle sicher, dass beide Geräte im selben WLAN sind
- Prüfe, ob die WLAN-Client-Isolation im Router deaktiviert ist
- Verwende den QR-Code zum Austausch der IP/Port

#### mDNS-Scan fehlgeschlagen: EPERM

**Ursache:** Android beschränkt Multicast-Sockets ab Android 14.

**Lösung:**
- Der `WifiTransport` hat einen UDP-Broadcast-Fallback
- Verwende den QR-Code zur manuellen Verbindung
- Die DHT-Suche funktioniert trotzdem (via Internet)

#### "Timeout beim Lesen" (SocketTimeoutException)

**Ursache:** Der Peer hat die Verbindung geöffnet, aber keine Daten gesendet.

**Lösung:**
- Prüfe, ob beide Geräte die gleiche Protokollversion verwenden
- Der `readFully()`-Timeout beträgt 5 Sekunden
- Bei wiederholten Timeouts: App neustarten

### Debugging

```bash
# Logs filtern
adb logcat -s InternetTransport
adb logcat -s Libp2pManager
adb logcat -s PeerDiscovery
adb logcat -s MainlineDhtNode
adb logcat -s WifiTransport
adb logcat -s TransportManager

# Alle Crisix-Logs
adb logcat | grep "com.messenger.crisix"
```

---

## 📄 Lizenz

MIT License – siehe [LICENSE](LICENSE).

---

## 🙏 Danksagung

- **libp2p** – Inspiration für das P2P-Protokoll
- **Mainline DHT** – Dezentrale Peer-Findung (BEP 5)
- **BitTorrent** – Bootstrap-Knoten für die DHT
- **Jetpack Compose** – Modernes Android-UI-Toolkit
- **CameraX + ML Kit** – QR-Code-Scanner
