# Crisix 1.0 Release Plan

## Stabilität & Sicherheit

### 1. Silent-Exception-Catches protokollieren

**Betroffene Dateien:** `CrisixApp.kt`, `CrisixNavHost.kt`, `BleTransport.kt`, `DnsTunnelTransport.kt`, `WifiTransport.kt`, `RelayTransport.kt`, `DoubleRatchet.kt`, `MessageProcessor.kt`, `MessageRepository.kt`

**Problem:** 21 Stellen im Code fangen Exceptions mit `catch (_: Exception) { }` und verwerfen sie komplett. Im Feld auftretende Fehler sind damit unsichtbar und nicht debugbar.

**Maßnahme:** Jeden leeren Catch-Block durch `Timber.e(e, "Kontextbeschreibung")` ersetzen. Beispiele:
- `CrisixApp.kt:254` – `Timber.e(e, "Failed to initialize transports")`
- `BleTransport.kt:412` – `Timber.e(e, "BLE read characteristic failed")`
- `DnsTunnelTransport.kt:129` – `Timber.e(e, "DNS tunnel send failed")`

**Zeitaufwand:** ~1h

---

### 2. R8/ProGuard im Release aktivieren

**Betroffene Dateien:** `app/build.gradle.kts:58`, `app/proguard-rules.pro`

**Problem:** `isMinifyEnabled = false` in Release-Builds. Keine Minification, kein Shrinking, keine Obfuscation. Die APK ist unnötig groß und komplett lesbar.

**Maßnahme:**
1. `isMinifyEnabled = true` setzen
2. `proguard-rules.pro` mit tatsächlichen Regeln befüllen (statt Stock-Template):
   - Room-Entities und DAOs von Obfuscation ausschließen
   - BouncyCastle / Ed25519-Klassen erhalten
   - MessagePack-Klassen erhalten
   - Compose-/Navigation-Klassen erhalten
   - OkHttp/WebSocket-Klassen erhalten
3. Release-Build durchführen und auf `NoSuchMethodError`/`ClassNotFoundException` testen

**Zeitaufwand:** ~3h (inkl. Testen)

---

### 3. Crash Reporting einbauen

**Problem:** Kein Crash-Reporting-SDK im Projekt. Nach Release sind Abstürze im Feld komplett unsichtbar.

**Maßnahme:**
- **Empfehlung: Sentry** (Open-Source-freundlich, self-hostable, kein Google-Abhängigkeit)
- Alternativ: Firebase Crashlytics
- Integration:
  - Sentry-Gradle-Plugin + `sentry-android` SDK in `libs.versions.toml` aufnehmen
  - `Sentry.init()` in `MainActivity.onCreate()` oder `Application.onCreate()`
  - `Timber.plant(SentryTimberTree())` für automatisches Error-Reporting
  - Breadcrumbs für Navigation und Transport-Events

**Zeitaufwand:** ~2h

---

### 4. APK-Signaturprüfung beim Auto-Update

**Betroffene Datei:** `app/src/main/java/com/messenger/crisix/update/UpdateManager.kt`

**Problem:** Der UpdateManager lädt APKs von GitHub Releases herunter und installiert sie, ohne die APK-Signatur zu verifizieren. Ein kompromittiertes Release oder Man-in-the-Middle-Angriff würde unbemerkt eine manipulierte APK installieren.

**Maßnahme:**
1. Signatur-Hash des Crisix-Release-Keystores in der App hartkodieren
2. Vor der Installation die heruntergeladene APK per `PackageManager.getPackageArchiveInfo()` auf Signaturübereinstimmung prüfen
3. Bei Abweichung Update abbrechen und Nutzer warnen
4. GitHub-Release-Download nur über HTTPS mit Certificate Pinning (OkHttp `CertificatePinner`)

**Zeitaufwand:** ~2h

---

### 5. App-Backup-Regeln definieren

**Betroffene Dateien:** `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`

**Problem:** Beide XML-Dateien enthalten nur das Stock-Template mit allen Regeln auskommentiert. Android Auto-Backup würde potenziell die verschlüsselte Datenbank und SharedPreferences in die Cloud sichern – Sicherheitsrisiko.

**Maßnahme:**
- `backup_rules.xml`: `crisix.db`, `*.db-wal`, `*.db-shm` und SharedPreferences per `<exclude>` ausschließen
- `data_extraction_rules.xml`: Gleich verfahren für Gerät-zu-Gerät-Transfer
- Begründung: E2EE-Schlüssel und Kontaktdaten sollen das Gerät nie unverschlüsselt verlassen

**Zeitaufwand:** ~30min

---

## Dead Code & Altlasten

### 6. Leere Room-Migrationen entfernen

**Betroffene Datei:** `app/src/main/java/com/messenger/crisix/data/AppDatabase.kt`

**Problem:** Sieben Migrationen (1→2 bis 7→8) sind leere No-Ops (`override fun migrate(db) {}`). Nur Migration 8→9 enthält tatsächliches SQL (`ALTER TABLE ... ADD COLUMN disappearingTimerMs`). Das bläht den Code auf und suggeriert eine Migrationshistorie, die nie produktiv existierte.

**Maßnahme:**
- **Option A (empfohlen):** DB-Version auf 1 resetten – da die App nie im Play Store war, gibt es keine Nutzer mit alten DB-Versionen. Nur eine einzige Migration behalten (falls in Zukunft nötig) oder `fallbackToDestructiveMigration()` verwenden.
- **Option B:** Nur MIGRATION_8_9 behalten und als MIGRATION_1_2 umbenennen, alle anderen löschen.
- Achtung: Bestehende Entwickler-Installationen verlieren ihre lokalen Chats bei Option A.

**Zeitaufwand:** ~15min

---

### 7. `colors.xml` löschen

**Betroffene Datei:** `app/src/main/res/values/colors.xml`

**Problem:** Enthält 7 Farbressourcen (`purple_200`, `purple_500`, `purple_700`, `teal_200`, `teal_700`, `black`, `white`), die nirgendwo im Projekt referenziert werden. Kein `@color/`-Verweis und kein `R.color.*` in Kotlin. Die Compose-UI nutzt ausschließlich das in `Color.kt` definierte `NavyDarkColorScheme`.

**Maßnahme:** `colors.xml` ersatzlos löschen.

**Zeitaufwand:** ~1min

---

### 8. `info_version_value` dynamisieren

**Betroffene Dateien:** `app/src/main/res/values/strings.xml:139`, `app/src/main/res/values-en/strings.xml:139`

**Problem:** Beide Sprachvarianten zeigen hartkodiert `"1.0.0 (Phase 0)"`, obwohl die App bei Version 1.4 (`versionName` in `build.gradle.kts`) ist. Widersprüchlich und wartungsintensiv.

**Maßnahme:**
- String-Ressource entfernen
- Im Code `BuildConfig.VERSION_NAME` verwenden (z.B. `"${BuildConfig.VERSION_NAME} (Phase 1)"`)
- Oder String auf einen Platzhalter wie `"%s"` umstellen und per `String.format()` befüllen

**Zeitaufwand:** ~15min

---

### 9. `Contact.encryptedData`-Feld entfernen

**Betroffene Datei:** `app/src/main/java/com/messenger/crisix/data/Contact.kt`

**Problem:** Das Feld `encryptedData: String? = null` ist immer `null` und wird bei jeder Serialisierung als `JSONObject.NULL` mitgeschrieben. Reine Verschwendung – die geplante AES-256-GCM-Verschlüsselung existiert nicht.

**Maßnahme:**
- Feld, Getter/Setter, JSON-Serialisierung und Kommentar entfernen
- Wieder einbauen, sobald die Kontaktverschlüsselung tatsächlich implementiert wird

**Zeitaufwand:** ~10min

---

### 10. `themes.xml` bereinigen

**Betroffene Datei:** `app/src/main/res/values/themes.xml`

**Problem:** Referenziert `android:Theme.Material.Light.NoActionBar`, obwohl die App ein dunkles Material3-Compose-Theme verwendet. Das XML-Theme wird zur Laufzeit von Compose komplett überschrieben und hat keine Wirkung.

**Maßnahme:**
- Parent auf `android:Theme.Material3.Dark.NoActionBar` ändern (korrekte Dokumentation)
- Oder Datei ganz löschen, da Compose das Theme ohnehin via `CrisixTheme` composable setzt
- Letzteres nur, wenn das `<application android:theme>` im Manifest ebenfalls entfernt/angepasst wird

**Zeitaufwand:** ~5min

---

### 11. `supportsRtl="false"` setzen

**Betroffene Datei:** `app/src/main/AndroidManifest.xml:41`

**Problem:** `android:supportsRtl="true"` ist gesetzt, aber es existieren keine RTL-Layouts, keine `layout-ldrtl`-Ressourcen und keine arabischen/hebräischen Übersetzungen. Falsche Deklaration kann zu unerwartetem Layout-Verhalten auf RTL-Geräten führen.

**Maßnahme:** Auf `android:supportsRtl="false"` setzen, bis echte RTL-Unterstützung mit Übersetzungen und getesteten Layouts vorhanden ist.

**Zeitaufwand:** ~1min

---

## UI/UX & Accessibility

### 12. `contentDescription = null` bereinigen

**Betroffene Dateien:** `ChatDetailScreen.kt`, `ChatListScreen.kt`, `ConnectionsScreen.kt`, `ContactDetailScreen.kt`, `ContactListScreen.kt`, `MessageBubble.kt`, `SettingsScreen.kt`, `PermissionSetupScreen.kt`

**Problem:** 38 Stellen setzen `contentDescription = null` – darunter sind viele interaktive Elemente (Icons in Dropdown-Menüs, Chat-Avatare, Status-Indikatoren, Play/Pause-Buttons). Screenreader-Nutzer können die UI nicht bedienen.

**Maßnahme:**
- Interaktive Icons: aussagekräftige `contentDescription`-Strings vergeben
- Rein dekorative Icons: `contentDescription = null` ist hier korrekt, aber kommentieren warum
- Priorität: Buttons und klickbare Elemente zuerst, dann informative Icons, zuletzt rein dekorative

**Zeitaufwand:** ~1–2h

---

## Infrastruktur

### 13. CI/CD-Pipeline aufsetzen

**Problem:** Kein GitHub Actions Workflow, keine automatisierten Builds, keine Lint-/Test-Gates.

**Maßnahme:** `.github/workflows/ci.yml` erstellen mit:
- **Trigger:** Push auf `main`, Pull Requests
- **Jobs:**
  1. `lint`: `./gradlew lintDebug` – bricht Build bei Lint-Fehlern ab
  2. `test`: `./gradlew testDebug` – Unit-Tests ausführen
  3. `build`: `./gradlew assembleRelease` – signierten Release-Build erzeugen (Keystore per GitHub Secrets)
- Optional: `android_test`-Job mit Emulator für instrumentierte Tests

**Zeitaufwand:** ~2h

---

### 14. Testabdeckung erweitern

**Problem:** Nur 13 Testdateien – hauptsächlich Crypto-Layer und ChatListViewModel. Keine Tests für Transports, Repositories, Navigation, Messages oder UI-Screens.

**Maßnahme (priorisiert):**
1. **Repository-Tests:** `ContactRepository`, `MessageRepository` mit In-Memory-Room-DB testen
2. **Transport-Unit-Tests:** `TransportManager`, `WifiTransport`, `RelayTransport` mit gemockten Sockets
3. **UI-Smoke-Tests:** Mindestens `ComposeTestRule`-Test pro Screen, der prüft ob die UI rendert
4. **Integrationstest:** `MessageSender` → `MessageProcessor` Roundtrip mit echter E2EE
5. **Navigation-Tests:** Prüfen dass alle NavGraph-Routen erreichbar sind

**Zeitaufwand:** ~1–2 Tage

---

### 15. Disappearing-Message-Cleanup per WorkManager

**Problem:** Disappearing Messages werden nur über In-Memory-Timer (`CoroutineScope`) gesteuert. Wird die App vom System gekillt, laufen keine Timer mehr und abgelaufene Nachrichten bleiben in der DB.

**Maßnahme:**
- `WorkManager`-PeriodicTask einrichten (Intervall: 5 Minuten)
- Worker prüft `disappearingTimerMs` + `timestamp` aller Nachrichten in der DB
- Abgelaufene Nachrichten löschen und UI per `Flow`/`LiveData` aktualisieren
- `ForegroundService`-Keep-Alive allein reicht nicht, da Android den Service trotzdem killen kann (insbesondere auf neueren API-Leveln und bei Akkusparmaßnahmen)

**Zeitaufwand:** ~2–3h

---

## Zusammenfassung

| # | Maßnahme | Zeitaufwand | Kategorie |
|---|----------|-------------|-----------|
| 1 | Exception-Logging | ~1h | Stabilität |
| 2 | R8/ProGuard aktivieren | ~3h | Sicherheit |
| 3 | Crash Reporting (Sentry) | ~2h | Stabilität |
| 4 | APK-Signaturprüfung | ~2h | Sicherheit |
| 5 | Backup-Regeln definieren | ~30min | Sicherheit |
| 6 | Leere Migrationen entfernen | ~15min | Cleanup |
| 7 | `colors.xml` löschen | ~1min | Cleanup |
| 8 | Version dynamisch aus BuildConfig | ~15min | Cleanup |
| 9 | `encryptedData`-Feld entfernen | ~10min | Cleanup |
| 10 | `themes.xml` bereinigen | ~5min | Cleanup |
| 11 | `supportsRtl` korrigieren | ~1min | Cleanup |
| 12 | Accessibility-Descriptions | ~1–2h | UX |
| 13 | CI/CD-Pipeline | ~2h | Infrastruktur |
| 14 | Testabdeckung | 1–2 Tage | Infrastruktur |
| 15 | WorkManager Cleanup | ~2–3h | Stabilität |

**Geschätztes Minimum für 1.0: ~5 Tage** (Punkte 1–5, 8, 12, 13, 15)
**Vollständig: ~2–3 Wochen** (inkl. Testabdeckung)
