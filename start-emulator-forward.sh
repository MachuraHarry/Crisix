#!/bin/bash
# ⚠️ OBSOLET - WIRD NICHT MEHR BENÖTIGT ⚠️
#
# Wir nutzen jetzt den Relay-Server (relay_server.py) für die Kommunikation.
# Der Relay-Server läuft auf 0.0.0.0:54232 und ist von überall erreichbar.
# Kein ADB-Forwarding oder socat mehr nötig!
#
# Stattdessen:
#   1. python3 relay_server.py
#   2. App auf Emulator + Pixel 9 installieren
#   3. Beide verbinden sich automatisch zum Relay-Server
#
# Siehe: relay_server.py

echo "❌ Dieses Script ist obsolet!"
echo ""
echo "Nutze stattdessen:"
echo "  1. python3 relay_server.py"
echo "  2. App auf allen Geräten installieren"
echo "  3. Fertig!"
exit 1
