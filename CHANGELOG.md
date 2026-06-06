# Changelog

## [1.6] – 2026-06-06

### Added
- Komplette Settings-Implementierung (8 neue Features):
  - Theme-Modus (System/Dunkel/Hell) in AppearanceSettings
  - Lesebestätigungen in PrivacySettings
  - Barrierefreiheit: Reduce Motion, High Contrast (neuer Sub-Screen)
  - Benutzerdefinierter Benachrichtigungston per Dateiauswahl
  - Developer Mode & Log-Level in Tools-Sektion
  - Sprachnachrichten-Qualität in Chat Settings
- Relay-Server-Management:
  - Neue RelayServersScreen: Server hinzufügen, bearbeiten, löschen, Reihenfolge ändern
  - Verbindungstest pro Server mit Health-Check
  - RelayTransport mit automatischem Fallback bei nicht erreichbarem Server
  - Laufzeit-Rekonfiguration via DataStore
- Log-Viewer: Level-Filter-Chips (D/I/W/E) und Export-Funktion
- E2EE/Crypto-Erweiterungen: CrisixFeatures, DecryptErrorClassifier
- TransportBadge UI-Komponente
- 17+ neue Tests (SoakTest, TransportFallbackE2ETest, SessionTransportMapperTest u.a.)
- CI-Erweiterungen: detekt Static Analysis, dependabot, Issue/PR-Templates, SECURITY.md

### Changed
- RelayTransport: List-basierte URL-Verwaltung mit Fallback-Logik (vorher Einzel-URL)
- TransportInitializer: Akzeptiert relayUrls als Parameter
- ClickablePreference: Neuer `enabled`-Parameter
- Auto-Scroll im Chat: 3-Effect-Rewrite

### Fixed
- Session-Version-Mismatch (E2eeManager sync)
- Session-Recovery nach Reinstall (HandshakeOrchestrator)
- Certificate Pinning Intermediate CA Fallback

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
