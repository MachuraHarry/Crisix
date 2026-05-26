# Fix-Plan: Emulator ↔ Pixel 9 Kommunikation

## Probleme (aus dem Log)
1. **EADDRINUSE**: DhtNode (Port 54231) kollidiert mit WifiTransport (discoveryPort=54231)
2. **Keine Bootstrap-Nodes**: DHT kann keine Peers finden (leere Liste)
3. **RelayTransport verbindet sich zu lokaler IP (192.168.178.32)**, nicht zum Pixel 9
4. **WifiTransport scannt nur 10.0.2.x** (Emulator-Netzwerk) - findet Pixel 9 nicht
5. **Kein Mechanismus für Internet-Kommunikation** zwischen Emulator und Pixel 9

## Lösung: Relay-Server als Brücke
- Der Relay-Server läuft auf dem **Pixel 9** (nicht auf 192.168.178.32)
- Beide Geräte verbinden sich aktiv zum Relay-Server
- Der Server leitet Nachrichten zwischen ihnen weiter
- Funktioniert auch über das Internet (nicht nur lokales Netzwerk)
