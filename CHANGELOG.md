# Changelog

## [1.5] – 2026-05-26

### Added
- Fallback-Rewrite: Transport-Entkopplung, adaptives Fallback
- Health & Observability (ConnectionDiagnostics, TransportScorer)
- SessionTransportMapper, CoalescedReconnectScheduler
- 53 neue Tests (JVM + Compose)
- TransportBadge UI-Komponente
- Live-Smoke auf Pixel 9 + OnePlus 8 Pro validiert

### Fixed
- Session-Version-Mismatch (E2eeManager sync)
- Session-Recovery nach Reinstall (HandshakeOrch)
- Auto-Scroll im Chat (3-Effect-Rewrite)
- Responder akzeptiert neue Handshakes für non-ACTIVE Sessions

### Changed
- 18 Einzel-Commits gesquasht, Historie bereinigt
- 60MB APK aus Git-Historie entfernt

## [1.4] – 2026-06-01

### Fixed
- Semver-Tags für VersionCode-Parsing
- Certificate Pinning (Intermediate CA Fallback)

### Added
- Release-Konfiguration mit Keystore-Signierung
- Auto-Update-Funktion via GitHub

## [1.0.0-alpha.1] – 2026-05-26

### Added
- Foreground Service für Hintergrund-Kommunikation
- Paging 3 für Chat-Nachrichten
- Notification-Infrastruktur
- Initiale Projektstruktur
