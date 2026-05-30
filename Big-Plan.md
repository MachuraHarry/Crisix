# Crisix — Großer Verbesserungsplan (Big-Plan)

## 🎯 Mission
**Robuster E2EE-Schlüsselaustausch + Netzwerk-Stabilität**

Die aktuelle App kann Nachrichten senden, aber der Schlüsselaustausch (E2EE-Handshake) und die Netzwerk-Erreichbarkeit sind die kritischen Fehler. Dieser Plan adressiert beide.

---

## 🔴 Kritische Probleme (aus Log-Analyse)

### Problem 1: E2EE-Handshake schlägt 5x fehl
```
HandshakeRetryManager: ⏱️ Timeout für 493d14fa... nach 20000 ms
[Versuch 1-5 alle Timeout]
```

**Root Cause:**
- Handshake-Nachricht wird gesendet, aber der Peer empfängt sie nicht
- Oder der Peer antwortet nicht innerhalb von 20 Sekunden
- Keine Transport-Erreichbarkeit während des Handshakes

**Symptome:**
- Beide Geräte können sich nicht verbinden
- DHT-Suche findet keine Peers ("0 Peers für Topic gefunden")
- BLE-Scan findet keine Peers ("BLE Scan: kein Peer gefunden")
- STUN schlägt fehl (→ NAT traversal geht nicht)

---

### Problem 2: Transport-Netzwerk nicht funktionsfähig
```
TransportManager: Probe fehlgeschlagen für WIFI_DIRECT → skip
TransportManager: Probe fehlgeschlagen für RELAY → skip
TransportManager: Probe fehlgeschlagen für BLUETOOTH_MESH → skip
DnsTunnel: Server nicht erreichbar: null
RelayTransport: Relay-Fehler: ERROR:unknown-command
```

**Root Cause:**
- Kein Transport kann eine erfolgreiche Verbindung zum anderen Peer herstellen
- Relay-Server gibt Fehler zurück
- DNS-Tunnel Server nicht erreichbar

**Symptome:**
- "Broadcast-Capabilities an 0 Peers"
- Alle Probes schlagen fehl
- Handshake-Retry hat keine erfolgreiche Transport-Route

---

### Problem 3: Netzwerk-Erreichbarkeit
```
Libp2pManager: Verbindung zu 10.0.2.16:39223 fehlgeschlagen
InternetTransport: Internet nicht erreichbar (8.8.8.8:53): null
NatTraversal: STUN ... fehlgeschlagen: Poll timed out
```

**Root Cause:**
- Emulatoren können sich nicht direkt verbinden (unterschiedliche NAT-Situationen)
- STUN-Server alle blocked
- DHT-Discovery funktioniert, aber Peer-Adressen sind nicht erreichbar

---

## ✅ Lösung 1: E2EE-Handshake robuster machen

### Phase 1a: One-Sided Encryption (SOFORT)
**Ziel:** Nachrichten verschlüsseln, auch wenn Handshake nicht komplett ist

```kotlin
// In E2eeManager.kt
fun canEncryptMessage(peerId: String): Boolean {
    // true wenn:
    // 1. Session vollständig aufgebaut (beide haben sich Keys ausgetauscht)
    // 2. ODER Initiator hat Pre-KeyMessage vom Responder erhalten (One-sided)
    return hasCompletedSession(peerId) || hasReceivedPeerBundle(peerId)
}

// In CrisixApp.kt bei sendMessage():
if (e2eeManager.canEncryptMessage(peerId)) {
    // Verschlüsseln + Senden
    encryptAndSend(message)
} else if (e2eeManager.isHandshakeInProgress(peerId)) {
    // Queuen + Warten
    queueForEncryption(message)
} else {
    // Initialisiere Handshake + Queuen
    startHandshake(peerId)
    queueForEncryption(message)
}
```

**Vorteile:**
- Nachrichten können schon während des Handshakes verschlüsselt werden
- Verhindert "hängende" Nachrichten
- Funktioniert auch bei Netzwerk-Verzögerungen

---

### Phase 1b: Handshake-Timeout-Logik VERBESSERN
**Problem:** 20 Sekunden Timeout ist zu kurz bei schlechtem Netzwerk

```kotlin
// In HandshakeRetryManager.kt
data class HandshakeRetryConfig(
    val initialTimeout: Long = 20_000,  // 20s
    val maxAttempts: Int = 5,
    val backoffMultiplier: Double = 2.0,  // 1s, 2s, 4s, 8s, 16s = 31s total
    val maxTimeoutPerAttempt: Long = 60_000  // 60s max
)

// Timeout = min(initialTimeout * backoffMultiplier^attempt, maxTimeoutPerAttempt)
// Versuch 1: 20s
// Versuch 2: 40s
// Versuch 3: 60s (capped)
// Versuch 4: 60s (capped)
// Versuch 5: 60s (capped)
```

**Implementierung:**
```kotlin
suspend fun performRetry(peerId: String) {
    for (attempt in 1..config.maxAttempts) {
        val timeout = calculateTimeout(attempt)
        
        try {
            Log.d(TAG, "Versuch $attempt/$maxAttempts für $peerId (Timeout: ${timeout}ms)")
            
            withTimeout(timeout) {
                createAndSendHandshake(peerId)
                // Warte auf Antwort
                waitForHandshakeResponse(peerId)
            }
            
            Log.i(TAG, "✅ Handshake erfolgreich bei Versuch $attempt")
            return
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "⏱️ Timeout bei Versuch $attempt nach ${timeout}ms")
            if (attempt < maxAttempts) {
                val delayMs = calculateDelay(attempt)
                delay(delayMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler bei Versuch $attempt: ${e.message}", e)
        }
    }
    
    Log.e(TAG, "❌ Handshake fehlgeschlagen nach $maxAttempts Versuchen")
    onHandshakeExhausted(peerId)
}

private fun calculateTimeout(attempt: Int): Long {
    val exponentialTimeout = config.initialTimeout * (config.backoffMultiplier.pow(attempt - 1)).toLong()
    return minOf(exponentialTimeout, config.maxTimeoutPerAttempt)
}
```

---

### Phase 1c: Fallback-Verschlüsselung (Pre-Shared Key Variant)
**Für den Fall, dass Handshake komplett fehlschlägt:**

```kotlin
// In E2eeManager.kt
fun getFallbackEncryptionKey(peerId: String): ByteArray? {
    // Verwende einen deterministischen Schlüssel aus QR-Code oder manueller Eingabe
    // z.B. Hash(myFingerprint || peerFingerprint || sharedSecret)
    
    val sharedSecret = getSharedSecretFromQrOrManual(peerId)
    if (sharedSecret != null) {
        return deriveKeyFromSecret(sharedSecret)
    }
    return null
}

// In CrisixApp.kt
if (e2eeManager.isHandshakeExhausted(peerId)) {
    val fallbackKey = e2eeManager.getFallbackEncryptionKey(peerId)
    if (fallbackKey != null) {
        Log.w(TAG, "⚠️ Verwende Fallback-Verschlüsselung für $peerId")
        encryptWithFallbackKey(message, fallbackKey)
    } else {
        Log.w(TAG, "⚠️ Keine Fallback-Verschlüsselung verfügbar, sende unverschlüsselt")
        sendUnencrypted(message, showWarning = true)
    }
}
```

---

## ✅ Lösung 2: Netzwerk-Konnektivität FIX

### Phase 2a: Transport-Probing VERBESSERN
**Problem:** Probes schlagen alle fehl, weil keine Peers erreichbar

```kotlin
// In TransportManager.kt
suspend fun probeTransport(transportType: TransportType, peerId: String): Boolean {
    val transport = getTransport(transportType) ?: return false
    
    return try {
        Log.d(TAG, "[probeTransport] Probe für $transportType zu $peerId")
        
        // Sende Ping + warte auf Pong (statt nur Verbindung zu prüfen)
        val pingId = UUID.randomUUID().toString()
        val pingPayload = JSONObject().apply {
            put("type", "crisix_ping")
            put("id", pingId)
        }.toString().toByteArray()
        
        val probeTimeout = when (transportType) {
            TransportType.WIFI_DIRECT -> 3000L
            TransportType.INTERNET -> 8000L    // Länger für DHT
            TransportType.BLUETOOTH_MESH -> 5000L
            TransportType.RELAY -> 4000L
            TransportType.DNS_TUNNEL -> 6000L
            else -> 5000L
        }
        
        val deferred = CompletableFuture<Boolean>()
        
        // Registriere Pong-Listener
        val tempListener = { response: String ->
            if (response.contains(pingId)) {
                deferred.complete(true)
            }
        }
        registerPongListener(pingId, tempListener)
        
        // Sende Ping
        transport.send(peerId, pingPayload)
        
        // Warte auf Pong
        val result = try {
            deferred.get(probeTimeout, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            false
        }
        
        if (result) {
            Log.i(TAG, "[probeTransport] ✅ Probe erfolgreich für $transportType")
        } else {
            Log.w(TAG, "[probeTransport] ❌ Probe fehlgeschlagen für $transportType (Timeout)")
        }
        
        result
    } catch (e: Exception) {
        Log.e(TAG, "[probeTransport] ❌ Probe Fehler für $transportType: ${e.message}")
        false
    } finally {
        unregisterPongListener(pingId)
    }
}
```

---

### Phase 2b: Relay-Fehler diagnostizieren
**Problem:** "ERROR:unknown-command"

```kotlin
// In RelayTransport.kt - Debug-Modus
private fun handleRelayCommand(command: String) {
    Log.d(TAG, "[RelayTransport] Empfangenes Kommando: $command")
    
    when {
        command == "OK:registered" -> {
            // Alles OK
        }
        command.startsWith("FROM:") -> {
            // Nachricht empfangen
        }
        command.startsWith("ERROR:") -> {
            val errorMsg = command.removePrefix("ERROR:")
            Log.e(TAG, "[RelayTransport] Server-Fehler: $errorMsg")
            
            // Stelle fest, welches Kommando den Fehler verursacht hat
            Log.e(TAG, "[RelayTransport] Letztes gesendetes Kommando war: $lastSentCommand")
            
            // Beispiel Fehlerbehandlung:
            when (errorMsg) {
                "unknown-command" -> {
                    Log.e(TAG, "❌ Relay Server verstand Kommando nicht: $lastSentCommand")
                    // Möglich: Relay-Server-Version mismatch
                    // Oder: Falsches Protokoll-Format
                    reconnect()
                }
                "peer-not-found" -> {
                    Log.w(TAG, "⚠️ Peer nicht auf Relay registriert")
                }
                else -> {
                    Log.e(TAG, "❌ Unbekannter Fehler: $errorMsg")
                }
            }
        }
        else -> {
            Log.w(TAG, "[RelayTransport] Unerkanntes Kommando: $command")
        }
    }
}
```

---

### Phase 2c: Netzwerk-Erkennungslogik OPTIMIEREN
**Problem:** DHT gibt 0 Peers zurück, obwohl Peers im gleichen Netzwerk sind

```kotlin
// In PeerDiscovery.kt
suspend fun discoverPeers(peerId: String): List<Peer> {
    val peers = mutableListOf<Peer>()
    
    Log.d(TAG, "Starte Peer-Discovery für $peerId")
    
    // 1. Lokale Netzwerk-Discovery (Broadcast)
    try {
        val localPeers = discoverLocalPeers()
        peers.addAll(localPeers)
        Log.i(TAG, "✅ ${localPeers.size} lokale Peers gefunden")
    } catch (e: Exception) {
        Log.w(TAG, "⚠️ Lokale Discovery fehlgeschlagen: ${e.message}")
    }
    
    // 2. DHT-Discovery
    try {
        val dhtPeers = discoverViaMainlineDht(peerId)
        peers.addAll(dhtPeers)
        Log.i(TAG, "✅ ${dhtPeers.size} DHT-Peers gefunden")
    } catch (e: Exception) {
        Log.w(TAG, "⚠️ DHT Discovery fehlgeschlagen: ${e.message}")
    }
    
    // 3. Relay-Fallback (wenn beide auf Relay registriert sind)
    try {
        if (peers.isEmpty()) {
            Log.w(TAG, "⚠️ Keine Peers in lokalem Netzwerk oder DHT gefunden")
            Log.i(TAG, "💡 Fallback: Relay-Discovery")
            // Relay wird automatisch versucht, wenn Peer dort registriert ist
        }
    } catch (e: Exception) {
        Log.w(TAG, "⚠️ Relay Fallback fehlgeschlagen: ${e.message}")
    }
    
    Log.i(TAG, "🔍 Discovery abgeschlossen: ${peers.size} Peers gefunden")
    return peers
}
```

---

## ✅ Lösung 3: App-Robustheit ALLGEMEIN

### Phase 3a: Transport-Status SICHTBAR machen (UI)
**Problem:** User sieht nicht, warum Nachricht nicht versendet wird

```kotlin
// In TransportManager.kt
data class TransportStatus(
    val type: TransportType,
    val state: ConnectionState,
    val peersCount: Int,
    val detail: String,
    val lastError: String? = null,
    val lastSuccessAt: Long? = null,
    val failureCount: Int = 0
)

sealed class ConnectionState {
    object SEARCHING : ConnectionState()      // Sucht nach Peers
    object CONNECTED : ConnectionState()      // Mindestens 1 Peer erreichbar
    object ERROR : ConnectionState()          // Kritischer Fehler
    object UNAVAILABLE : ConnectionState()    // Transport nicht verfügbar
}

// UI zeigt:
// ✅ INTERNET: 23 DHT-Knoten (2h ago)
// ⏳ RELAY: Sucht Peer... (Versuch 3/5)
// ❌ BLE: Fehler - Advertising fehlgeschlagen (5x Retry)
// 🔄 DNS_TUNNEL: Reconnecting... (30s ago)
```

---

### Phase 3b: Handshake-Status TRACKBAR
**In ChatDetailScreen:**

```kotlin
// Zeige Handshake-Fortschritt
Column {
    when {
        e2eeSessions[peerId] == true -> {
            Icon(Icons.Default.Lock, "🔒 Encrypted", tint = Green)
            Text("E2EE aktiviert")
        }
        handshakeInProgress -> {
            CircularProgressIndicator()
            Text("E2EE wird eingerichtet... (Versuch ${retryCount}/5)")
            LinearProgressIndicator(progress = retryCount / 5f)
        }
        handshakeExhausted -> {
            Icon(Icons.Default.Warning, "⚠️", tint = Orange)
            Text("E2EE Handshake fehlgeschlagen")
            Button(onClick = { retryHandshake() }) {
                Text("Nochmal versuchen")
            }
        }
        else -> {
            Icon(Icons.Default.Lock, "🔓 Unencrypted", tint = Gray)
            Text("Warte auf Peer...")
        }
    }
}
```

---

### Phase 3c: Fehler-Logging STRUKTURIEREN
**In LogViewerScreen:**

```
[E2EE] Handshake für 493d14fa gestartet
  ├─ Transport: RELAY
  ├─ Handshake-Versuch: 1/5
  └─ Timeout: 20000ms

[TRANSPORT] Probe für INTERNET
  ├─ Ziel: 493d14fa
  ├─ Ergebnis: ❌ TIMEOUT nach 8000ms
  └─ Letzter erfolgreicher Kontakt: 2m ago

[RELAY] ERROR:unknown-command
  ├─ Letztes Kommando: SEND:...
  └─ Fix: Reconnecting...

[NETWORK] Netzwerkänderung erkannt
  ├─ Aktion: Capabilities broadcastet + Retry-Queue gestartet
  └─ Retry-Status: 2/10 Nachrichten versendet
```

---

## 🚀 Implementierungs-Roadmap

### Woche 1: E2EE-Handshake-Fix
- [ ] **E2-Phase 1a:** One-Sided Encryption implementieren
- [ ] **E2-Phase 1b:** Timeout-Logik mit Backoff
- [ ] **E2-Phase 1c:** Fallback-Verschlüsselung (optional, für Sicherheit)
- [ ] **Test:** Handshake-Szenarien (schnelles Netzwerk, langsames Netzwerk, Offline)

### Woche 2: Netzwerk-Robustheit
- [ ] **N2-Phase 2a:** Transport-Probing mit Ping/Pong
- [ ] **N2-Phase 2b:** Relay-Fehler-Diagnostik
- [ ] **N2-Phase 2c:** DHT-Discovery-Logik optimieren
- [ ] **Test:** Multi-Transport-Szenarien

### Woche 3: UI + Debugging
- [ ] **U3-Phase 3a:** Transport-Status in ChatList/ChatDetail
- [ ] **U3-Phase 3b:** Handshake-Status UI
- [ ] **U3-Phase 3c:** Strukturiertes Error-Logging
- [ ] **Test:** Fehlerszenarien visual debuggen

---

## 📊 Metriken für Erfolg

### E2EE-Handshake
- ✅ Handshake-Erfolgsrate: > 80%
- ✅ Durchschnittliche Handshake-Zeit: < 5s (über gutes Netzwerk)
- ✅ One-Sided Encryption: Nachrichten können während Handshake verschlüsselt werden

### Netzwerk
- ✅ Transport-Probes: > 70% erfolgreich
- ✅ Mindestens 1 Transport verfügbar: > 90% der Zeit
- ✅ Nachrichten-Zustellungsrate: > 95% (sobald Peer erreichbar ist)

### Robustheit
- ✅ App startet ohne Crashes: 100%
- ✅ Graceful Error Handling bei alle Fehlerszenarien
- ✅ User sieht immer klare Status-Info

---

## 🔍 Debugging-Checkliste

Wenn Handshake fehlschlägt:
1. ❓ Kann der Peer erreicht werden? → Transport-Probe prüfen
2. ❓ Erhält der Peer die Handshake-Nachricht? → Logs auf Empfänger-Seite prüfen
3. ❓ Antwortet der Peer? → ACK in Logs suchen
4. ❓ Timeout zu kurz? → Timeout erhöhen (Backoff aktiviert?)
5. ❓ Relay-Fehler? → Protokoll-Format prüfen

Wenn Netzwerk nicht funktioniert:
1. ❓ Welche Transporte sind verfügbar? → TransportManager-Status prüfen
2. ❓ DHT funktioniert? → "Peers für Topic gefunden" vs. "0 Peers"
3. ❓ Relay funktioniert? → WebSocket-Verbindung OK? → ERROR-Meldungen?
4. ❓ BLE funktioniert? → Scan findet Peers? → GATT-Verbindung OK?
5. ❓ STUN funktioniert? → NAT-Traversal möglich?

---

## 🎯 Nächste Schritte (JETZT)

1. **E2-Phase 1a starten:** One-Sided Encryption
   - Datei: `E2eeManager.kt`
   - Methode: `canEncryptMessage(peerId)` hinzufügen
   - Test mit QR-Code-Szenario

2. **Logging verbessern:** Bessere Fehler-Kontexte
   - `HandshakeRetryManager.kt`: Log-Details erweitern
   - `TransportManager.kt`: Probe-Details erweitern
   - `RelayTransport.kt`: Fehler-Diagnostik erweitern

3. **UI-Feedback:** Handshake-Status zeigen
   - `ChatDetailScreen.kt`: Handshake-Icon + Status
   - `ChatListScreen.kt`: Transport-Status Badge

---

## 📝 Technische Tiefenanalyse

### Warum funktioniert der Handshake nicht?

**Hypothese 1: Nachricht kommt nicht an**
```
Handy 2 sendet Handshake
    ↓
[RELAY/Internet/BLE?]
    ↓
Handy 1 empfängt... NICHT GESEHEN IM LOG
```
**Action:** Log-Punkt hinzufügen wenn Handshake empfangen wird

**Hypothese 2: Antwort kommt nicht zurück**
```
Handy 1 empfängt Handshake
    ↓
Generiert PreKeyMessage
    ↓
Versucht zu antworten... [Transport?]
    ↓
Handy 2 wartet... TIMEOUT
```
**Action:** Prüfe ob Handshake-Response gesendet wird

**Hypothese 3: Transport ist nicht verfügbar**
```
Handshake-Init auf RELAY
RELAY antwortet mit ERROR:unknown-command
    ↓
Retry auf INTERNET
INTERNET Probe schlägt fehl
    ↓
Retry auf DNS_TUNNEL
DNS_TUNNEL nicht erreichbar
    ↓
Retry auf BLE
BLE hat kein GATT-Connection
    ↓
TIMEOUT (alle Transporte failed)
```
**Action:** Transport-Fallback-Kette optimieren

---

## 💡 Quick-Wins

1. **Timeout erhöhen** (20s → 40s für erste Versuche)
2. **Handshake-Start-Nachrichten loggen** in CrisixApp.kt
3. **Transport-Status in UI** (grüne/rote Status-Icons)
4. **Retry-Button für Handshake** in Chat-Screen
5. **Ping-Pong für Transport-Health** (bereits implementiert ✅)

---

## 🔐 Sicherheits-Hinweise

- **One-Sided Encryption:** Ist sicher, solange Pre-KeyBundle authentifiziert ist
- **Fallback-Encryption:** Nur mit shared secret aus QR oder manueller Eingabe
- **Session-Storage:** Bleibt Hardware-backed verschlüsselt
- **Key-Rotation:** Bereits implementiert (täglich)

---

**Version:** 1.0  
**Datum:** 31.05.2026  
**Status:** 📋 Bereit für Implementierung
