# Crisix Fallback-System: Robustheit & Erweiterungsplan

## Executive Summary

Das Fallback-System von Crisix ist das **Herzstück der Zuverlässigkeit**. Es stellt sicher, dass Nachrichten immer zugestellt werden, egal welche Netzwerk-Interfaces verfügbar sind. Dieser Plan beschreibt:

- **Analyse des aktuellen Systems**
- **Identifizierte Schwachstellen**
- **Verbesserungen für bestehende Fallbacks**
- **Neue Fallback-Strategien**
- **Implementierungsroadmap**

---

## Teil I: Analyse des aktuellen Fallback-Systems

### 1.1 Aktuelle Fallback-Kette (Priority Order)

```
┌─────────────────────────────────────────────────────────────┐
│                  CRISIX FALLBACK-KETTE                      │
├─────────────────────────────────────────────────────────────┤
│ 1. WIFI_DIRECT      │ Lokales P2P (LAN)                      │
│ 2. INTERNET         │ P2P über DHT + Libp2p                  │
│ 3. RELAY            │ WebSocket-Relay (Render.com)           │
│ 4. BLUETOOTH_MESH   │ BLE (Nahbereich, kein Netz)            │
│ 5. SMS              │ SMS-Fallback (experimentell)           │
│ 6. DNS_TUNNEL       │ DNS-Tunnel (sehr begrenzte Bandbreite) │
│ 7. LORA             │ Long Range (geplant)                   │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Fallback-Mechanismus (Aktuell)

**Flow:**
```
sendMessage(peerId, data, uiMessageId)
  ↓
1. Mutual Priority: Filtern nach Peer-Capabilities
  ↓
2. Route Hint: Letzte erfolgreiche Route probieren zuerst
  ↓
3. Probe: Ping-Pong-Test vor echtem Senden (PING_TIMEOUT_MS = 2000ms)
  ↓
4. Send: Echte Nachricht versenden
  ↓
5. ACK: Empfangsbestätigung warten
  ↓
6. Retry: Max 10 Versuche, 10s Intervall
```

### 1.3 Schwachstellen des aktuellen Systems

| Schwachstelle | Auswirkung | Severity |
|---------------|-----------|----------|
| **Statischer Ping-Timeout (2s)** | Langsame/instabile Netzwerke schlagen fehl | MEDIUM |
| **Keine Metrik für Transport-Qualität** | Alle Transporte gleich behandelt, auch wenn manche 99% Fehlerquote haben | HIGH |
| **Route Hints verfallen nach 5 Min** | Bei instabilem Netzwerk wird guter Transport nicht erinnert | MEDIUM |
| **Probe blockiert den Sender** | 2s Timeout pro Transport - bei 7 Transporte = 14s möglich | HIGH |
| **Keine Parallelisierung** | Transporte werden sequenziell probiert, nicht parallel | HIGH |
| **Keine Gewichtung nach Bandbreite** | SMS und DNS-Tunnel werden wie WIFI behandelt | MEDIUM |
| **Keine Priorisierung nach Zuverlässigkeit** | Ein Transport kann sich selbst "heilen" nicht | MEDIUM |
| **Keine Fallback zu niedrig-bandbreite Transporte** | Wenn WIFI/Internet ausfällt, lange Pause bis BLE probiert wird | MEDIUM |

---

## Teil II: Verbesserte Fallback-Strategien

### 2.1 Dynamische Priorität basierend auf Transport-Metriken

**Konzept:** Jeder Transport speichert Metriken, die die Priorität dynamisch anpassen.

```kotlin
data class TransportMetrics(
    val peerId: String,
    val transportType: TransportType,
    
    // Zuverlässigkeit
    val successCount: Int = 0,
    val failureCount: Int = 0,
    var successRate: Double = 0.0,  // 0.0 - 1.0
    
    // Latenz
    val avgLatencyMs: Long = 0,
    val maxLatencyMs: Long = Int.MAX_VALUE.toLong(),
    
    // Bandbreite
    val estimatedBandwidthKbps: Int = 0,  // 0 = unbekannt
    
    // Zeitstempel
    val lastSuccessTime: Long = 0,
    val lastFailureTime: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    
    // Status
    @Volatile var isFailing: Boolean = false,  // Mehrere Fehler in Folge
    @Volatile var recoveryAttempts: Int = 0,   // Wie oft versucht zu "heilen"
)
```

**Dynamische Berechnung der Priorität:**

```kotlin
fun calculateDynamicPriority(baseOrder: Int, metrics: TransportMetrics): Int {
    var adjustedPriority = baseOrder.toDouble()
    
    // Boost für zuverlässige Transporte
    adjustedPriority -= (metrics.successRate * 3.0)  // -3 wenn 100% zuverlässig
    
    // Penalty für fehlerhafte Transporte
    if (metrics.isFailing) adjustedPriority += 5.0
    
    // Boost für niedrige Latenz
    adjustedPriority -= (100.0 / maxOf(metrics.avgLatencyMs, 1))
    
    // Penalty für tote Transporte (lange Zeit kein Success)
    val timeSinceSuccess = System.currentTimeMillis() - metrics.lastSuccessTime
    if (timeSinceSuccess > 5 * 60 * 1000) adjustedPriority += 2.0
    
    return adjustedPriority.toInt()
}
```

### 2.2 Adaptive Probe-Timeouts

**Konzept:** Timeout basiert auf Netzwerk-Bedingungen

```kotlin
private fun calculateProbeTimeout(metrics: TransportMetrics): Long {
    return when {
        metrics.avgLatencyMs > 5000 -> 8000L      // Sehr langsam: 8s
        metrics.avgLatencyMs > 2000 -> 5000L      // Langsam: 5s
        metrics.isFailing -> 3000L                // Instabil: 3s (schneller geben)
        else -> 2000L                             // Normal: 2s
    }
}
```

### 2.3 Parallele Probe für Top-3 Transporte

**Konzept:** Statt sequenziell zu probieren, probiere Top-3 parallel

```kotlin
suspend fun probeTransportsParallel(peerId: String, transports: List<Transport>): Transport? {
    val top3 = transports.sortedBy { calculatePriority(it) }.take(3)
    
    return withTimeoutOrNull(5000L) {
        // Race condition - erster der antwortet gewinnt
        val deferreds = top3.map { transport ->
            async { 
                if (probeTransport(peerId, transport)) transport else null 
            }
        }
        deferreds.awaitAll().firstNotNullOrNull()
    }
}
```

### 2.4 Intelligente Fallback-Eskalation

**Konzept:** Fallback beschleunigt bei Fehler, nicht auf nächste probiert nach längerem Timeout

```kotlin
private suspend fun sendMessageWithIntelligentFallback(
    peerId: String, 
    data: ByteArray, 
    uiMessageId: String?
): Result<Unit> {
    val metrics = getMetricsForPeer(peerId)
    var consecutiveFailures = 0
    
    for (transport in getOrderedTransports(peerId)) {
        Log.i(TAG, "[Fallback] Versuche $transport für ${peerId.take(8)}...")
        
        // Dynamischer Timeout
        val timeout = calculateProbeTimeout(metrics[transport.type])
        
        try {
            val probeOk = withTimeout(timeout) {
                probeTransport(peerId, transport)
            }
            
            if (probeOk) {
                val result = transport.send(peerId, payload)
                if (result.isSuccess) {
                    recordSuccess(peerId, transport.type)
                    return result
                } else {
                    recordFailure(peerId, transport.type)
                    consecutiveFailures++
                    
                    // Schneller zum nächsten Transport bei Fehler
                    if (consecutiveFailures >= 2) {
                        Log.w(TAG, "[Fallback] 2x Fehler → eskaliere zu nächstem Transport")
                    }
                }
            } else {
                recordFailure(peerId, transport.type)
                consecutiveFailures++
            }
        } catch (e: TimeoutCancellationException) {
            recordTimeout(peerId, transport.type)
            consecutiveFailures++
            
            if (consecutiveFailures >= 2) {
                Log.w(TAG, "[Fallback] Timeouts → eskaliere schneller")
            }
        }
    }
    
    return Result.failure(Exception("Alle Transporte erschöpft"))
}
```

### 2.5 Bandbreite-bewusste Fallback-Wahl

**Konzept:** Wähle Transport basierend auf Nachrichtengröße

```kotlin
fun selectTransportBySize(peerId: String, dataSize: Int): List<TransportType> {
    return when {
        // Klein (< 1KB): Alle Transporte
        dataSize < 1024 -> priorityOrder
        
        // Mittel (1KB - 100KB): Keine SMS
        dataSize < 100 * 1024 -> priorityOrder.filter { it != TransportType.SMS }
        
        // Groß (> 100KB): Nur schnelle Transporte
        else -> listOf(
            TransportType.WIFI_DIRECT,
            TransportType.INTERNET,
            TransportType.RELAY,
            TransportType.BLUETOOTH_MESH
        )
    }
}
```

---

## Teil III: Neue Fallback-Optionen

### 3.1 SMS-Fallback (Robustheit)

**Status:** Experimentell  
**Zweck:** Ultra-Fallback wenn alle anderen ausfallen

```kotlin
class SmsTransport(
    private val context: Context,
    private val deviceId: String,
    private val phoneNumber: String?  // Peer's Telefonnummer
) : Transport {
    override val type: TransportType = TransportType.SMS
    
    // Limitierung: 160 Zeichen pro SMS
    // Kompression nötig für JSON + Nachricht
    override val capabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = 160,
        supportsImages = false,
        supportsAudio = false
    )
    
    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        // 1. Daten komprimieren (Gzip)
        // 2. Base64 + Truncate zu 160 Zeichen
        // 3. SMS senden
    }
}
```

**Vorteile:**
- Funktioniert ohne Internet
- Universelle Unterstützung auf allen Telefonen
- Zuverlässig für Text-Nachrichten

**Nachteile:**
- Begrenzte Größe (160 Zeichen)
- Kostet Geld (nicht ideal)
- Langsam (kann Sekunden dauern)

### 3.2 LoRa / NB-IoT Fallback (Langstrecke)

**Status:** Geplant  
**Zweck:** Kommunikation über große Entfernungen ohne Infrastruktur

```kotlin
class LoraTransport(
    private val deviceId: String
) : Transport {
    override val type: TransportType = TransportType.LORA
    
    override val capabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = 250,  // Größer als SMS, kleiner als WIFI
        supportsImages = false,
        supportsAudio = false,
        isMetered = false  // Kostenlos!
    )
    
    // Kommunikation über LoRa-Netzwerk
    // Reichweite: 5-15 km (ländlich), weniger in Stadt
}
```

**Einsatz-Szenarien:**
- Bergige Regionen
- Ländliche Gebiete ohne Mobilfunk
- Notfallkommunikation (Naturkatastrophen)

### 3.3 Mesh-Relay über Bluetooth (P2P-Weiterleitungen)

**Status:** Erweiterung von BLE  
**Zweck:** Nachrichten über mehrere BLE-Hops weitergeben

```
Alice (nur BLE)
    ↓ (BLE)
  Bob (WIFI + BLE)
    ↓ (WIFI)
  Charlie (WIFI)

Alice sendet zu Charlie über Bob als Relay!
```

**Implementierung:**

```kotlin
data class MeshMessage(
    val originalSender: String,
    val originalRecipient: String,
    val hopLimit: Int = 3,  // Max 3 Hops
    val relayedBy: List<String> = emptyList(),
    val payload: ByteArray
)

suspend fun sendViaMesh(sender: String, recipient: String, data: ByteArray) {
    // 1. Prüfe ob recipient erreichbar ist
    if (isReachable(recipient)) {
        send(recipient, data)
        return
    }
    
    // 2. Finde Neighbors mit besserer Konnektivität
    val meshNeighbors = findNeighborsWithBetterReach(recipient)
    
    for (neighbor in meshNeighbors) {
        val meshMsg = MeshMessage(
            originalSender = sender,
            originalRecipient = recipient,
            relayedBy = listOf(sender),
            payload = data
        )
        send(neighbor, serialize(meshMsg))
    }
}
```

### 3.4 HTTP/HTTPS Direct Connection Fallback

**Status:** Neu  
**Zweck:** Direktes HTTP zu bekannten Peers, nicht über Relay

```kotlin
class DirectHttpTransport(
    private val context: Context,
    private val deviceId: String
) : Transport {
    override val type: TransportType = TransportType.DIRECT_HTTP
    
    // Wenn Peer eine IP + Port hat:
    // http://<peer-ip>:<port>/api/messages
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        val peerIp = resolvePeerIp(peerId)
        if (peerIp == null) return Result.failure(Exception("IP unbekannt"))
        
        val request = Request.Builder()
            .url("http://$peerIp:54230/api/messages")
            .post(RequestBody.create(data))
            .build()
        
        return try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("HTTP ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

### 3.5 Multipath Aggregation

**Status:** Fortgeschrittenes Konzept  
**Zweck:** Nutze mehrere Transporte parallel für höhere Geschwindigkeit

```
Nachricht: "Hallo Welt" (11 Bytes)

Split über 3 Transporte:
├─ WIFI:     Bytes 0-3   (4 Bytes)
├─ INTERNET: Bytes 4-7   (4 Bytes)
└─ RELAY:    Bytes 8-10  (3 Bytes)

Empfänger reassembliert: "Hallo Welt"
```

**Vorteil:** 3x schneller bei stabilen Transporte  
**Nachteil:** Komplexe Implementierung, nur für Nachrichten > 1KB sinnvoll

---

## Teil IV: Robustheit-Verbesserungen

### 4.1 Exponentieller Backoff für Retries

**Aktuell:**
```
Retry-Versuch 1: 10s
Retry-Versuch 2: 10s
Retry-Versuch 3: 10s
...
```

**Neu (exponentiell):**
```
Retry-Versuch 1:  10s (2^0 * 10s)
Retry-Versuch 2:  20s (2^1 * 10s)
Retry-Versuch 3:  40s (2^2 * 10s)
Retry-Versuch 4:  80s (2^3 * 10s)
...
Max: 1 Stunde
```

**Vorteil:** Weniger Verschwendung bei dauerhaften Ausfällen

### 4.2 Circuit-Breaker Pattern

**Konzept:** Transport wird temporär deaktiviert, wenn zu viele Fehler

```kotlin
enum class CircuitState {
    CLOSED,      // Normal, Transport wird genutzt
    OPEN,        // Zu viele Fehler, Transport wird übersprungen
    HALF_OPEN    // Recovery-Versuch in Progress
}

class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 30_000
) {
    @Volatile var state: CircuitState = CircuitState.CLOSED
    @Volatile var failureCount = 0
    @Volatile var lastFailureTime = 0L
    
    suspend fun recordSuccess() {
        failureCount = 0
        state = CircuitState.CLOSED
        Log.i(TAG, "Circuit breaker RESET")
    }
    
    suspend fun recordFailure() {
        failureCount++
        lastFailureTime = System.currentTimeMillis()
        
        if (failureCount >= failureThreshold) {
            state = CircuitState.OPEN
            Log.w(TAG, "Circuit breaker OPEN after $failureCount failures")
            
            // Nach resetTimeout → HALF_OPEN versuchen
            delay(resetTimeoutMs)
            state = CircuitState.HALF_OPEN
        }
    }
    
    fun canUse(): Boolean = state != CircuitState.OPEN
}
```

### 4.3 Health-Check Background Job

**Konzept:** Periodisch Transporte checken, auch wenn nicht genutzt

```kotlin
private suspend fun healthCheckAllTransports() {
    while (isRunning) {
        for (transport in transports) {
            if (!transport.isAvailable()) continue
            
            // Sende einen Ping an bekannte zuverlässige Peers
            for (peerId in recentPeers.take(3)) {
                val probeOk = probeTransport(peerId, transport)
                
                if (probeOk) {
                    recordHealthSuccess(transport.type, peerId)
                } else {
                    recordHealthFailure(transport.type, peerId)
                }
            }
        }
        
        delay(30_000)  // Health-Check alle 30s
    }
}
```

### 4.4 Adaptive Route Hints mit TTL

**Aktuell:**
```
TTL: 5 Minuten (statisch)
```

**Neu (adaptiv):**
```
TTL = MIN_TTL + (successRate * MAX_TTL_DELTA)
    = 1 Minute + (0.95 * 19 Minuten)
    = 19 Minuten für zuverlässige Routes
    
Vs.
    = 1 Minute + (0.50 * 19 Minuten)
    = 10 Minuten für instabile Routes
```

---

## Teil V: Implementierungs-Roadmap

### Phase 1: Transport-Metriken (Woche 1-2)

**Ziele:**
- TransportMetrics-Datenklasse hinzufügen
- Metrics-Tracking in alle Transporte
- Metrics-Storage in SharedPreferences (optional: Room-DB)

**Datei-Änderungen:**
- Neue: `TransportMetrics.kt`
- Neue: `MetricsCollector.kt`
- Modifiziert: `TransportManager.kt`
- Modifiziert: Alle Transport-Klassen

**Tests:**
```kotlin
test("Erfolgreiches Senden erhöht successCount") {
    metrics.recordSuccess()
    assert(metrics.successCount == 1)
}

test("Metriken werden korrekt aggregiert") {
    repeat(10) { metrics.recordSuccess() }
    repeat(2) { metrics.recordFailure() }
    assert(metrics.successRate == 0.833)  // 10/12
}
```

### Phase 2: Dynamische Priorität (Woche 2-3)

**Ziele:**
- calculateDynamicPriority() implementieren
- TransportManager nutzt dynamische Priorität statt statischer
- Testen mit verschiedenen Szenarien

**Datei-Änderungen:**
- Modifiziert: `TransportManager.kt`

**Tests:**
```kotlin
test("Zuverlässiger Transport hat höhere Priorität") {
    val metrics1 = TransportMetrics(successRate = 1.0)
    val metrics2 = TransportMetrics(successRate = 0.5)
    
    assert(calculateDynamicPriority(0, metrics1) < calculateDynamicPriority(0, metrics2))
}
```

### Phase 3: Adaptive Probe Timeouts (Woche 3)

**Ziele:**
- calculateProbeTimeout() implementieren
- Probe-Logik nutzt adaptive Timeouts

**Datei-Änderungen:**
- Modifiziert: `TransportManager.kt`

### Phase 4: Parallele Probe (Woche 4)

**Ziele:**
- probeTransportsParallel() implementieren
- Top-3 Transporte parallel probieren
- Falls eine antwortet: sofort diese nutzen

**Datei-Änderungen:**
- Modifiziert: `TransportManager.kt`

**Performance-Gewinn:**
```
Alt (Sequenziell):   WIFI (2s timeout) → INTERNET (2s) → RELAY (2s) = 6s best-case
Neu (Parallel):      WIFI + INTERNET + RELAY = 2s best-case
                     = 3x schneller!
```

### Phase 5: Circuit Breaker (Woche 5)

**Ziele:**
- CircuitBreaker-Klasse hinzufügen
- Jeder Transport hat einen Circuit Breaker
- OPEN-State führt zum Überspringen des Transports

**Datei-Änderungen:**
- Neue: `CircuitBreaker.kt`
- Modifiziert: `TransportManager.kt`

### Phase 6: SMS-Transport (Woche 6-7)

**Ziele:**
- SmsTransport implementieren
- Kompression/Decompression für 160-Zeichen-Limit
- Integration in TransportManager

**Datei-Änderungen:**
- Neue: `SmsTransport.kt`
- Modifiziert: `TransportManager.kt`, `AndroidManifest.xml`

**Permissions nötig:**
```xml
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
```

### Phase 7: Health-Check Job (Woche 8)

**Ziele:**
- healthCheckAllTransports() implementieren
- Background-Job läuft alle 30 Sekunden
- Aktualisiert Metriken proaktiv

**Datei-Änderungen:**
- Modifiziert: `TransportManager.kt`

### Phase 8: LoRa / NB-IoT (Woche 9-10) - Optional

**Ziele:**
- LoraTransport-Stub erstellen
- Hardware-Integration prüfen
- Pilot-Test mit LoRa-Modul

### Phase 9: Testing & Validation (Woche 11-12)

**Szenarien testen:**

| Szenario | Test-Beschreibung | Erwartet |
|----------|------------------|----------|
| **Alle WIFI** | Nur WIFI verfügbar | Schnelle Delivery |
| **Nur Internet** | WIFI aus, Internet an | Internet wird genutzt |
| **Internet instabil** | Viele Timeouts | Fallback zu RELAY |
| **Alle aus** | Keine Netzwerk | SMS-Fallback (falls aktiviert) |
| **BLE + WIFI** | Beide verfügbar | WIFI bevorzugt |
| **Schnelles Failover** | WIFI ausfällt → INTERNET startet | < 5s Übergangspause |
| **Lasttest** | 100 Nachrichten parallel | Max 1 Drop |

---

## Teil VI: Konfigurationsoptionen

### 6.1 Transport-Konfiguration

```kotlin
data class TransportConfig(
    val enabled: Boolean = true,
    val basePriority: Int = 0,
    val maxRetries: Int = 10,
    val retryIntervalMs: Long = 10_000,
    val probeTimeoutMs: Long = 2_000,
    val metricsHistorySize: Int = 100,
    val circuitBreakerThreshold: Int = 5,
    val bandwidthEstimateKbps: Int = 0,  // 0 = auto-detect
)
```

### 6.2 User-Preferences

```xml
<!-- settings.xml -->
<PreferenceScreen>
    <PreferenceCategory android:title="Fallback-Einstellungen">
        <CheckBoxPreference
            android:key="enable_sms_fallback"
            android:title="SMS-Fallback erlauben"
            android:summary="Nachricht per SMS senden, wenn alles andere ausfällt"
            android:defaultValue="false" />
        
        <ListPreference
            android:key="preferred_transport"
            android:title="Bevorzugter Transport"
            android:entries="@array/transport_names"
            android:entryValues="@array/transport_types"
            android:defaultValue="wifi_direct" />
        
        <SeekBarPreference
            android:key="max_retry_attempts"
            android:title="Maximale Retry-Versuche"
            android:defaultValue="10"
            android:max="30" />
    </PreferenceCategory>
</PreferenceScreen>
```

---

## Teil VII: Metriken & Monitoring

### 7.1 Dashboard für Transport-Status

```
┌──────────────────────────────────────────────────────┐
│           TRANSPORT-STATUS-DASHBOARD                 │
├──────────────────────────────────────────────────────┤
│ WIFI_DIRECT                                          │
│   Success Rate: ████████░░ 85%  (34/40)              │
│   Avg Latency:  150ms              Last Success: 2s  │
│   Status:       ✓ CONNECTED                          │
│                                                       │
│ INTERNET (DHT)                                       │
│   Success Rate: ██████░░░░ 62%  (25/40)              │
│   Avg Latency:  850ms              Last Success: 12s │
│   Status:       ⚠ DEGRADED                           │
│                                                       │
│ RELAY                                                │
│   Success Rate: ████░░░░░░ 40%  (16/40)              │
│   Avg Latency:  2100ms             Last Success: 45s │
│   Status:       ⚠ POOR                               │
│                                                       │
│ BLUETOOTH_MESH                                       │
│   Success Rate: ██████████ 100% (20/20)              │
│   Avg Latency:  200ms              Last Success: 1s  │
│   Status:       ✓ EXCELLENT                          │
└──────────────────────────────────────────────────────┘
```

### 7.2 Logging

```kotlin
Log.d(TAG, """
    [Fallback-Strategy]
    Peer: ${peerId.take(8)}
    Data Size: ${dataSize} bytes
    Available Transports: $availableCount
    Selected (Ordered):
      1. ${orderedTransports[0]} (Priority: ${priorities[0]}, Success Rate: ${metrics[0].successRate * 100}%)
      2. ${orderedTransports[1]} (Priority: ${priorities[1]})
      3. ${orderedTransports[2]} (Priority: ${priorities[2]})
""".trimIndent())
```

---

## Teil VIII: Fehlerbehandlung & Recovery

### 8.1 Eskalationsmatrix

```
Fehler-Typ              → Aktion
────────────────────────────────────────────────
Probe Timeout           → Nächster Transport sofort
Send Failure (Netz)     → Exponentieller Backoff
Send Failure (Auth)     → Nicht Retry, Fehler zurück
ACK Timeout             → Nach 30s Retry mit Ping
Transport Crashed       → Circuit Breaker → OPEN
Alle Transporte OPEN    → Recovery-Versuch nach 1min
```

### 8.2 Recovery-Mechanismen

```kotlin
suspend fun recoverCircuitBreaker(transport: Transport) {
    // Versuch 1: Kleine Nachricht an bekannten Peer
    if (probeTransport(recentGoodPeer, transport)) {
        transport.circuitBreaker.state = CLOSED
        recordHealthSuccess(transport.type)
        return
    }
    
    // Versuch 2: Erneut starten
    transport.stop()
    delay(2_000)
    transport.start()
    
    // Versuch 3: Hard reset
    transport.reset()
}
```

---

## Teil IX: Sicherheit & Datenschutz

### 9.1 Metriken-Datenschutz

- ✅ Metriken enthalten KEINE Nachricht-Inhalte
- ✅ Nur anonymisierte Peer-IDs (erste 8 Zeichen)
- ✅ Daten können verschlüsselt in SharedPreferences gespeichert werden

### 9.2 Relay-Sicherheit

- ✅ TLS 1.3 für WebSocket (wss://)
- ✅ Nachrichten-Payload ist bereits E2EE-verschlüsselt
- ✅ Relay sieht nur die Envelopes, nicht den Inhalt

---

## Teil X: Performance-Optimierungen

### 10.1 Caching

```kotlin
// Cache successful routes für häufige Peers
private val routeCache = LRUCache<String, TransportType>(maxSize = 100)

// TTL: 5 Minuten, wird aktualisiert bei jedem Success
fun getCachedRoute(peerId: String): TransportType? {
    return routeCache.get(peerId)?.takeIf {
        System.currentTimeMillis() - getLastSuccessTime(it) < 5 * 60 * 1000
    }
}
```

### 10.2 Batch-Probing

```kotlin
// Probe alle Transporte für einen Peer zusammen, nicht einzeln
suspend fun probeAllTransports(peerId: String): Map<TransportType, Boolean> {
    return withContext(Dispatchers.Default) {
        transports.associate { transport ->
            transport.type to async {
                probeTransport(peerId, transport)
            }
        }.mapValues { it.value.await() }
    }
}
```

---

## Teil XI: Testing-Strategie

### 11.1 Unit Tests

```kotlin
// TransportMetricsTest.kt
class TransportMetricsTest {
    @Test fun successRateCalculation() { ... }
    
    @Test fun dynamicPriorityBoost() { ... }
    
    @Test fun circuitBreakerStateTransition() { ... }
}
```

### 11.2 Integration Tests

```kotlin
// FallbackIntegrationTest.kt
@RunWith(AndroidJUnit4::class)
class FallbackIntegrationTest {
    @Test fun testSequentialFallback() {
        // WIFI → INTERNET → RELAY
    }
    
    @Test fun testParallelProbe() {
        // Top-3 parallel, schnellste gewinnt
    }
    
    @Test fun testCircuitBreakerRecovery() {
        // Transport fails → Circuit OPEN → Recovery nach Timeout
    }
}
```

### 11.3 Network Simulation Tests

```kotlin
// SimulatedNetworkTest.kt
class SimulatedNetworkTest {
    @Before
    fun setupNetworkSimulation() {
        // Mock Network Delays & Failures
        networkSimulator.setLatency(TransportType.WIFI, 100..200)
        networkSimulator.setFailureRate(TransportType.INTERNET, 0.3)
    }
    
    @Test fun testWifiOutage() {
        networkSimulator.disconnect(TransportType.WIFI)
        sendMessage("test")
        verify(INTERNET).send()
    }
}
```

---

## Teil XII: Erfolgs-Kriterien

| Kriterium | Ziel | Messung |
|-----------|------|---------|
| **Delivery Success Rate** | ≥ 99.5% | Alle Nachrichten kommen an |
| **Avg Failover Time** | < 5 Sekunden | WIFI → INTERNET Wechsel |
| **Circuit Breaker Recovery** | < 1 Minute | Tote Transport heilt sich |
| **Metriken-Overhead** | < 5 MB/Monat | Speicher-Nutzung |
| **CPU-Overhead** | < 2% | Parallel Probing |
| **User Visibility** | ✓ Dashboard | Transport-Status sichtbar |

---

## Teil XIII: Rückwärts-Kompatibilität

- ✅ Alte Clients funktionieren weiterhin
- ✅ Neue Metriken sind optional
- ✅ Circuit Breaker ist transparent
- ✅ Fallback-Kette bleibt gleich

---

## Zusammenfassung

Das verbesserte Fallback-System wird Crisix **massiv robuster** machen:

1. **Dynamische Transporte** basierend auf echten Metriken
2. **Paralleles Probing** für schnellere Fallbacks
3. **Circuit Breaker** für fehlerhafte Transporte
4. **SMS-Notfall-Fallback** für extreme Situationen
5. **Transparentes Monitoring** für Benutzer

**Geschätzter Aufwand:** 12 Wochen (2-3 Entwickler)  
**Impact:** 99.5% Delivery-Erfolgsrate (von aktuell ~95%)

