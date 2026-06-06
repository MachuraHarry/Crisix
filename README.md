# Crisix – Dezentraler Krisen-Messenger

[![CI](https://github.com/MachuraHarry/Crisix/actions/workflows/ci.yml/badge.svg)](https://github.com/MachuraHarry/Crisix/actions/workflows/ci.yml)
[![MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![v1.5](https://img.shields.io/badge/version-1.5-green)](https://github.com/MachuraHarry/Crisix/releases/tag/v1.5)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1-purple)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-16+-3ddc84)](https://developer.android.com/about/versions/16)
[![PRs](https://img.shields.io/badge/PRs-welcome-brightgreen)](CONTRIBUTING.md)
[![GitHub issues](https://img.shields.io/github/issues/MachuraHarry/Crisix)](https://github.com/MachuraHarry/Crisix/issues)

**Crisix** ist ein dezentraler Android-Messenger für Krisensituationen. Wenn das Internet ausfällt, wechselt Crisix automatisch auf alternative Transportwege – Wi-Fi Direct, Bluetooth Low Energy (BLE), Relay, SMS, DNS-Tunnel oder LoRa – **ohne zentrale Server, ohne SIM-Karte, ohne Internet**.

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

Die App priorisiert Transportwege nach ihrer **Effizienz und Zuverlässigkeit**:
1. **Wi-Fi Direct** – Lokales P2P, hohe Bandbreite, Reichweite ~100m
2. **Internet (P2P)** – Dezentrales Netzwerk über das Internet (libp2p)
3. **Relay (Websocket)** – Fallback über einen Relay-Server (falls P2P blockiert)
4. **Bluetooth Low Energy (BLE)** – Energiesparend, Reichweite ~10-50m
5. **SMS** – Fallback über das Mobilfunknetz (kein Internet nötig)
6. **DNS-Tunnel** – Funktioniert hinter restriktiven Firewalls/Captive Portals
7. **LoRa** – Extrem hohe Reichweite (~10km), experimentell

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
│  │WiFi-Dir  │ Internet │Relay │ BLE    │ DNS-   │ SMS / │  │
│  │Transport │ P2P      │Trans │ Trans  │ Tunnel │ LoRa  │  │
│  └──────────┴──────────┴──────┴────────┴────────┴───────┘  │
├─────────────────────────────────────────────────────────────┤
│  P2P-Netzwerk (InternetTransport)                           │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Libp2pManager (TCP-Server, Ed25519, Streams)        │   │
│  │  PeerDiscovery (DHT + mDNS + NAT-Traversal)          │   │
│  │  MainlineDhtNode (Kademlia DHT, BEP 5)               │   │
│  └──────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│  Datenhaltung                                               │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Room Database (Messages, Chats, Pending Messages)   │   │
│  │  ContactRepository (SharedPreferences + JSON)        │   │
│  │  CryptoHelper (Ed25519, AES-GCM, Schlüsselpaare)    │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 🚇 Transport-Layer

### TransportManager

Der `TransportManager` ist das zentrale Steuerungsmodul. Er wählt automatisch den besten verfügbaren Transport für jede Nachricht basierend auf einer Prioritätsliste und dem Verbindungsstatus der Peers.

**Transport-Auswahlstrategie (Priorität):**
1. **Wi-Fi Direct** (Höchste Priorität für lokale P2P-Kommunikation)
2. **Internet (P2P)** (Fallback für globale Kommunikation via DHT)
3. **Relay** (WebSocket-Tunneling)
4. **Bluetooth Low Energy**
5. **SMS**
6. **DNS-Tunnel**
7. **LoRa**

### WifiTransport (Lokales P2P)

- Nutzt **TCP-Sockets** für direkte Verbindungen im lokalen Netzwerk (WLAN/LAN).
- Aktuell optimiert für manuelle Verbindungen via **QR-Code** oder IP-Eingabe.
- Port: 54230

### InternetTransport (P2P)

- Nutzt **libp2p**-inspirierte TCP-Streams über das Internet.
- **Kademlia DHT** (Mainline BEP 5) zur Peer-Suche ohne zentrale Server.
- **STUN** für NAT-Traversal und IP-Erkennung.

### RelayTransport (Fallback)

- Nutzt **WebSockets** (`wss://crisix-dns.onrender.com/ws`) als Fallback.
- Ermöglicht Kommunikation, wenn direkte P2P-Verbindungen durch Firewalls oder symmetrische NATs blockiert sind.

### BleTransport (Bluetooth Low Energy)

- Nutzt **GATT-Server/Client** zur Kommunikation zwischen Android-Geräten.
- Unterstützung für **Chunking** großer Nachrichten (z.B. Bilder).
- Eigener Identitäts-Austausch via BLE-Characteristics.

### DnsTunnelTransport

- Sendet Daten kodiert in **DNS-Anfragen** an `crisix-dns.onrender.com`.
- Nutzt **Gzip-Kompression** und **JSON-Key-Minifizierung** zur Maximierung der Payload pro Query.
- Unterstützt **Chunking** und Reassembly für Nachrichten, die das DNS-Limit überschreiten.

---

## 🌍 Peer-to-Peer-Netzwerk

### Identität

Jeder Peer hat ein **Ed25519-Schlüsselpaar**:
- **Privater Schlüssel**: Wird sicher im Android KeyStore oder verschlüsselten SharedPreferences gespeichert.
- **Peer-ID**: Ein Fingerprint des öffentlichen Schlüssels.

### PeerDiscovery (Internet)

Kombiniert mehrere Verfahren:
1. **Mainline DHT**: Globales Finden von Peers via Kademlia (BEP 5).
2. **mDNS**: Lokale Peer-Erkennung im selben WLAN.
3. **QR-Codes**: Manueller Austausch von Peer-IDs und Verbindungsdaten.

---

## 🔐 Kryptografie & Sicherheit

### Binäres Protokoll (CrisixProtocol)

Das Nachrichtenformat verwendet ein effizientes binäres TLV-Format (Type-Length-Value):
- **Magic Number**: `0x43524958` ("CRIX")
- **Versioning**: Protokollversionierung (Byte).
- **Struktur**: `[Feld-Typ: 1 Byte] [Länge: 4 Bytes] [Wert: variable Länge]`
- **Inhalt**: Enthält MessageId, Sender, Recipient, Type, Payload, Timestamp und Nonce.

---

## 📁 Projektstruktur

```
app/src/main/java/com/messenger/crisix/
├── MainActivity.kt                    # Einstiegspunkt
│
├── data/                              # Datenhaltung
│   ├── AppDatabase.kt                 # Room-Datenbank Definition
│   ├── Contact.kt / ContactRepository.kt
│   ├── ChatEntity.kt / ChatDao.kt
│   ├── MessageEntity.kt / MessageDao.kt
│   ├── MessageRepository.kt           # Zentrales Message-Management
│   └── PendingMessageEntity.kt        # Retry-Queue Persistierung
│
├── transport/                         # Transport-Layer
│   ├── Transport.kt / TransportManager.kt
│   ├── WifiTransport.kt               # WLAN P2P (TCP)
│   ├── BleTransport.kt                # Bluetooth Low Energy (GATT)
│   ├── RelayTransport.kt              # WebSocket Relay
│   ├── DnsTunnelTransport.kt          # DNS Tunneling
│   │
│   └── internet/                      # Internet P2P (DHT)
│       ├── InternetTransport.kt       # P2P-Steuerung
│       ├── Libp2pManager.kt           # TCP-Server & Streams
│       ├── MainlineDhtNode.kt         # Kademlia DHT Implementierung
│       ├── PeerDiscovery.kt           # DHT + mDNS Integration
│       └── CrisixProtocol.kt          # Binäres Nachrichtenformat
│
└── ui/                                # Benutzeroberfläche
    ├── navigation/
    │   └── CrisixApp.kt               # Haupt-Navigation & Session-Logic
    └── screens/
        ├── ChatListScreen.kt          # Chat-Übersicht
        ├── ChatDetailScreen.kt        # Chat-Ansicht (Nachrichten-Liste)
        ├── ContactListScreen.kt       # Kontaktliste
        ├── MyIdScreen.kt              # Eigene ID & QR-Code
        ├── QrCodeScannerScreen.kt     # CameraX QR-Scanner
        ├── ConnectionsScreen.kt       # Verbindungs- & Transportstatus
        ├── SettingsScreen.kt          # App-Einstellungen
        └── LogViewerScreen.kt         # In-App Debug-Logs
```

---

## 🛠 Entwicklung & Build

### Voraussetzungen

- **Android Studio** Ladybug (2024.2.1+)
- **JDK 17+**
- **Android SDK 36** (Android 16)
- **Kotlin 2.0+**

### Wichtige Abhängigkeiten (Auszug)

```kotlin
// build.gradle.kts (app)
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.paging:paging-runtime:3.3.6")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.77") // Ed25519 Crypto
    implementation("org.msgpack:msgpack-core:0.9.8")        // Serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")    // WebSocket Relay
    implementation("com.jakewharton.timber:timber:5.0.1")    // Logging
    implementation("io.coil-kt:coil-compose:2.7.0")         // Image Loading
}
```

---

## 🌐 DNS-Tunnel-Server

Der DNS-Tunnel-Server (`dns-tunnel-server/`) läuft auf Render.com und bietet:
1. **DNS-Endpoint**: Verarbeitet Tunnel-Queries (UDP/8053).
2. **HTTP-API**: Fallback für DNS-over-HTTPS ähnliche Anfragen.
3. **WebSocket-Relay**: Zentrale Vermittlung für den `RelayTransport`.

---

## 🔍 Fehlerbehebung

### Häufige Probleme

#### "Keine Verbindung zum Relay"
**Ursache:** Internetverbindung fehlt oder Websocket-Port wird blockiert.
**Lösung:** Prüfen, ob `https://crisix-dns.onrender.com/health` im Browser erreichbar ist.

#### "BLE Peer nicht gefunden"
**Ursache:** Standortberechtigung fehlt oder Bluetooth ist deaktiviert.
**Lösung:** Sicherstellen, dass "Geräte in der Nähe" und Standort-Berechtigungen erteilt sind.

---

## 📄 Lizenz

MIT License – siehe [LICENSE](LICENSE).
