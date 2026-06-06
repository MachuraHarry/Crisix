# Crisix Release Smoke-Test Checklist

> Vor jedem Release manuell durchgehen. Stand: nach Phasen 1-5 (E2EE-Schutz, Transport-Entkopplung, Race-Härtung, Observability, Migration).
>
> **Print-Version:** Diese Datei ist als Checkliste formatiert für direkten Druck.

## Setup

- [ ] **Geräte:** 2× Pixel oder vergleichbar (1× als „Client", 1× als „Server")
- [ ] **Build:** `app-debug.apk` mit den Phasen 1-5 Fixes installieren
- [ ] **Logging:** `adb logcat -s E2eeManager TransportManager` für Diagnose bereit
- [ ] **Plan-Referenz:** `docs/fallbacks-fixes.md` für Fix-Details

## Phase A — E2EE-Basis

- [ ] Pairing + Session-Aufbau (beide Geräte: `ACTIVE`-State)
- [ ] 10 Messages senden + empfangen (in-order, kein Decrypt-Fail)
- [ ] Session in Settings prüfen → `ACTIVE`, `lastUsedAt` aktualisiert

## Phase B — Transport-Fallback

- [ ] WLAN auf Client-Gerät deaktivieren
- [ ] 10 Messages senden → müssen alle ankommen, ggf. über RELAY/DNS
- [ ] Session-State nach 10 Messages → **bleibt `ACTIVE`** (kein COMPROMISED!)
- [ ] TransportBadge zeigt aktiven Transport + „🔒 Aktiv"
- [ ] WLAN wieder aktivieren
- [ ] 10 weitere Messages → müssen alle ankommen
- [ ] TransportMapper.lastSuccessfulTransport → WIFI_DIRECT

## Phase C — Race-Conditions

- [ ] Sende 20 Messages schnell hintereinander (innerhalb 5s)
- [ ] Alle kommen an, keine Duplikate (Cross-Transport-Dedup funktioniert)
- [ ] 5 Messages parallel über BLE + RELAY (LoRa-Setup) → kein Out-of-Order-BAD_DECRYPT

## Phase D — Handshake-Idempotenz

- [ ] Löse Handshake-Fehler aus (falsches PreKey-Bundle)
- [ ] 3× Retry-Versuche beobachten (Log: `HandshakeRetryManager.performRetry`)
- [ ] **Wichtig:** Selber `EK_A`/`EK_opk` wird 3× gesendet (idempotent)
- [ ] Beobachte: kein zusätzlicher OneTime-PreKey-Verbrauch

## Phase E — Persistenz

- [ ] App komplett neu starten auf **beiden** Geräten
- [ ] 10 Messages senden + empfangen → Session-State aus Persistenz korrekt?
- [ ] Session-Migration v1→v2 funktioniert (alte Session wird geladen)
- [ ] CrisixFeatures alle `true` nach Update (außer bei v2.4.x-Backport)

## Phase F — Multi-Transport

- [ ] Beide Geräte gleichzeitig über BLE + WiFi-Direct + Relay erreichbar
- [ ] Session bleibt `ACTIVE`, keine doppelten Messages
- [ ] OutOfOrderMessageHandler korrekt: 5 Messages out-of-order → alle entschlüsselbar
- [ ] SessionStateMachine `staleSince` = 0 (kein STALE-State)

## Phase G — ConnectionDiagnostics (Phase 4)

- [ ] InAppLogger zeigt strukturierte FallbackEvents
- [ ] ConnectionDiagnostics.snapshot() liefert Daten ohne Crash
- [ ] TransportBadge Komponente rendert in ChatDetailScreen

## Phase H — Soak (optional, 1h+)

- [ ] 1h automatischer Message-Send alle 30s
- [ ] Session bleibt `ACTIVE`, max 1 Re-Handshake
- [ ] Keine Memory-Leaks in LruHashMap (processedIncomingIds bounded 10k)

## Release-Go-Kriterium

**Alle Phase A-F Tests ✅** = Release freigegeben.

**Phase G-H** optional aber empfohlen für Production-Releases.

## Bei Fail

1. Logcat-Output sichern: `adb logcat -d > fail-$(date).log`
2. Session-State dumpen: `E2eeManager.dumpSessions()`
3. ConnectionDiagnostics.snapshot() für UI-Snapshot
4. Issue mit Phase/Fix-Referenz erstellen

## Automatisierte Tests (separat)

- `./gradlew :app:testDebugUnitTest` — 53 neue Tests + Baseline
- `./gradlew :app:connectedDebugAndroidTest` — 9 Compose-Tests auf Device
- `./gradlew :app:testDebugUnitTest --tests '*SoakTest*' -DrunSoakTests=true` — 3 Soak-Tests

## Verwandte Dokumentation

- `docs/fallbacks-fixes.md` — Vollständiger Plan mit Begründungen
- `fixes.md` — Separater UI-Plan (unverändert)
- `desktop-plan.md` — Crisix Desktop Port (separat)
