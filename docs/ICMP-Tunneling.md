# ICMP Tunneling in Crisix – Implementierungsplan

## 1. Zusammenfassung

ICMP Tunneling bettet Nutzdaten in ICMP-Echo-Pakete (Ping) ein und dient als **letzter Fallback-Transport**, wenn TCP (InternetTransport), WebSocket (RelayTransport) und DNS-Tunnel (DnsTunnelTransport) alle gesperrt oder nicht verfügbar sind.

Viele restriktive Netzwerke (Captive Portals, Hotel-WiFi, Firmen-Firewalls) blockieren ausgehende TCP-Verbindungen, erlauben aber ICMP (Ping) – das Protokoll ist für Netzwerkdiagnose essenziell und wird selten gefiltert.

**Ziel:** Null-Tasten-Backup-Kanal für kurze Textnachrichten (~140 Zeichen) mit < 1 kbit/s Durchsatz.

---

## 2. Technische Herausforderungen auf Android

### 2.1 Problem: Kein Raw Socket ohne Root

Normale Android-Apps haben keine `CAP_NET_RAW`-Berechtigung. `Socket(RAW)` und `DatagramSocket` mit IPPROTO_ICMP werfen `SecurityException`.

### 2.2 Lösung: VpnService-basierter ICMP-Tunnel

Die `android.net.VpnService` API (ab API 14) stellt einen **TUN-Adapter** bereit – ein virtuelles Netzwerk-Interface auf OSI-Layer 3. Die App liest/schreibt rohe IP-Pakete über einen `ParcelFileDescriptor`.

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Crisix App  │────▶│  VpnService  │────▶│  TUN Device  │
│ IcpmTransport│◀────│  (Layer 3)   │◀────│  (virtuell)  │
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                  │ raw IP packets
                                                  ▼
                                          ┌──────────────┐
                                          │  Kernel TCP/ │
                                          │  IP Stack    │
                                          └──────┬───────┘
                                                  │
                                          real net interface
```

**Vorteile:**
- Kein Root erforderlich
- Volle Kontrolle über IP/ICMP-Header
- Funktioniert auf allen Android-Versionen ≥ 4.0

**Nachteile:**
- Benötigt Nutzer-Zustimmung (VPN-Berechtigungsdialog)
- Nur EINE VpnService kann gleichzeitig aktiv sein
- VPN-Symbol in der Statusleiste
- Die App muss ALLEN Traffic durch den TUN-Adapter routen (auch Nicht-ICMP)

---

## 3. Gesamtarchitektur

### 3.1 Position im Fallback-System

```
Prioritätskette (TransportManager.priorityOrder):
  0. WIFI_DIRECT      (TCP auf LAN)
  1. INTERNET         (TCP via DHT / Libp2pManager)
  2. RELAY            (WebSocket über crisix-dns.onrender.com)
  3. BLUETOOTH_MESH   (BLE GATT)
  4. SMS              (SMS)
  5. DNS_TUNNEL       (DNS-Tunnel)
  6. ICMP_TUNNEL      ← NEU: ICMP-Tunnel (letzter Fallback)
  7. LORA             (LoRa, falls Hardware)
```

ICMP kommt NACH DNS-Tunnel, weil:
- ICMP hat **deutlich geringere Bandbreite** (typisch 32–1472 Byte Payload pro Ping)
- VPN-Dialog ist **UX-Hürde** – Nutzer muss aktiv zustimmen
- **Batterieverbrauch** ist höher (TUN-Adapter prozessiert jedes IP-Paket)
- **Erkennungsrisiko** höher (IDS kann ICMP-Tunneling erkennen)

### 3.2 Komponenten

```
IcpmTransport.kt         ← Implementiert Transport-Interface
IcpmVpnService.kt        ← Android VpnService (TUN-Adapter)
IcpmPacketEncoder.kt     ← ICMP-Paket-Encoding/Decoding
IcpmTunReader.kt         ← Liest IP-Pakete vom TUN-Device
IcpmTunWriter.kt         ← Sendet IP-Pakete über TUN-Device
IcpmHandshake.kt         ← Session-Aushandlung über ICMP
IcpmFragmentation.kt     ← Chunking für >MTU-Nachrichten
IcpmPeerRegistry.kt      ← Peer-IP ⇔ Peer-ID Mapping
```

---

## 4. ICMP-Protokoll-Design

### 4.1 Paket-Header

Standard-ICMP-Echo-Request/Reply (Typ 8/0):

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|     Type      |     Code      |          Checksum             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           Identifier          |        Sequence Number        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                       Crisix Payload                          |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 4.2 Crisix-Payload im ICMP-Datenfeld

Jedes ICMP-Paket trägt ein 8-Byte Crisix-Protokoll-Präfix vor den Nutzdaten:

```
Byte 0-3:   Magic Number "CRIX" (0x43 0x52 0x49 0x58)
Byte 4:     Message Type
              0x01 = HANDSHAKE_SYN
              0x02 = HANDSHAKE_ACK
              0x03 = DATA_SINGLE       (vollständige Nachricht)
              0x04 = DATA_FRAGMENT     (Teil einer chunked Nachricht)
              0x05 = HEARTBEAT         (keep-alive)
              0x06 = DISCONNECT
              0x07 = ACK               (Empfangsbestätigung)
Byte 5:     Flags
              Bit 0: compressed (gzip)
              Bit 1: encrypted (E2EE – schon auf App-Layer)
              Bit 2-7: reserved
Byte 6-7:   Payload Length (Big-Endian uint16)
Byte 8+:    Payload (max 1464 Bytes bei Ethernet MTU 1500)
```

### 4.3 Handshake-Protokoll

Bevor Daten fließen, tauschen Peers eine Session aus:

```
Peer A                              Peer B
  │                                   │
  │── HANDSHAKE_SYN ────────────────▶│
  │   peerId=A, sessionNonce,        │
  │   capabilities, icmpSeqStart     │
  │                                   │
  │◀─────── HANDSHAKE_ACK ──────────│
  │   peerId=B, sessionNonce,        │
  │   accepted=true/false            │
  │                                   │
  │── HEARTBEAT ◀══════════════════▶│
  │   (alle 5 Sekunden)              │
  │                                   │
  │── DATA_SINGLE ─────────────────▶│
  │◀───── ACK ──────────────────────│
```

### 4.4 Fragmentierung

Maximale ICMP-Nutzlast: 1472 Bytes (1500 MTU – 20 IP-Header – 8 ICMP-Header).
Davon abzüglich 8 Byte Crisix-Präfix: **1464 Bytes verfügbar**.

Nachrichten > 1464 Bytes werden fragmentiert (wie DNS-Tunnel, mit `MessageFragmenter`):

```
Fragment-Header (6 Bytes):
  Byte 0-1:   Fragment-ID (uint16, Big-Endian)
  Byte 2-3:   Fragment-Index
  Byte 4-5:   Total Fragments
```

Das ergibt max. 1458 Bytes Payload pro Fragment.

### 4.5 Heartbeat / Keep-Alive

Da ICMP zustandslos ist, senden beide Seiten alle 5–10 Sekunden einen Heartbeat.
Bleibt er 30 Sekunden aus, gilt die Session als getrennt.

---

## 5. Implementierung

### 5.1 Datei: `IcpmVpnService.kt`

```kotlin
class IcpmVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.messenger.crisix.ICMP_START"
        const val ACTION_STOP  = "com.messenger.crisix.ICMP_STOP"
        const val EXTRA_PEER_IP = "peer_ip"
        var isRunning = false
            private set
        var tunnelFd: ParcelFileDescriptor? = null
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startVpn()
        } else if (intent?.action == ACTION_STOP) {
            stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        // TUN-Device erstellen (nur ICMP routen)
        val builder = Builder()
            .setSession("Crisix ICMP Tunnel")
            .addAddress("10.99.99.1", 32)     // virtuelles Interface
            .addRoute("0.0.0.0", 0)            // alle IPs routen
            .addAllowedApplication(packageName) // nur diese App
            .setMtu(1500)
            .setBlocking(true)

        tunnelFd = builder.establish()
        isRunning = true
        // Starte Reader/Writer in Coroutine
    }

    private fun stopVpn() {
        tunnelFd?.close()
        tunnelFd = null
        isRunning = false
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
```

**Wichtige Design-Entscheidungen:**
- `addAllowedApplication(packageName)`: Nur Crisix-Traffic geht durchs TUN. System-Traffic (Browser, andere Apps) bleibt unberührt – der VPN-Konflikt wird dadurch entschärft, und die Performance außerhalb von Crisix bleibt normal.
- `addRoute("0.0.0.0", 0)`: Fangt ALLE Crisix-IP-Pakete ab. Der Reader filtert dann im Code nur ICMP heraus und verwirft alles andere (DROP).
- Virtuelle Adresse `10.99.99.1/32`: Isoliert vom echten Netzwerk. Kein Routing-Konflikt.

### 5.2 Datei: `IcpmPacketEncoder.kt`

```kotlin
object IcpmPacketEncoder {

    private const val MAGIC: Int = 0x43524958  // "CRIX"

    enum class MessageType(val code: Byte) {
        HANDSHAKE_SYN(0x01),
        HANDSHAKE_ACK(0x02),
        DATA_SINGLE(0x03),
        DATA_FRAGMENT(0x04),
        HEARTBEAT(0x05),
        DISCONNECT(0x06),
        ACK(0x07)
    }

    data class CrisixIcmpPacket(
        val type: MessageType,
        val flags: Byte = 0,
        val payload: ByteArray = ByteArray(0)
    )

    fun encodeCrisixPayload(packet: CrisixIcmpPacket): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(intToBytes(MAGIC))
        out.write(packet.type.code.toInt())
        out.write(packet.flags.toInt())
        val len = packet.payload.size
        out.write((len shr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(packet.payload)
        return out.toByteArray()
    }

    fun decodeCrisixPayload(data: ByteArray, offset: Int, length: Int): CrisixIcmpPacket? {
        if (length < 8) return null
        val magic = bytesToInt(data, offset)
        if (magic != MAGIC) return null
        val msgType = MessageType.entries.firstOrNull { it.code == data[offset + 4] }
            ?: return null
        val flags = data[offset + 5]
        val payloadLen = ((data[offset + 6].toInt() and 0xFF) shl 8) or
                         (data[offset + 7].toInt() and 0xFF)
        if (offset + 8 + payloadLen > data.size) return null
        val payload = data.copyOfRange(offset + 8, offset + 8 + payloadLen)
        return CrisixIcmpPacket(msgType, flags, payload)
    }
}
```

### 5.3 IP/ICMP-Paket-Bau (Low-Level)

Im `IcpmTunWriter` werden rohe IP+ICMP-Pakete gebaut:

```kotlin
fun buildIcmpEchoPacket(
    destIp: InetAddress,
    identifier: Short,
    sequenceNumber: Short,
    payload: ByteArray
): ByteArray {
    val icmpData = buildIcmpHeader(8.toByte(), 0.toByte(), identifier, sequenceNumber) + payload
    return buildIpHeader(sourceIp, destIp, IPPROTO_ICMP) + icmpData
}

// IP-Header (20 Bytes ohne Optionen):
//   Version(4) | IHL(4) | DSCP(6) | ECN(2) | Total Length(16)
//   Identification(16) | Flags(3) | Fragment Offset(13)
//   TTL(8) | Protocol(8) = 1 (ICMP) | Header Checksum(16)
//   Source IP(32)
//   Destination IP(32)

// ICMP-Header (8 Bytes):
//   Type(8) = 8 (Echo Request) | Code(8) = 0
//   Checksum(16)
//   Identifier(16)
//   Sequence Number(16)
//   [Payload]
```

Die Checksum-Berechnung folgt RFC 1071 (Internet Checksum für IP-Header) und RFC 792 (ICMP-Checksum über ICMP-Header + Payload).

### 5.4 Datei: `IcpmTransport.kt`

```kotlin
class IcpmTransport(
    private val context: Context,
    private val localPeerId: String
) : Transport {

    override val type: TransportType = TransportType.ICMP_TUNNEL

    override val capabilities: TransportCapabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = 500,       // ICMP ist extrem langsam
        supportsImages = false,
        supportsVideo = false,
        supportsAudio = false,
        supportsFileTransfer = false,
        isMetered = false,
        maxPayloadSize = 1464,      // 1500 MTU - 20 IP - 8 ICMP - 8 Crisix
        requiresProbing = true
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var peerChannel = Channel<Peer>(Channel.UNLIMITED)
    private var tunReader: IcpmTunReader? = null
    private var tunWriter: IcpmTunWriter? = null
    private var handshakeManager: IcpmHandshake? = null
    private var isRunning = AtomicBoolean(false)
    private val listeners = mutableListOf<(String, ByteArray) -> Unit>()
    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())

    override suspend fun start() {
        if (isRunning.getAndSet(true)) return

        // 1. Prüfen, ob VpnService verfügbar ist
        val intent = Intent(context, IcpmVpnService::class.java)
        intent.action = IcpmVpnService.ACTION_START
        context.startService(intent)

        // 2. Warten, bis TUN-Device bereit ist (mit Timeout)
        withTimeout(5000) {
            while (IcpmVpnService.tunnelFd == null) {
                delay(100)
            }
        }

        // 3. Reader + Writer starten
        val fd = IcpmVpnService.tunnelFd ?: throw IllegalStateException("TUN fd is null")
        tunReader = IcpmTunReader(fd, ::onIncomingPacket)
        tunWriter = IcpmTunWriter(fd)
        handshakeManager = IcpmHandshake(localPeerId, tunWriter!!)

        scope.launch { tunReader!!.readLoop() }

        // 4. Discovery: Dieselbe DHT nutzen (über PeerDiscovery)
        //    ICMP-spezifische Info als DHT-Eintrag ablegen
        //    (z.B. lokale IP + Flag "icmp_available")
        startDiscovery()
    }

    override suspend fun stop() {
        isRunning.set(false)
        tunReader?.cancel()
        tunWriter = null
        context.stopService(Intent(context, IcpmVpnService::class.java))
    }

    override suspend fun isAvailable(): Boolean {
        return isRunning.get() && IcpmVpnService.isRunning
    }

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        val session = handshakeManager?.getSession(peerId)
            ?: return Result.failure(Exception("Keine ICMP-Session zu $peerId"))

        val packets = if (data.size <= 1464) {
            listOf(IcpmPacketEncoder.encodeCrisixPayload(
                IcpmPacketEncoder.CrisixIcmpPacket(
                    IcpmPacketEncoder.MessageType.DATA_SINGLE,
                    payload = data
                )
            ))
        } else {
            // Fragmentieren (wie MessageFragmenter, aber ICMP-spezifisch)
            IcpmFragmentation.fragment(data)
        }

        for (packet in packets) {
            tunWriter?.sendIcmpPacket(
                destIp = session.ipAddress,
                identifier = session.icmpId,
                sequenceNumber = session.nextSeq(),
                payload = packet
            )
        }
        return Result.success(Unit)
    }

    override fun registerListener(listener: (String, ByteArray) -> Unit) {
        listeners.add(listener)
    }

    override fun discoverPeers(): Flow<Peer> {
        return _discoveredPeers
    }

    override fun getStatusDetail(): Pair<Int, String> {
        val activeSessions = handshakeManager?.activeSessionCount() ?: 0
        return Pair(activeSessions, "$activeSessions ICMP-Sessions aktiv")
    }

    private fun onIncomingPacket(srcIp: InetAddress, icmpPayload: ByteArray) {
        val packet = IcpmPacketEncoder.decodeCrisixPayload(icmpPayload, 0, icmpPayload.size)
            ?: return

        when (packet.type) {
            IcpmPacketEncoder.MessageType.HANDSHAKE_SYN ->
                handshakeManager?.handleSyn(srcIp, packet.payload)
            IcpmPacketEncoder.MessageType.HANDSHAKE_ACK ->
                handshakeManager?.handleAck(srcIp, packet.payload)
            IcpmPacketEncoder.MessageType.DATA_SINGLE -> {
                val senderId = handshakeManager?.getPeerIdForIp(srcIp) ?: return
                listeners.forEach { it(senderId, packet.payload) }
                sendAck(srcIp, senderId)
            }
            IcpmPacketEncoder.MessageType.DATA_FRAGMENT ->
                IcpmFragmentation.handleFragment(srcIp, packet.payload)
            IcpmPacketEncoder.MessageType.HEARTBEAT -> {
                handshakeManager?.updateLastSeen(srcIp)
            }
            IcpmPacketEncoder.MessageType.ACK -> {
                // Bestätigung verarbeiten (Delivery-Update)
            }
            IcpmPacketEncoder.MessageType.DISCONNECT ->
                handshakeManager?.removeSession(srcIp)
        }
    }
}
```

### 5.5 Integration in TransportManager

**Neuer Enum-Wert in `Transport.kt`:**
```kotlin
enum class TransportType {
    RELAY, INTERNET, WIFI_DIRECT, BLUETOOTH_MESH,
    SMS, DNS_TUNNEL, ICMP_TUNNEL, LORA  // ICMP_TUNNEL hinzugefügt
}
```

**Neue Priorität in `TransportManager.kt`:**
```kotlin
private val priorityOrder = listOf(
    TransportType.WIFI_DIRECT,
    TransportType.INTERNET,
    TransportType.RELAY,
    TransportType.BLUETOOTH_MESH,
    TransportType.SMS,
    TransportType.DNS_TUNNEL,
    TransportType.ICMP_TUNNEL,     // ← NEU: vor LoRa
    TransportType.LORA
)
```

**Registrierung in `TransportInitializer.kt`:**
```kotlin
val icmpTransport = IcpmTransport(appContext, localPeerId)
transportManager.registerTransport(icmpTransport,
    onDeliveryAck = { messageId, peerId ->
        transportManager.emitDeliveryUpdate(
            DeliveryUpdate(messageId, peerId, MessageStatus.DELIVERED, TransportType.ICMP_TUNNEL)
        )
    }
)
```

**Mutual-Priority-Prüfung in `sendMessage()`:**
```kotlin
TransportType.ICMP_TUNNEL -> true  // ICMP ist immer bilateral nutzbar, wenn verfügbar
```

**Circuit Breaker:**
```kotlin
// In TransportManager:
TransportType.ICMP_TUNNEL -> CircuitBreakerConfig(
    failureThreshold = 5,       // 5 Fehlversuche
    successThreshold = 2,
    openTimeoutMs = 120_000     // 2 Minuten
)
```

### 5.6 Discovery über die bestehende DHT

Der ICMP-Transport nutzt die **bestehende DHT-Infrastruktur** (PeerDiscovery / MainlineDhtNode), um seine Verfügbarkeit zu signalisieren:

```kotlin
// In IcpmTransport.startDiscovery():
// Lege im DHT ab: "ICMP verfügbar für peerId X"
// Nutze Topic: SHA-1("icmp:" + localPeerId)

// Andere Peers können dann prüfen, ob ein Peer ICMP unterstützt:
// dhtNode.findPeersForTopic(SHA-1("icmp:" + targetPeerId))
```

Alternativ: ICMP-Flag in die `PeerCapabilities` aufnehmen und über den bestehenden BLE-Capability-Austausch verteilen.

**Capability-Erweiterung in `PeerCapabilities`:**
```kotlin
data class PeerCapabilities(
    ...
    val hasIcmp: Boolean = false    // ← NEU
)
```

---

## 6. AndroidManifest & Berechtigungen

```xml
<service
    android:name=".transport.icmp.IcpmVpnService"
    android:exported="false"
    android:permission="android.permission.BIND_VPN_SERVICE">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

Der Nutzer muss beim ersten Start des ICMP-Tunnels den VPN-Dialog bestätigen:

```
┌─────────────────────────────────────┐
│  Crisix möchte ein VPN einrichten   │
│                                     │
│  ⚠ Crisix kann den gesamten        │
│  Netzwerkverkehr überwachen.        │
│                                     │
│  Dies ermöglicht ICMP-Tunneling     │
│  als Fallback bei blockierten       │
│  Netzwerken.                        │
│                                     │
│      [Abbrechen]    [OK]            │
└─────────────────────────────────────┘
```

---

## 7. UX-Flow

```
1. Crisix versucht Nachricht über normale Wege (INTERNET → RELAY → ...)
2. Alle bevorzugten Transporte fehlgeschlagen
3. DNS_TUNNEL auch fehlgeschlagen
4. System prüft: ICMP_TUNNEL verfügbar? (VpnService berechtigt?)
   ├─ NEIN → Zeige Hinweis: "VPN-Berechtigung für ICMP-Fallback erforderlich"
   │          Button: "Jetzt aktivieren" → starte VpnService
   └─ JA   → Starte ICMP-Tunnel, sende Nachricht
              → Status: "Nachricht via ICMP-Tunnel gesendet (langsam)"
```

---

## 8. Fehlerbehandlung & Edge Cases

| Szenario | Behandlung |
|----------|-----------|
| VPN bereits durch andere App belegt | `establish()` schlägt fehl → ICMP nicht verfügbar |
| Nutzer lehnt VPN-Dialog ab | Speichere Präferenz; ICMP dauerhaft deaktiviert |
| ICMP von Firewall geblockt (keine Reply) | Heartbeat-Time-out → Session als tot markieren |
| TUN-Device disconnected (System-Neustart) | Auto-Restart mit Exponential Backoff |
| Battery Saver / Doze-Mode | `VpnService` läuft als Foreground-Service mit Notification |
| ICMP-Rate-Limiting durch Router | Dynamische Ratenanpassung (AIMD: Additive Increase, Multiplicative Decrease) |
| Fragment-Verlust (unzuverlässig) | ACK + Retransmit nach 3s ohne ACK |
| Checksum-Fehler | Packet verwerfen, kein Retransmit (ICMP selbst hat keine Garantie) |

---

## 9. Sicherheit

- **E2EE:** ICMP-Payload wird NIE im Klartext gesendet. Die Nachrichten sind bereits auf App-Layer durch Crisix' E2EE-Mechanismus verschlüsselt.
- **Magic Number:** `CRIX`-Präfix verhindert Kollisionen mit echten Ping-Paketen (die typischerweise ASCII-Daten wie "abcdefgh..." enthalten).
- **Replay-Schutz:** Sequence-Nummern im Handshake + Session-Nonce verhindern Replay-Angriffe.
- **Amplification:** Da ICMP Echo Request/Reply symmetrisch ist (gleiche Payload-Größe), gibt es kein Amplification-Risiko.
- **VPN-Missbrauch:** Die `addAllowedApplication(packageName)`-Restriktion verhindert, dass andere Apps den Tunnel missbrauchen.

---

## 10. Performance & Limits

| Metrik | Wert |
|--------|------|
| Bandbreite (theoretisch) | ~12 kbit/s (1 × 1464-Byte-Paket alle 1000 ms) |
| Bandbreite (praktisch, mit Rate-Limiting) | ~3–6 kbit/s |
| Typische Nachrichten-Latenz | 500–2000 ms (Round-Trip + Verarbeitung) |
| Maximale Nachrichtengröße | 500 Bytes (unfragmentiert), ~64 KB (fragmentiert, aber langsam) |
| Batterieverbrauch | ~2–3% pro Stunde (TUN-Adapter + ICMP-Verkehr) |
| Gleichzeitige Sessions | 32 Peers (Begrenzung durch ICMP-Identifier-Range) |

---

## 11. Teststrategie

### 11.1 Unit-Tests

- `IcpmPacketEncoderTest`: Encoding/Decoding, Checksum-Berechnung, Fragmentierung
- `IcpmHandshakeTest`: Session-State-Machine, Timeout-Handling
- `IcpmFragmentationTest`: Reassembly bei Paketverlust

### 11.2 Integrationstests

- **Emulator-Test:** Zwei Emulatoren im selben virtuellen Netzwerk starten
- **ICMP-Blockade simulieren:** `iptables -A OUTPUT -p icmp --icmp-type echo-request -j DROP`
- **Paketverlust simulieren:** `tc qdisc add dev eth0 root netem loss 20%`

### 11.3 Real-Geräte-Test

1. Zwei Android-Geräte im selben WLAN
2. InternetTransport abschalten (Flugmodus + nur WLAN)
3. ICMP-Tunnel aktivieren
4. Kurze Textnachricht senden
5. Prüfen: Nachricht kommt an, < 3s Latenz

---

## 12. Implementierungs-Phasen

### Phase 1: Grundgerüst (3–4 Tage)
- `IcpmVpnService` + AndroidManifest-Eintrag
- `IcpmPacketEncoder` mit Magic-Number und Checksum
- `IcpmTunReader` / `IcpmTunWriter` (rohes IP/ICMP-Parsing)
- Unit-Tests für Encoding/Decoding

### Phase 2: Transport-Klasse (2–3 Tage)
- `IcpmTransport` implementiert `Transport`-Interface
- Integration in `TransportManager` / `TransportInitializer`
- Handshake-Protokoll (`IcpmHandshake`)

### Phase 3: Fragmentierung & ACK (2 Tage)
- `IcpmFragmentation` mit ACK-basierter Wiederholung
- Integration mit bestehendem `MessageFragmenter`

### Phase 4: Discovery & Capabilities (1 Tag)
- ICMP-Flag in `PeerCapabilities`
- DHT-basierte Discovery
- `isAvailable()`-Logik

### Phase 5: UX & Testing (2 Tage)
- VPN-Dialog / Berechtigungs-Flow
- Status-Anzeige ("ICMP-Tunnel aktiv")
- Emulator-Tests, Real-Geräte-Test

### Phase 6: Optimierung (1–2 Tage)
- AIMD-Ratenanpassung
- Battery-Optimierung (Foreground-Service-Tuning)
- Fehlerbehandlung für Edge Cases

**Geschätzte Gesamtzeit: 11–15 Tage**

---

## 13. Risiken & Offene Fragen

| Risiko | Mitigation |
|--------|-----------|
| ICMP wird von modernen Firewalls zunehmend per DPI (Deep Packet Inspection) erkannt und geblockt | Payload-Verschleierung (XOR mit Session-Key oder Base64-Encoding) in Phase 2 |
| Google Play Store könnte VpnService-Missbrauch erkennen und App ablehnen | Klare Dokumentation der Funktion; Opt-in statt Default |
| TUN-Adapter verarbeitet ALLEN Traffic → Performance-Impact | `addAllowedApplication` beschränkt auf Crisix; nur ICMP wird verarbeitet, Rest wird verworfen (DROP) |
| Android 14+ verschärfte Background-Restrictions für VpnService | Foreground-Service mit Notification ist Pflicht |
| ICMP-Identifier-Konflikt mit anderen Ping-Tools | Zufällige Identifier-Auswahl + Kollisionserkennung |

---

## 14. Zusammenfassung

ICMP Tunneling als letzter Fallback bietet Crisix-Nutzern eine **Ultra-Fallback-Konnektivität** in extrem restriktiven Netzwerken. Die Implementierung via Android VpnService vermeidet Root-Zwang, nutzt die bestehende DHT-Infrastruktur für Discovery und integriert sich nahtlos in die bestehende Transport-Prioritätskette.

Die größte Einschränkung ist die **Bandbreite** (~3–6 kbit/s), was ICMP nur für kurze Textnachrichten praktikabel macht. Der **Batterieverbrauch** durch den TUN-Adapter ist ein weiterer Trade-off, der durch die Position als letzter Fallback (nur aktiv wenn nichts anderes geht) gerechtfertigt wird.
