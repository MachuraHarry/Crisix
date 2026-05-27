# Crisix — Summary & Plan

## Goal
Einheitliche Geräte-ID für alle Crisix-Transporte (WifiTransport, InternetTransport, DnsTunnelTransport), damit Peers korrekt gefunden und zugeordnet werden können – und Protokoll-Leaks (JSON-Handshake-Nachrichten) im Chat beheben.

## Constraints & Preferences
- Ed25519 keypair als Single Source of Truth für die Geräteidentität
- Der Fingerprint (SHA-256 des Public Keys) wird von allen Transporten als `deviceId` / `localPeerId` verwendet
- Keine `pending-id-*` Platzhalter mehr
- Build muss erfolgreich sein (Kotlin + Android)

## Progress
### Done
- Analyse der Logs: Drei inkonsistente Identitätssysteme entdeckt (WifiTransport: UUID, InternetTransport: Ed25519-Fingerprint, QR-Code: Ed25519-Fingerprint)
- Fix in `CrisixApp.kt` implementiert: Ed25519-Schlüsselpaar wird **vor** allen Transporten geladen/generiert (synchron in `remember`) und der Fingerprint als einheitliche `deviceId` an WifiTransport & DnsTunnelTransport übergeben
- `pending-id-*` Fallback entfernt
- Build erfolgreich (`./gradlew assembleDebug` → SUCCESSFUL)
- **Handshake-Leak-Fix**: In `WifiTransport.startDiscovery()` wird nach dem TCP-Verbindungsaufbau die Handshake-Antwort des Peers gelesen und **verworfen**, bevor `startClientListener` gestartet wird – so landen rohe JSON-Handshake-Daten (`deviceId`, `deviceName`, `port`) nicht mehr als Chatnachricht im UI

### In Progress
- (none)

### Blocked / Offen
- **Duplikat-TCP-Verbindungen**: Beide Geräte bauen gleichzeitig TCP-Verbindungen auf (UDP-Trigger), was zu zwei parallelen Verbindungen führt.

## Offene Fragen / Nächste Schritte
1. **Duplikat-TCP-Verbindungen**: Beide Geräte bauen gleichzeitig zwei parallele TCP-Verbindungen auf (UDP-Trigger bidirectional). Besser: nur eine Seite connecten lassen.

## Critical Context
- **Letzter Log (PID 19051)**: WifiTransport findet Peer `0bee0515` per UDP, TCP-Handshake gelingt. QR-Scan liefert `key=0bee0515d96de078...` (Fingerprint). DHT-Suche schlägt 3x fehl (erwartet). Fallback auf direkte IP-Verbindung (`192.168.178.45:34389`) → Libp2pManager verbindet, aber `SocketTimeoutException` – Stream wird geschlossen.
- **`0bee0515`** ist 8-Zeichen-Präfix des Fingerprints – Identity-Fix wirkt korrekt.
- **Build-Befehl**: `./gradlew assembleDebug` (Gradle 9.4.1, Android-Gradle-Plugin)

## Key Decisions
- Ed25519 keypair wird in `crisix_identity` SharedPreferences persistiert (gleicher Speicherort wie `InternetTransport.start()`)
- Fingerprint wird bereits in `remember` bestimmt, bevor die Jetpack-Compose-Navigation startet
- Handshake-Antwort in `startDiscovery()` wird synchron (5s Timeout) gelesen & verworfen → kein Protokoll-Leak mehr

## Relevant Files
- `app/src/main/java/com/messenger/crisix/ui/navigation/CrisixApp.kt` — Einstiegs-Composable, lädt Ed25519-Keypair, erzeugt alle Transporte mit einheitlicher `deviceId`
- `app/src/main/java/com/messenger/crisix/transport/WifiTransport.kt` — TCP/UDP-P2P-Transport, Handshake-Protokoll; **Fix in `startDiscovery()`** (Zeile 718–738)
- `app/src/main/java/com/messenger/crisix/transport/internet/Libp2pManager.kt` — `readFully()` (Zeile 404) – Quelle der SocketTimeoutException
- `app/src/main/java/com/messenger/crisix/transport/internet/InternetTransport.kt` — Verbindungsaufbau über Libp2p, `connectToPeer()` (Zeile 658)
- `Crisix-Plan.md` — Architekturplan (nicht im Build)
