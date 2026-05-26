📱 Crisix – Umfassender technischer & gestalterischer Plan

Ein adaptiver, serverloser Messenger für alle Netzwerksituationen
Version 4.0 – Ausgearbeitet für Harry Machura

---

🧭 1. Vision & Kernkonzept der App

1.1 Was ist Crisix?

Crisix ist ein multimodaler, dezentraler Messenger für Android, der im Normalbetrieb wie ein gewöhnlicher Chat funktioniert (Internet via WLAN/Mobilfunk), aber bei Ausfall des Internets automatisch auf alternative Transportwege umschaltet: Bluetooth Mesh, Wi-Fi Direct, SMS, DNS-Tunneling und optional LoRa (über externes Modul). Der Wechsel erfolgt für den Nutzer nahezu unsichtbar, mit einer intelligenten Transport-Abstraktion.

1.2 Vision

“Ein Messenger, der immer eine Möglichkeit findet – egal ob im Krisengebiet, auf dem Berg ohne Mobilfunk oder im überlasteten Netz.”

Crisix soll die Lücke zwischen alltäglichen Messenger-Apps (WhatsApp, Signal) und reinen Offline-Messengern (Briar, BitChat) schließen. Er kombiniert die gewohnte Benutzeroberfläche mit einer robusten, transport-agnostischen Architektur. Wichtig: Je nach verfügbarem Transport werden bestimmte Funktionen (Bilder, Videos, Sprachnachrichten) automatisch ein- oder ausgeblendet, um die Übertragung nicht zu gefährden.

1.3 Betriebsmodi & automatische Feature-Anpassung

Transportweg Typische Reichweite Unterstützte Inhalte Automatische Anpassung
Internet (libp2p) Weltweit Text, Bilder, Videos, Sprachnachrichten Alle Funktionen aktiv
Wi-Fi Direct bis 200 m Text, Bilder, kleinere Videos Text + Bilder (optional)
Bluetooth Mesh bis 100 m (Mesh >1 km mit vielen Knoten) Nur Text (max. 500 Bytes pro Hop) Medien deaktiviert
SMS Überall (Mobilfunk) Nur Text (160 Zeichen limitiert) Medien deaktiviert, Text auf 160 Zeichen gekürzt
DNS-Tunneling Weltweit (langsam) Nur Kurztext (max. 200 Zeichen) Medien deaktiviert, Zeichenlimit
LoRa (extern) bis 5 km (Feld) Nur Text (max. 200 Bytes) Medien deaktiviert

Umsetzung in der App:
Der TransportManager liefert der UI ein Capability-Objekt (supportsText, supportsImages, supportsAudio, maxMessageLength). Die UI (z.B. der Eingabebereich im ChatDetailScreen) blendet entsprechend den „Anhang“-Button, das Mikrofon-Icon oder den Senden-Button (bei Überschreitung der Länge) aus bzw. deaktiviert diese.

---

🎨 2. UI/UX-Design – WhatsApp-ähnlich, Navy Blue Dark Mode

(Farbschema und Layout bleiben wie im vorherigen Plan, siehe Abschnitt 1.1 und 1.2 – nur geringfügige Anpassungen)

· Zusätzliche UI-Elemente:
  · Im ChatDetailScreen wird oberhalb der Eingabezeile ein Capability-Hinweis eingeblendet, wenn der aktive Transport eingeschränkt ist: z.B. „Nur Text – Bilder deaktiviert (BLE-Modus)“.
  · Der „Anhang“-Button (📎) und das Mikrofon-Icon (🎤) werden ausgegraut und nicht klickbar, sobald der aktive Transport keine Medien unterstützt.
  · Bei SMS oder DNS-Tunnel erscheint zusätzlich ein Zeichenzähler (z.B. 140/160).
· Einstellungen: Der Benutzer kann für jeden Transport festlegen, ob er automatisch genutzt werden darf (z.B. SMS nur nach Bestätigung, LoRa nur, wenn Dongle verbunden).

---

🧱 3. Systemarchitektur (aktualisiert)

3.1 Capability-Aware Transport Abstraktion

Erweiterung des Transport-Interfaces:

```kotlin
data class TransportCapabilities(
    val supportsText: Boolean = true,
    val maxTextLength: Int = Int.MAX_VALUE,
    val supportsImages: Boolean = false,
    val supportsVideo: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsFileTransfer: Boolean = false,
    val isMetered: Boolean = false  // z.B. SMS kostet Geld
)

interface Transport {
    val type: TransportType
    val capabilities: TransportCapabilities
    suspend fun isAvailable(): Boolean
    suspend fun send(peerId: String, data: ByteArray): Result
    fun registerListener(listener: (PeerId, ByteArray) -> Unit)
    fun discoverPeers(): Flow<Peer>
    suspend fun start()
    suspend fun stop()
}
```

3.2 Message Processor mit Content-Typ-Adaption

Bevor eine Nachricht gesendet wird, prüft der SendMessageUseCase die Fähigkeiten des aktuell besten Transports. Wenn der Nutzer versucht, ein Bild zu senden, während nur BLE verfügbar ist, erscheint ein Toast: „Bilder können über BLE nicht gesendet werden. Aktiviere WLAN oder warte auf bessere Verbindung.“ Die Nachricht wird in die Warteschlange gelegt und später mit einem geeigneten Transport zugestellt.

3.3 Datenbank & Offline-Queue

· Die Message-Entity erhält ein neues Feld requiredCapabilities: String? (z.B. "IMAGE", "VIDEO").
· Der MessageRetryWorker sucht periodisch nach einem Transport, der die erforderlichen Fähigkeiten erfüllt.

---

🔌 4. Detaillierte Transport-Implementierung mit Einschränkungen

4.1 Bluetooth Transport (BLE)

· Capabilities: Nur Text, max. 500 Bytes pro Fragment (kann auf mehrere Pakete aufgeteilt werden).
· Umsetzung: Fragmentierung grüner Nachrichten; keine automatische Wiederholung bei Fehlern (Anwendungsebene).
· UI: Eingabefeld begrenzt auf 500 Zeichen (UTF-8). Bei Überschreitung Warnung.

4.2 SMS Transport

· Capabilities: Nur Text, max. 160 Zeichen (oder 70 bei Unicode).
· Versand: Base64-Kodierung des Chiffrats (reduziert verfügbare Zeichen). Daher wird der Klartext vor Verschlüsselung auf 100 Zeichen begrenzt.
· UI: Zeichenzähler, Hinweis auf Kosten.

4.3 DNS-Tunnel Transport

· Capabilities: Nur sehr kurze Textnachrichten (max. 200 Zeichen im Klartext nach Kodierung).
· Technik: Base32-Kodierung, Aufteilung auf mehrere Subdomain-Labels.
· UI: Hinweis auf experimentellen Charakter, Zeichenlimit.

4.4 LoRa Transport (externer ESP32)

· Capabilities: Nur Text, max. 200 Bytes (nach Verschlüsselung etwa 150 Zeichen Klartext).
· Implementierung: BLE-Verbindung zum ESP32; Senden einer Nachricht dauert 1-2 Sekunden.
· UI: Zeigt verbundenen Dongle an, deaktiviert Medien.

4.5 Internet Transport (libp2p) / Wi-Fi Direct

· Volle Unterstützung aller Medien (Bilder, Videos, Sprachnachrichten).
· Bilder werden vor Versand komprimiert (max. 1 MB). Videos auf 10 MB begrenzt.

---

🗺️ 5. Roadmap mit Phasen (unter Berücksichtigung der adaptiven UI)

Phase 0: Grundgerüst & Navy Blue UI (1 Woche)

· Projekt, Theme, Navigation, ChatList/ChatDetail Grundstruktur.
· Dummy-Transport mit Capabilities (z.B. „Internet“ voll).
· Eingabebereich passt sich dynamisch an Capabilities an (Anhang-Button ausgrauen).

Phase 1: Internet-P2P + Capability-Aware UI (3 Wochen)

· libp2p + Noise.
· TransportManager wählt Internet-Transport (höchste Priorität).
· UI: Zeigt aktuellen Transport in TopBar. Medien-Buttons aktiv.

Phase 2: BLE Mesh (2 Wochen)

· BLE Discovery & GATT.
· TransportCapabilities für BLE setzen (nur Text, max 500 Bytes).
· UI: Eingabefeld begrenzt, Anhang-Button deaktiviert, Hinweis anzeigen.

Phase 3: Wi-Fi Direct (2 Wochen)

· Hohe Bandbreite, volle Capabilities.
· TransportManager wählt Wi-Fi Direct, falls verfügbar (Priorität nach Internet).

Phase 4: SMS-Fallback (1 Woche)

· SMS mit Capabilities (Text, max 160 Zeichen, metered).
· Dialog vor erstmaliger Nutzung.
· UI: Zeichenzähler, Kosten-Hinweis.

Phase 5: DNS-Tunnel (experimentell) (2 Wochen)

· Eigener Server, Base32-Kodierung.
· Capabilities: Text, max 200 Zeichen.
· UI: Deaktiviert per Default, manuell aktivierbar mit Warnung.

Phase 6: LoRa-Plugin (optional, 3 Wochen)

· ESP32 Firmware + BLE.
· Capabilities: Text, max 150 Zeichen.
· UI: Erkennung des Dongles, separates Icon.

Phase 7: Intelligenter Handover & Medien-Queue (2 Wochen)

· Nachrichten mit Medienanforderungen (IMAGE, VIDEO) werden zurückgestellt, bis ein geeigneter Transport verfügbar ist.
· UI zeigt in der Chatliste einen „Ausstehend“-Indikator.

Phase 8: Beta & Sicherheitsaudit (2 Wochen)

· Abschlussarbeiten, Test auf echten Geräten, Open-Source-Release.

---

📚 6. Bibliotheken & Tools (unverändert)

(siehe vorherige Liste, ergänzt um Coil für Bildladung)

---

📁 7. Projektstruktur (Crisix-spezifisch)

```
app/src/main/java/com/crisix/messenger/
├── ui/
│   ├── theme/                 (NavyBlue Dark Theme)
│   ├── components/            (AdaptiveInputBar, CapabilityBadge)
│   ├── screens/...
├── transport/
│   ├── Transport.kt (mit Capabilities)
│   ├── BluetoothTransport.kt  (setzt capabilities = nur Text)
│   ├── SmsTransport.kt        (setzt metered = true)
│   └── ...
├── domain/
│   └── usecase/SendMessageUseCase (prüft Capabilities)
└── ...
```

---

✅ 8. Abschließende Hinweise

· Die automatische Deaktivierung von Medien bei eingeschränkten Transporten ist ein zentrales Alleinstellungsmerkmal von Crisix. Implementiere die Capability-Prüfung direkt im SendMessageUseCase und spiegle sie in der UI durch ViewModel-States.
· Teste jeden Transport mit zwei Geräten unter realen Bedingungen (z.B. BLE im Wald, SMS mit Prepaid-Karte).
· Die Roadmap ist so aufgebaut, dass du nach Phase 1 bereits eine nutzbare App mit Internet-Chat hast – die erweiterten Transporte kommen Schritt für Schritt.

Viel Erfolg bei der Umsetzung von Crisix! Bei Detailfragen zu einzelnen Transporten oder der Capability-UI stehe ich mit Code-Beispielen bereit.