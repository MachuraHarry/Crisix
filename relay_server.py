#!/usr/bin/env python3
"""
Crisix Relay Server
===================
Ein zentraler TCP-Server, der Nachrichten zwischen Crisix-Geräten weiterleitet.
Löst das NAT-Problem: Beide Geräte verbinden sich aktiv zum Server,
der Server relayt die Nachrichten zwischen ihnen.

Verwendung:
  python3 relay_server.py [--port PORT] [--host HOST]

Standard:
  Host: 0.0.0.0 (alle Interfaces)
  Port: 54232

Beispiel:
  python3 relay_server.py --port 54232
  # Verbinde von der App mit: 192.168.178.32:54232
"""

import argparse
import json
import socket
import threading
import time
import sys
from datetime import datetime

# Globale Registrierung: device_id -> (socket, device_name, address)
clients = {}
clients_lock = threading.Lock()


def timestamp():
    """Gibt einen Zeitstempel für Log-Nachrichten zurück."""
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def debug(msg, prefix="[DEBUG]"):
    """Einheitliches Debug-Logging mit Zeitstempel."""
    print(f"{prefix} {timestamp()} {msg}", flush=True)


def handle_client(conn, addr):
    """Behandelt eine einzelne Client-Verbindung."""
    device_id = None
    device_name = None

    try:
        conn.settimeout(60)  # 60 Sekunden Timeout

        # Auf Registrierung warten
        data = receive_message(conn)
        if data is None:
            debug(f"Keine Registrierung von {addr[0]} erhalten -> Verbindung geschlossen", "[-]")
            return

        msg = json.loads(data.decode('utf-8'))

        if msg.get("type") == "register":
            device_id = msg["deviceId"]
            device_name = msg.get("deviceName", "Unbekannt")

            with clients_lock:
                # Alte Verbindung desselben Geräts schließen
                if device_id in clients:
                    old_conn = clients[device_id][0]
                    debug(f"Alte Verbindung von {device_name} ({device_id}) wird geschlossen", "[!]")
                    try:
                        old_conn.close()
                    except:
                        pass

                clients[device_id] = (conn, device_name, addr[0])

            debug(f"{device_name} ({device_id[:8]}...) registriert von {addr[0]}", "[+]")
            debug(f"Aktive Clients: {len(clients)}", "[STATUS]")

            # Alle aktuell verbundenen Clients anzeigen
            with clients_lock:
                for cid, (_, cname, caddr) in clients.items():
                    debug(f"  Client: {cname} ({cid[:8]}...) @ {caddr}", "[STATUS]")

            # Bestätigung senden
            send_message(conn, {
                "type": "registered",
                "deviceId": device_id,
                "message": f"Registriert als {device_name}"
            })
            debug(f"Bestätigung an {device_name} gesendet", "[SEND]")

            # Liste der anderen verbundenen Clients senden
            other_clients = []
            with clients_lock:
                for cid, (_, cname, caddr) in clients.items():
                    if cid != device_id:
                        other_clients.append({
                            "deviceId": cid,
                            "deviceName": cname,
                            "address": caddr
                        })

            if other_clients:
                send_message(conn, {
                    "type": "peer_list",
                    "peers": other_clients
                })
                debug(f"Peer-Liste mit {len(other_clients)} Gerät(en) an {device_name} gesendet", "[SEND]")
                for p in other_clients:
                    debug(f"  -> {p['deviceName']} ({p['deviceId'][:8]}...) @ {p['address']}", "[SEND]")
            else:
                debug(f"Keine anderen Peers für {device_name} - ist der erste Client", "[INFO]")

            # Andere Clients über neuen Peer informieren
            with clients_lock:
                for cid, (c_conn, cname, _) in list(clients.items()):
                    if cid != device_id:
                        try:
                            send_message(c_conn, {
                                "type": "new_peer",
                                "deviceId": device_id,
                                "deviceName": device_name,
                                "address": addr[0]
                            })
                            debug(f"Neuer-Peer-Benachrichtigung an {cname} gesendet: {device_name}", "[SEND]")
                        except Exception as e:
                            debug(f"Fehler beim Benachrichtigen von {cname}: {e}", "[!]")

            # Hauptschleife: Nachrichten empfangen und weiterleiten
            debug(f"Warte auf Nachrichten von {device_name}...", "[INFO]")
            while True:
                data = receive_message(conn)
                if data is None:
                    debug(f"Verbindung zu {device_name} unterbrochen (keine Daten)", "[-]")
                    break

                msg = json.loads(data.decode('utf-8'))
                msg_type = msg.get("type", "unknown")

                if msg_type == "relay":
                    target_id = msg.get("targetDeviceId")
                    payload = msg.get("payload", {})

                    # Payload-Inhalt für Debug (gekürzt)
                    payload_preview = str(payload)[:100]
                    debug(f"RELAY-Nachricht von {device_name} ({device_id[:8]}...) an {target_id[:8]}...", "[RELAY]")
                    debug(f"  Payload: {payload_preview}", "[RELAY]")

                    with clients_lock:
                        if target_id in clients:
                            target_conn = clients[target_id][0]
                            target_name = clients[target_id][1]
                            try:
                                send_message(target_conn, {
                                    "type": "message",
                                    "fromDeviceId": device_id,
                                    "fromDeviceName": device_name,
                                    "payload": payload
                                })
                                debug(f"Nachricht weitergeleitet an {target_name} ({target_id[:8]}...)", "[RELAY->]")

                                # Bestätigung an Sender
                                send_message(conn, {
                                    "type": "relay_ack",
                                    "targetDeviceId": target_id,
                                    "status": "delivered"
                                })
                                debug(f"Bestätigung (delivered) an {device_name} gesendet", "[SEND]")
                            except Exception as e:
                                debug(f"Fehler beim Senden an {target_name}: {e}", "[!]")
                                send_message(conn, {
                                    "type": "relay_ack",
                                    "targetDeviceId": target_id,
                                    "status": "failed",
                                    "error": "Target disconnected"
                                })
                                debug(f"Bestätigung (failed: disconnected) an {device_name} gesendet", "[SEND]")
                        else:
                            debug(f"Ziel {target_id[:8]}... nicht gefunden! Verfügbare Clients:", "[!]")
                            with clients_lock:
                                for cid, (_, cname, caddr) in clients.items():
                                    debug(f"  {cname} ({cid[:8]}...) @ {caddr}", "[!]")
                            send_message(conn, {
                                "type": "relay_ack",
                                "targetDeviceId": target_id,
                                "status": "failed",
                                "error": "Target not found"
                            })
                            debug(f"Bestätigung (failed: not found) an {device_name} gesendet", "[SEND]")

                elif msg_type == "ping":
                    send_message(conn, {"type": "pong"})
                    debug(f"Ping/Pong von {device_name}", "[INFO]")

                else:
                    debug(f"Unbekannter Nachrichtentyp von {device_name}: {msg_type}", "[!]")

    except socket.timeout:
        debug(f"Timeout: {device_name or addr[0]}", "[-]")
    except (ConnectionResetError, BrokenPipeError, OSError) as e:
        debug(f"Verbindungsfehler bei {device_name or addr[0]}: {e}", "[-]")
    except Exception as e:
        debug(f"Fehler bei {device_name or addr[0]}: {e}", "[!]")
        import traceback
        traceback.print_exc()
    finally:
        if device_id:
            with clients_lock:
                if device_id in clients:
                    del clients[device_id]
            debug(f"{device_name or device_id or addr[0]} getrennt", "[-]")
            debug(f"Aktive Clients: {len(clients)}", "[STATUS]")

            # Andere über Trennung informieren
            with clients_lock:
                for cid, (c_conn, cname, _) in list(clients.items()):
                    try:
                        send_message(c_conn, {
                            "type": "peer_left",
                            "deviceId": device_id
                        })
                        debug(f"Trennungs-Benachrichtigung an {cname} gesendet", "[SEND]")
                    except:
                        pass

        try:
            conn.close()
        except:
            pass


def receive_message(conn):
    """Empfängt eine Nachricht im Längen-Präfix-Format."""
    try:
        length_data = b""
        while True:
            ch = conn.recv(1)
            if not ch:
                return None
            if ch == b'\n':
                break
            length_data += ch

        length = int(length_data.decode('utf-8').strip())
        data = b""
        while len(data) < length:
            chunk = conn.recv(length - len(data))
            if not chunk:
                return None
            data += chunk
        return data
    except:
        return None


def send_message(conn, msg_dict):
    """Sendet eine Nachricht im Längen-Präfix-Format."""
    data = json.dumps(msg_dict).encode('utf-8')
    conn.sendall(f"{len(data)}\n".encode('utf-8'))
    conn.sendall(data)


def print_status():
    """Zeigt regelmäßig den Status an."""
    while True:
        time.sleep(30)
        with clients_lock:
            if clients:
                debug(f"{len(clients)} aktive Client(s):", "[STATUS]")
                for cid, (_, cname, caddr) in clients.items():
                    debug(f"  - {cname} ({cid[:8]}...) @ {caddr}", "[STATUS]")
            else:
                debug("Keine aktiven Clients", "[STATUS]")


def main():
    parser = argparse.ArgumentParser(description="Crisix Relay Server")
    parser.add_argument("--port", type=int, default=54232, help="Port (Standard: 54232)")
    parser.add_argument("--host", default="0.0.0.0", help="Host (Standard: 0.0.0.0)")
    args = parser.parse_args()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server.bind((args.host, args.port))
        server.listen(10)
        server.settimeout(1.0)
    except OSError as e:
        print(f"❌ Port {args.port} bereits belegt: {e}")
        sys.exit(1)

    print(f"╔══════════════════════════════════════════╗")
    print(f"║       Crisix Relay Server gestartet      ║")
    print(f"╠══════════════════════════════════════════╣")
    print(f"║  Host: {args.host:<30} ║")
    print(f"║  Port: {args.port:<30} ║")

    # Alle IPs anzeigen
    import subprocess
    try:
        result = subprocess.run(["ip", "-4", "addr", "show"], capture_output=True, text=True)
        for line in result.stdout.split('\n'):
            if 'inet ' in line and '127.0.0' not in line:
                ip = line.strip().split()[1].split('/')[0]
                print(f"║  IP:   {ip:<30} ║")
                print(f"║  App:  {ip}:{args.port:<22} ║")
    except:
        pass

    print(f"╚══════════════════════════════════════════╝")
    print(f"Warte auf Verbindungen...")
    print(f"(Debug-Modus: Alle Nachrichten werden geloggt)")
    print()

    # Status-Thread
    status_thread = threading.Thread(target=print_status, daemon=True)
    status_thread.start()

    try:
        while True:
            try:
                conn, addr = server.accept()
                debug(f"Neue Verbindung von {addr[0]}:{addr[1]}", "[+]")
                thread = threading.Thread(target=handle_client, args=(conn, addr), daemon=True)
                thread.start()
            except socket.timeout:
                continue
    except KeyboardInterrupt:
        print("\n👋 Server wird beendet...")
    finally:
        server.close()
        with clients_lock:
            for conn, _, _ in clients.values():
                try:
                    conn.close()
                except:
                    pass
        print("✅ Server beendet")


if __name__ == "__main__":
    main()
