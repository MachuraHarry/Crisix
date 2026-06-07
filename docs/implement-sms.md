# Crisix — SMS-Transport Implementierungsplan

## 1. Ziel

SMS als **fünften Fallback-Transport** in Crisix aktivieren. Wenn alle anderen Transporte (WiFi Aware, WiFi Direct, BLE, Internet, Relay, DNS-Tunnel) nicht verfügbar sind, werden Nachrichten über das Mobilfunknetz per SMS zugestellt. Kein Internet nötig, riesige Reichweite (überall wo Mobilfunkempfang besteht).

---

## 2. Ist-Zustand

| Bereich | Status |
|---------|--------|
| `TransportType.SMS` | ✅ Im Enum vorhanden |
| `priorityOrder` in TransportManager | ✅ Position #6 (zwischen DNS_TUNNEL und LORA) |
| `enabledTransports`-Default | ✅ SMS standardmäßig deaktiviert |
| Circuit-Breaker | ✅ Für SMS deaktiviert (`isCircuitBreakerEnabled` returns `false`) |
| UI-Toggle | ✅ Vorhanden, aber auf `disabled` + "(Coming Soon)" |
| Strings (DE + EN) | ✅ Vorhanden, aber mit "(Coming Soon)" in der Beschreibung |
| Icon (`ic_sms.xml`) | ✅ Vorhanden |
| `SEND_SMS` / `RECEIVE_SMS` in Manifest | ❌ Fehlt |
| `SmsTransport.kt` | ❌ Existiert nicht |
| `SmsReceiver.kt` | ❌ Existiert nicht |

---

## 3. Architektur-Entscheidungen

### 3.1 Nachrichtenformat

**Text-SMS mit `CRSX:`-Prefix und Base64-Payload:**

```
CRSX:SGVsbG8gV29ybGQ=
        └─── Base64-codierte Binärdaten
    └─── Crisix-Erkennungspräfix
```

- `SmsManager.sendMultipartTextMessage()` splittet automatisch in mehrere SMS bei >160 Zeichen
- Empfänger: Prüft auf `CRSX:`-Prefix, extrahiert und decodiert Base64
- Multipart-Reassembly: Gleicher Absender + gleicher Timestamp → Teile konkatenieren

**Alternativ** (spätere Optimierung): Data-SMS über dedizierten Port (54232) via `sendDataMessage()` — sauberere Trennung von normalen SMS, aber aufwändigeres Multipart-Handling.

### 3.2 Peer-Identifikation

Die **Telefonnummer** ist die Peer-ID. Sender-Nummer wird aus der eingehenden SMS extrahiert. Keine Device-ID nötig — die SIM-Karte identifiziert das Gerät eindeutig.

### 3.3 Berechtigungen

| Permission | Zweck | API-Level |
|-----------|-------|-----------|
| `SEND_SMS` | SMS versenden | Runtime ab API 23 |
| `RECEIVE_SMS` | SMS empfangen & filtern | Runtime ab API 23 |

Beide sind **kostenpflichtige Permissions** (Google Play prüft, warum die App SMS-Zugriff braucht).

### 3.4 SMS-Receiver

**Manifest-registrierter `BroadcastReceiver`**, damit SMS auch bei geschlossener App empfangen werden:

```xml
<receiver android:name=".transport.SmsReceiver"
          android:exported="true"
          android:permission="android.permission.BROADCAST_SMS">
    <intent-filter>
        <action android:name="android.provider.Telephony.SMS_RECEIVED" />
        <action android:name="android.provider.Telephony.SMS_DELIVER" />
    </intent-filter>
</receiver>
```

`SmsReceiver` leitet Nachrichten via Callback an `SmsTransport` weiter.

### 3.5 Delivery-Tracking

SMS unterstützt nativ Delivery-Confirmation:
- `sentIntent`: Wird ausgelöst, wenn die SMS den SMSC erreicht hat
- `deliveryIntent`: Wird ausgelöst, wenn die SMS auf dem Zielgerät angekommen ist
- ACK: Empfänger sendet eine kurze Bestätigungs-SMS zurück (`CRSX:ACK:<msgId>`) für E2E-Bestätigung

---

## 4. Implementierungsdateien

### 4.1 Neue Dateien

| Datei | Zeilen | Beschreibung |
|-------|--------|-------------|
| `transport/SmsTransport.kt` | ~300 | Transport-Implementierung mit `SmsManager` |
| `transport/SmsReceiver.kt` | ~80 | `BroadcastReceiver` für eingehende SMS |

### 4.2 Zu ändernde Dateien

| Datei | Änderung |
|-------|----------|
| `AndroidManifest.xml` | `SEND_SMS` + `RECEIVE_SMS` Permissions, `SmsReceiver`-Registrierung |
| `util/PermissionManager.kt` | `smsPermissions()`, `hasSmsPermissions()` |
| `ui/screens/PermissionSetupScreen.kt` | SMS-Permission-Item |
| `transport/TransportInitializer.kt` | `SmsTransport` registrieren |
| `transport/TransportManager.kt` | ACK-Callback für SMS |
| `ui/screens/TransportSetupScreen.kt` | "Coming Soon" für SMS entfernen, Toggle aktivieren |
| `ui/screens/SettingsScreen.kt` | "Coming Soon" für SMS entfernen, Toggle aktivieren |
| `res/values/strings.xml` | `permission_sms_label`, `permission_sms_desc`, "(Coming Soon)" aus SMS-Desc entfernen |
| `res/values-en/strings.xml` | Gleiche Strings auf Englisch |

---

## 5. SmsTransport — Design

```kotlin
class SmsTransport(
    private val localPeerId: String,
    private val appContext: Context
) : Transport {
    override val type = TransportType.SMS
    override val capabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = 160,        // SMS-Begrenzung
        supportsImages = false,
        supportsVideo = false,
        supportsAudio = false,
        supportsFileTransfer = false,
        isMetered = true,           // SMS kostet Geld
        maxPayloadSize = 140,       // 140 bytes raw payload (Base64 = ~187 chars → 2 SMS bei >140)
        requiresProbing = false,
        supportsUiMessageIdSuffix = false,
    )
    
    private val prefix = "CRSX:"
    private var smsReceiver: SmsReceiver? = null
    private var phoneNumber: String? = null
    private val listeners = mutableListOf<(String, ByteArray) -> Unit>()
    private val multipartBuffer = ConcurrentHashMap<String, StringBuilder>()
    
    // Transport-Interface
    override fun start():      Permission prüfen → Receiver registrieren → Rufnummer ermitteln
    override fun stop():       Receiver deregistrieren → Buffer leeren
    override fun send():       Base64 → "CRSX:" + base64 → sendMultipartTextMessage()
    override fun isAvailable(): SIM vorhanden? + Permission erteilt?
    override fun registerListener(): Listener registrieren
    override fun discoverPeers(): Leer (keine SMS-Discovery)
    override fun getStatusDetail(): "SMS bereit" / "Keine SIM" / "Keine Berechtigung"
    
    // Interne Hilfsmethoden
    private fun encodeMessage(data: ByteArray): String
    private fun decodeMessage(text: String): ByteArray?
    private fun sendAck(phoneNumber: String, messageId: String)
    private fun handleIncomingMessage(senderPhone: String, payload: ByteArray)
}
```

### 5.1 `start()` — Detailablauf

```
1. Prüfe SEND_SMS + RECEIVE_SMS Permissions
   ↓ fehlt → Log-Warnung, return (Transport gestoppt)
2. Ermittle eigene Rufnummer via TelephonyManager
   ↓ null → Log-Warnung (keine SIM)
3. Erstelle SmsReceiver-Instanz mit Callback
4. Registriere Receiver dynamisch:
   context.registerReceiver(receiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
5. Setze isRunning = true
```

### 5.2 `send(peerId, data)` — Detailablauf

```
1. Prüfe ob peerId wie Telefonnummer aussieht (+49...)
2. Base64-codiere data → base64Payload
3. Präfix "CRSX:" + base64Payload → smsText
4. Falls smsText.length > 160:
   - SmsManager.divideMessage(smsText) → parts
   - SmsManager.sendMultipartTextMessage(peerId, null, parts, sentIntents, deliveryIntents)
   Sonst:
   - SmsManager.sendTextMessage(peerId, null, smsText, sentIntent, deliveryIntent)
5. sentIntent → bei Erfolg: Result.success(Unit)
   sentIntent → bei Fehler: Result.failure(...)
6. deliveryIntent → bei Erfolg: onDeliveryAck(messageId)
```

### 5.3 `SmsReceiver.onReceive()` — Detailablauf

```
1. Extrahiere SMS-PDUs aus Intent: Telephony.Sms.Intents.getMessagesFromIntent(intent)
2. Für jede SMS:
   a. Kombiniere Multipart-Teile anhand origAddress + timestamp
   b. Prüfe ob Nachricht mit "CRSX:" beginnt
      ↓ nein → ignoriere (keine Crisix-Nachricht)
   c. Extrahiere Base64-Payload nach "CRSX:"
   d. decodiere Base64 → rawBytes
   e. Sende ACK: "CRSX:ACK:<messageId>" zurück an Absender
   f. Leite rawBytes an Transport-Listener weiter
3. Abbruch (Broadcast-Receiver-Limit von 10s nicht überschreiten)
```

---

## 6. Permission-Integration

### 6.1 PermissionManager

```kotlin
fun smsPermissions(): Array<String> = arrayOf(
    Manifest.permission.SEND_SMS,
    Manifest.permission.RECEIVE_SMS,
)

fun hasSmsPermissions(context: Context): Boolean =
    checkAll(context, *smsPermissions())
```

### 6.2 PermissionSetupScreen

SMS-Permission-Item **immer anzeigen** (nicht konditional wie NAN, da jedes Telefon SMS kann):

```kotlin
PermissionItem(
    icon = R.drawable.ic_sms,
    label = stringResource(R.string.permission_sms_label),
    description = stringResource(R.string.permission_sms_desc),
    granted = smsGranted
)
```

In den sequentiellen Permission-Flow einbauen (neuer Schritt nach NAN).

### 6.3 Kosten-Hinweis

Im Permission-Dialog und in der Transport-Beschreibung klar kommunizieren:

> **SMS-Kosten können anfallen!** Nachrichten werden als SMS über das Mobilfunknetz gesendet. Je nach Tarif entstehen Kosten pro Nachricht.

---

## 7. UI-Änderungen

### 7.1 `SettingsScreen.kt` und `TransportSetupScreen.kt`

```diff
- val isComingSoon = transportType == TransportType.SMS || transportType == TransportType.LORA
+ val isComingSoon = transportType == TransportType.LORA
```

Toggle für SMS wird aktiv, "(Coming Soon)"-Text entfällt.

### 7.2 Strings

```diff
- <string name="transport_sms_desc">Nur Text, max. 160 Zeichen – Kosten können anfallen (Coming Soon)</string>
+ <string name="transport_sms_desc">Nur Text, max. 160 Zeichen – Kosten können anfallen</string>
+ <string name="permission_sms_label">SMS</string>
+ <string name="permission_sms_desc">Für Nachrichten über das Mobilfunknetz (Kosten laut Tarif)</string>
```

---

## 8. TransportManager-Integration

SMS ist bereits gut integriert, nur zwei Ergänzungen nötig:

```kotlin
// In registerTransport():
if (transport is SmsTransport) {
    transport.onDeliveryAck = { messageId, peerId ->
        _deliveryUpdates.tryEmit(DeliveryUpdate(
            uiMessageId = messageId,
            peerId = peerId,
            status = MessageStatus.DELIVERED,
            transport = TransportType.SMS
        ))
    }
}
```

Circuit-Breaker bleibt für SMS deaktiviert (SMS-Empfang ist unzuverlässig, Retries sind sinnvoll).

---

## 9. Tests

| Test | Typ | Beschreibung |
|------|-----|-------------|
| `SmsTransportTest` | Unit | Encoding/Decoding, Prefix-Erkennung, Multipart-Reassembly |
| `SmsReceiverTest` | Unit | Intent-Parsing, Filter-Logik |
| `SmsIntegrationTest` | Instr. | E2E mit Mock-SmsManager (optional) |

---

## 10. Phasing & Zeitaufwand

| Phase | Inhalt | Aufwand |
|-------|--------|---------|
| **1** | `SmsTransport.kt` + `SmsReceiver.kt` Grundgerüst | ~3h |
| **2** | Permissions + Manifest + PermissionManager | ~1h |
| **3** | PermissionSetupScreen + UI-Integration | ~1.5h |
| **4** | TransportManager-Integration + ACK-Callback | ~0.5h |
| **5** | Strings (DE + EN) | ~0.5h |
| **6** | Tests | ~2h |
| | **Gesamt** | **~8.5h** |

---

## 11. Risiken & Fallstricke

| Risiko | Maßnahme |
|--------|----------|
| **Google Play lehnt SMS-Permissions ab** | Berechtigungserklärung im Play Console ausfüllen. Kernfunktion: Krisen-Messenger ohne Internet. Valid Use Case. |
| **SMS kostet Geld (user unaware)** | Klarer Hinweis in Permission-Dialog und Transport-Beschreibung. Optional: SMS-Warnung vor erstem Send. |
| **SMS-Delivery unzuverlässig** | Circuit-Breaker deaktiviert lassen. Retry-Queue aktiv. ACK-Mechanismus für Delivery-Confirmation. |
| **SMS-Verzögerung (mehrere Sekunden)** | Akzeptiert. SMS ist bewusst der letzte Fallback mit höchster Reichweite. User sieht Transport-Badge "SMS" und weiß es kann dauern. |
| **SMS-Empfang ohne App-Start** | Manifest-registrierter Receiver fängt SMS auch bei geschlossener App. Erfordert RECEIVE_SMS-Permission und BROADCAST_SMS. |
| **Multipart-SMS nicht als eine Nachricht erkannt** | Reassembly via origAddress + timestamp im SmsReceiver. Buffer mit Timeout (30s). |
| **Telefonnummer als Peer-ID ändert sich** | Selten (nur bei SIM-Wechsel). User muss ggf. Peer-ID im Kontakt aktualisieren. |
