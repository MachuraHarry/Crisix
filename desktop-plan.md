# Crisix Desktop Port — Technischer Implementierungsplan

## Ziel

Crisix als native Desktop-App für **Windows (10/11)** und **Linux (x64)** verfügbar machen. Die App fungiert als vollwertiger Knoten im Crisix-Netzwerk mit E2EE, P2P/DHT, DNS-Tunnel und Relay-Unterstützung. Android-spezifische Transporte (BLE, WiFi Direct, SMS, LoRa) entfallen auf dem Desktop.

---

## Architekturübersicht

```
Crisix/                                   ← Git-Monorepo (bestehend)
│
├── build.gradle.kts                      ← → compose-desktop Plugin hinzu
├── settings.gradle.kts                    ← → Module crisix-core + desktop registrieren
├── gradle/libs.versions.toml             ← → Version Catalog erweitern
│
├── crisix-core/                          ← NEU: Pure Kotlin/JVM, 0 Android-Deps
│   └── src/main/kotlin/com/messenger/crisix/core/
│       ├── crypto/          (← aus app/crypto/ verschoben)
│       ├── transport/       (← reine Transporte: InternetP2P, Relay, DNS-Tunnel)
│       │   └── internet/    (← DHT, Libp2p, CryptoHelper, Protobuf)
│       ├── message/         (← MessageProcessor, MessageSender)
│       ├── model/           (← Entities: Chat, Message, Contact — reine Data Classes)
│       ├── protocol/        (← TLV Wire Protocol, Protobuf-Definitionen)
│       └── util/            (← DateGrouper, ImageCompressor als Interface)
│
├── app/                                ← Bestehendes Android-Projekt (abhängig von crisix-core)
│   └── (BLE, WiFi Direct, Room, CameraX, Biometric, WorkManager bleiben hier)
│
├── desktop/                            ← NEU: Compose Desktop App
│   └── src/main/kotlin/com/messenger/crisix/desktop/
│       ├── Main.kt                     (fun main() = application { ... })
│       ├── CrisixAppState.kt           (Globaler App-Zustand — ersetzt CrisixApp.kt Logik)
│       ├── platform/
│       │   ├── DatabaseFactory.kt      (SQLDelight-Treiber: Dateibasiert)
│       │   ├── SettingsStore.kt        (java.util.prefs → expect/actual)
│       │   ├── SystemTrayManager.kt    (java.awt.SystemTray)
│       │   ├── QrScanner.kt           (Webcam-Capture + ZXing)
│       │   ├── Notifications.kt        (OS-native via java.awt / D-Bus)
│       │   ├── FilePicker.kt           (javafx.stage.FileChooser)
│       │   └── NetworkMonitor.kt       (java.net.NetworkInterface Polling)
│       └── ui/
│           ├── CrisixWindow.kt         (Fenster + System-Tray + MenuBar)
│           ├── CrisixNavHost.kt        (Navigation — adaptiert von Android)
│           ├── theme/
│           │   └── NavyTheme.kt        (1:1 aus app Color.kt)
│           └── screens/
│               ├── ChatListScreen.kt   (adaptiert — Split-Layout)
│               ├── ChatDetailScreen.kt (adaptiert)
│               ├── ContactListScreen.kt
│               ├── ContactDetailScreen.kt
│               ├── AddContactScreen.kt
│               ├── MyIdScreen.kt       (QR-Code via ZXing)
│               ├── SettingsScreen.kt   (adaptiert)
│               ├── ConnectionsScreen.kt
│               ├── LogViewerScreen.kt
│               └── QrScannerScreen.kt  (Webcam-basiert)
│
├── crisix-core/build.gradle.kts        ← Pure Kotlin/JVM-Library
├── desktop/build.gradle.kts            ← Compose Desktop App
│
├── dns-tunnel-server/                  ← Unverändert
└── macrobenchmark/                     ← Unverändert (nur Android)
```

---

## Phase 1: `crisix-core` Modul (2–3 Tage)

### 1.1 Modul anlegen

```kotlin
// crisix-core/build.gradle.kts
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(23)          // JDK 23 für jpackage
}

dependencies {
    // Crypto (reines JVM)
    implementation("org.bouncycastle:bcprov-jdk15to18:1.77")

    // Networking (ersetzt OkHttp — Ktor ist KMP-kompatibel)
    implementation("io.ktor:ktor-client-core:3.1.0")
    implementation("io.ktor:ktor-client-cio:3.1.0")        // JVM engine
    implementation("io.ktor:ktor-client-websockets:3.1.0")

    // Serialization
    implementation("org.msgpack:msgpack-core:0.9.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Logging (ersetzt Timber)
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation(kotlin("test"))
}
```

### 1.2 Verschiebungs-Matrix aus `app/` → `crisix-core/`

| Aus `app/src/main/java/com/messenger/crisix/` | Nach `crisix-core/src/main/kotlin/com/messenger/crisix/core/` |
|---|---|
| `crypto/` → alle 11 Dateien | `crypto/` |
| `transport/Transport.kt` | `transport/Transport.kt` |
| `transport/TransportManager.kt` | `transport/TransportManager.kt` |
| `transport/MessageFragmenter.kt` | `transport/MessageFragmenter.kt` |
| `transport/Defragmenter.kt` | `transport/Defragmenter.kt` |
| `transport/RelayTransport.kt` | `transport/RelayTransport.kt` |
| `transport/DnsTunnelTransport.kt` | `transport/DnsTunnelTransport.kt` |
| `transport/internet/` → alle Dateien | `transport/internet/` |
| `message/MessageProcessor.kt` | `message/MessageProcessor.kt` |
| `message/MessageSender.kt` | `message/MessageSender.kt` |
| `data/ChatEntity.kt` | `model/ChatEntity.kt` |
| `data/MessageEntity.kt` | `model/MessageEntity.kt` |
| `data/PendingMessageEntity.kt` | `model/PendingMessageEntity.kt` |
| `data/Contact.kt` | `model/Contact.kt` |

### 1.3 Änderungen in den verschobenen Dateien

| Datei | Änderung |
|---|---|
| `TransportManager.kt` | `android.content.Context` → eigenes `NetworkStateProvider`-Interface; `android.util.Log` → `org.slf4j.Logger`; `android.util.Base64` → `java.util.Base64`; `ConnectivityManager` → `NetworkMonitor` (expect/actual) |
| `RelayTransport.kt` | `okhttp3` → `io.ktor.client` (WebSocket); `android.util.Log` → SLF4J |
| `DnsTunnelTransport.kt` | `android.util.Log` → SLF4J; `java.net.DatagramSocket` bleibt |
| `InternetTransport.kt` | `android.content.Context` → entfernen; benötigt nur `File`-Pfad für Key-Storage |
| Alle `transport/internet/*.kt` | `android.util.Log` → SLF4J; `java.net.*` APIs bleiben identisch |
| `crypto/` alle Dateien | **Keine Änderungen** — bereits reines Kotlin/JVM mit BouncyCastle |
| `MessageProcessor.kt` | `android.util.Base64` → `java.util.Base64` |

### 1.4 Neues Interface: `NetworkStateProvider`

```kotlin
// crisix-core/.../transport/NetworkStateProvider.kt
interface NetworkStateProvider {
    val isNetworkAvailable: Boolean
    fun addListener(listener: (Boolean) -> Unit)
    fun removeListener(listener: (Boolean) -> Unit)
}
```

- **Android**: Implementiert mit `ConnectivityManager.NetworkCallback`
- **Desktop**: Polled `java.net.NetworkInterface` alle 5 Sekunden

---

## Phase 2: Compose Desktop App (4–6 Tage)

### 2.1 Build-Konfiguration

```kotlin
// desktop/build.gradle.kts
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(23)
}

dependencies {
    implementation(project(":crisix-core"))

    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // Navigation (Compose Multiplatform)
    implementation("org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha10")

    // Lifecycle ViewModel
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // SQLDelight (Dateibasierte DB)
    implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")  // natives SQLite

    // QR-Code (ZXing — bereits im Projekt)
    implementation("com.google.zxing:core:3.5.3")

    // Webcam-Capture für QR-Scanner
    implementation("net.sarazan:webcam-capture:0.3.5")

    // Coil für Bild-Laden
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-svg:3.0.4")

    // Markdown Rendering
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Sentry
    implementation("io.sentry:sentry:7.18.0")
}

compose.desktop {
    application {
        mainClass = "com.messenger.crisix.desktop.MainKt"

        nativeDistributions {
            targetFormats(
                TargetFormat.Deb,     // Linux .deb
                TargetFormat.Rpm,     // Linux .rpm
                TargetFormat.Msi      // Windows Installer
            )
            packageName = "Crisix"
            packageVersion = "1.5.0"

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "Crisix"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}
```

### 2.2 Einstiegspunkt

```kotlin
// desktop/.../Main.kt
package com.messenger.crisix.desktop

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val state = rememberWindowState(
        size = DpSize(1200.dp, 800.dp),
        position = WindowPosition(Alignment.Center)
    )

    Window(
        onCloseRequest = {
            // In System Tray minimieren statt beenden
            TrayManager.minimizeToTray()
        },
        state = state,
        title = "Crisix",
    ) {
        val appState = rememberCrisixAppState()
        CrisixWindow(appState)
    }
}
```

### 2.3 UI-Adaptierung: Android → Desktop

#### Fenster-Layout (Split-Panel)

Auf dem Desktop wird die große Bildschirmfläche genutzt — Chat-Liste und Chat-Detail in einem Split-Layout (wie Telegram Desktop):

```
┌──────────────────────────────────────────────────────────┐
│ MenuBar: Datei | Bearbeiten | Ansicht | Hilfe            │
├──────────────┬───────────────────────────────────────────┤
│ Chat-Liste   │ Chat-Detail (aktiver Chat)                │
│ (300dp)      │                                           │
│              │  ┌─────────────────────────────────────┐  │
│ [Kontakt 1]  │  │ Nachrichten-Verlauf                 │  │
│ [Kontakt 2]  │  │                                     │  │
│ [Kontakt 3]  │  │                                     │  │
│              │  └─────────────────────────────────────┘  │
│              │  ┌─────────────────────────────────────┐  │
│              │  │ Eingabefeld                    [➤]  │  │
│              │  └─────────────────────────────────────┘  │
├──────────────┴───────────────────────────────────────────┤
│ Statusleiste: 🟢 Internet P2P (8 Peers) | 🔵 DNS-Tunnel │
└──────────────────────────────────────────────────────────┘
```

#### Änderungen pro Screen

| Screen | Android | Desktop | Änderung |
|---|---|---|---|
| `ChatListScreen` | NavHost-Ziel | Linkes Panel (fest) | `NavigationBarItem`-OnClick → Panel-Auswahl |
| `ChatDetailScreen` | NavHost-Ziel | Rechtes Panel (fest) | Kein `NavHost`-Back-Button; `Window`-Menüleiste |
| `SettingsScreen` | Eigenes NavHost-Ziel | Overlay-Panel / Dialog | Ist bereits `AlertDialog`-basiert — minimal |
| `MyIdScreen` | NavHost-Ziel | `Dialog` oder eigenes `Window` | QR-Code-Rendering identisch |
| `AddContactScreen` | NavHost-Ziel | `Dialog` | Identisch |
| `QrScannerScreen` | CameraX Preview | Webcam-Feed | Kamera-API komplett anders |
| `OnboardingScreen` | NavHost-Ziel | Entfällt (nur ID-Generierung) | Desktop braucht kein Setup |
| `PermissionSetupScreen` | NavHost-Ziel | Entfällt | Desktop hat keine Runtime-Permissions |
| `TransportSetupScreen` | NavHost-Ziel | Entfällt | Transports werden implizit gestartet |

#### Navigation

Ersetzt `NavHost` durch zustandsbasiertes Split-Layout:

```kotlin
// desktop/.../ui/CrisixWindow.kt
@Composable
fun CrisixWindow(appState: CrisixAppState) {
    var selectedChatId by remember { mutableStateOf<String?>(null) }
    var currentOverlay by remember { mutableStateOf<DesktopOverlay?>(null) }

    Row(Modifier.fillMaxSize()) {
        // Linkes Panel (immer sichtbar)
        ChatListPanel(
            onChatSelected = { selectedChatId = it },
            onSettings = { currentOverlay = DesktopOverlay.SETTINGS },
            onMyId = { currentOverlay = DesktopOverlay.MY_ID },
            onAddContact = { currentOverlay = DesktopOverlay.ADD_CONTACT },
            onContacts = { currentOverlay = DesktopOverlay.CONTACTS },
            modifier = Modifier.width(300.dp)
        )

        // Rechtes Panel
        if (selectedChatId != null) {
            ChatDetailPanel(chatId = selectedChatId!!)
        } else {
            PlaceholderScreen()
        }
    }

    // Overlays (Dialoge)
    when (currentOverlay) {
        DesktopOverlay.SETTINGS -> SettingsDialog(onDismiss = { currentOverlay = null })
        DesktopOverlay.MY_ID -> MyIdDialog(onDismiss = { currentOverlay = null })
        DesktopOverlay.ADD_CONTACT -> AddContactDialog(onDismiss = { currentOverlay = null })
        null -> {}
    }
}
```

### 2.4 Datenbank: Room → SQLDelight

Room ist Android-spezifisch. SQLDelight generiert aus `.sq`-Dateien typsichere Kotlin-APIs:

```sql
-- desktop/src/main/sqldelight/com/messenger/crisix/db/CrisixDb.sq

CREATE TABLE MessageEntity (
    id TEXT NOT NULL PRIMARY KEY,
    chatId TEXT NOT NULL,
    senderId TEXT NOT NULL,
    textContent TEXT NOT NULL,
    timestampMillis INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'SENT',
    isEncrypted INTEGER NOT NULL DEFAULT 0,
    disappearingTimerMs INTEGER NOT NULL DEFAULT 0,
    expiresAtMillis INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE ChatEntity (
    id TEXT NOT NULL PRIMARY KEY,
    contactId TEXT NOT NULL,
    lastMessage TEXT,
    lastMessageTimeMillis INTEGER NOT NULL DEFAULT 0,
    unreadCount INTEGER NOT NULL DEFAULT 0,
    isGroup INTEGER NOT NULL DEFAULT 0,
    disappearingTimerMs INTEGER NOT NULL DEFAULT 0
);

-- Queries
getChats:
SELECT * FROM ChatEntity ORDER BY lastMessageTimeMillis DESC;

getMessagesForChat:
SELECT * FROM MessageEntity WHERE chatId = ? ORDER BY timestampMillis ASC;

insertMessage:
INSERT OR REPLACE INTO MessageEntity VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
```

**Desktop-DB-Pfad**: `~/.crisix/crisix.db` (Linux) / `%APPDATA%\Crisix\crisix.db` (Windows)

### 2.5 Settings: DataStore → `java.util.prefs`

```kotlin
// desktop/.../platform/SettingsStore.kt
class DesktopSettingsStore {
    private val prefs = Preferences.userNodeForPackage(CrisixAppState::class.java)

    var themeMode: String
        get() = prefs.get("theme_mode", "system")
        set(value) = prefs.put("theme_mode", value)

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) = prefs.putBoolean("notifications_enabled", value)

    // ... alle SettingsKeys 1:1 mappen
}
```

### 2.6 System Tray & Notifications

```kotlin
// desktop/.../platform/SystemTrayManager.kt
object TrayManager {
    private var trayIcon: TrayIcon? = null

    fun init(icon: BufferedImage, onClick: () -> Unit) {
        if (!SystemTray.isSupported()) return
        trayIcon = TrayIcon(icon, "Crisix").apply {
            isImageAutoSize = true
            popupMenu = PopupMenu().apply {
                add(MenuItem("Öffnen")).addActionListener { onClick() }
                addSeparator()
                add(MenuItem("Beenden")).addActionListener { exitApplication() }
            }
            addActionListener { onClick() }  // Doppelklick → öffnen
        }
        SystemTray.getSystemTray().add(trayIcon)
    }

    fun showNotification(title: String, message: String) {
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }
}
```

Für Windows 10/11 Toast-Notifications und Linux D-Bus/libnotify kann optional eine Bibliothek wie `com.dorkbox:Notify` eingebunden werden.

### 2.7 QR-Scanner: Webcam + ZXing

```kotlin
// desktop/.../platform/QrScanner.kt
class WebcamQrScanner {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
        ))
    }

    fun startCapture(onResult: (String) -> Unit) {
        // Webcam-Capture-Frame → BufferedImage → BinaryBitmap → ZXing
        val webcam = Webcam.getDefault()
        webcam.open()
        thread {
            while (webcam.isOpen) {
                val image = webcam.image ?: continue
                val bitmap = BinaryBitmap(HybridBinarizer(
                    BufferedImageLuminanceSource(image)
                ))
                try {
                    val result = reader.decode(bitmap)
                    onResult(result.text)
                    webcam.close()
                } catch (_: NotFoundException) { }
            }
        }
    }
}
```

### 2.8 Entfallende Android-Features

| Feature | Grund | Desktop-Alternative |
|---|---|---|
| BLE Transport | Keine Desktop-BLE-API | — |
| WiFi Direct | Android-spezifisch | — |
| SMS Transport | Kein Modem | — |
| LoRa Transport | Hardware-abhängig | — |
| CameraX | Android Camera2-API | Webcam-Capture-Bibliothek |
| Biometric App-Lock | Kein Standard-API | — (entfällt) |
| ForegroundService | Android-Konzept | System Tray (App läuft immer) |
| WorkManager | Android Job Scheduler | Coroutine-Timer |
| BootReceiver | Android Broadcast | OS-Autostart-Eintrag |
| EncryptedSharedPrefs | Android TINK | Bouncy Castle AES + Datei |
| DataStore | Android Jetpack | java.util.prefs |
| Room | Android SQLite Wrapper | SQLDelight |
| UpdateManager (APK) | Android Package Manager | GitHub Releases → `.msi`/`.deb` |
| Baseline Profile | Android AOT | — (JVM JIT, kein AOT nötig) |
| LeakCanary | Android Debug | — |
| ML Kit Barcode | Google Play Services | ZXing (reicht für QR) |
| OkHttp | — | Ktor Client (KMP-kompatibel) |
| Timber | Android-spezifisch | SLF4J/Logback |
| `android.net.*` APIs | Android | `java.net.*` Standard-Java |
| Coil (Android) | Android | Coil 3 (Multiplatform) |

---

## Phase 3: Build-System-Integration (1 Tag)

### 3.1 `settings.gradle.kts` erweitern

```kotlin
pluginManagement {
    repositories {
        google { ... }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Crisix"
include(":app")
include(":macrobenchmark")
include(":crisix-core")     // NEU
include(":desktop")         // NEU
```

### 3.2 Root `build.gradle.kts` erweitern

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.baseline.profile) apply false
    id("org.jetbrains.compose") version "1.7.3" apply false    // NEU
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false  // NEU
}
```

### 3.3 Version Catalog erweitern

```toml
# gradle/libs.versions.toml
[versions]
# ... bestehend ...
ktor = "3.1.0"
sqldelight = "2.0.2"
kotlinx-serialization = "1.7.3"
kotlinx-coroutines = "1.9.0"
slf4j = "2.0.16"
logback = "1.5.12"

[libraries]
ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
ktor-client-cio = { group = "io.ktor", name = "ktor-client-cio", version.ref = "ktor" }
ktor-client-websockets = { group = "io.ktor", name = "ktor-client-websockets", version.ref = "ktor" }
sqldelight-coroutines = { group = "app.cash.sqldelight", name = "coroutines-extensions", version.ref = "sqldelight" }
sqldelight-sqlite-driver = { group = "app.cash.sqldelight", name = "sqlite-driver", version.ref = "sqldelight" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-serialization-protobuf = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-protobuf", version.ref = "kotlinx-serialization" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
slf4j-api = { group = "org.slf4j", name = "slf4j-api", version.ref = "slf4j" }
logback-classic = { group = "ch.qos.logback", name = "logback-classic", version.ref = "logback" }
```

---

## Phase 4: Distribution & Release (1 Tag)

### Build-Kommandos

```bash
# Windows
./gradlew desktop:packageMsi
# → desktop/build/compose/binaries/main/msi/Crisix-1.5.0.msi

# Linux
./gradlew desktop:packageDeb
# → desktop/build/compose/binaries/main/deb/Crisix-1.5.0.deb

./gradlew desktop:packageRpm
# → desktop/build/compose/binaries/main/rpm/Crisix-1.5.0.rpm
```

### Auslieferungsgröße

| Komponente | Größe |
|---|---|
| JRE (JDK 23, jlink-minimiert) | ~45 MB |
| App-code + Compose Desktop Runtime | ~15 MB |
| SQLite native driver | ~2 MB |
| Bouncy Castle | ~5 MB |
| **Gesamt (MSI/DEB)** | **~65–70 MB** |

### Auto-Updater

Desktop-Variante des bestehenden `UpdateManager`:
- Prüft GitHub Releases auf neue Version
- Lädt `.msi` / `.deb` herunter
- Zeigt Dialog: "Neue Version verfügbar. Jetzt installieren?"
- Öffnet Installer (kein APK-Installations-Intent nötig)
- Verifiziert SHA256-Checksumme

---

## Phase 5: Tests (1–2 Tage)

### Was wird getestet

```
crisix-core/src/test/kotlin/          ← Bestehende Tests (werden verschoben)
├── crypto/DoubleRatchetTest.kt
├── crypto/BinaryEncryptionTest.kt
├── crypto/X3DHKeyAgreementTest.kt
├── crypto/SessionStateMachineTest.kt
├── crypto/E2eeIntegrationTest.kt
├── transport/TransportManagerTest.kt
├── transport/internet/InternetTransportTest.kt
├── transport/DefragmenterTest.kt
└── transport/FragmenterTest.kt

desktop/src/test/kotlin/              ← Neue Tests
├── platform/DesktopSettingsStoreTest.kt
├── platform/QrScannerTest.kt
└── db/DatabaseMigrationTest.kt
```

### Teststrategie

- **Core-Logik**: Alle bestehenden Unit-Tests bleiben erhalten und werden ins `crisix-core`-Modul verschoben (JUnit 4 → JUnit 5 migriert)
- **Desktop-Plattform**: Integrationstests gegen Datei-DB, Preferences, Webcam
- **UI**: Manuelles Testen (Compose Desktop hat noch kein stabiles UI-Testing-Framework)

---

## Zeitplan (Gesamt: 9–13 Tage)

| Phase | Aufgabe | Geschätzt |
|---|---|---|
| **1** | `crisix-core` extrahieren & bereinigen | 2–3 Tage |
| 1.1 | Modul anlegen, Abhängigkeiten deklarieren | 0.5 Tage |
| 1.2 | Dateien verschieben, Imports fixen | 1 Tag |
| 1.3 | `android.*` → JVM-Standard + SLF4J migrieren | 1 Tag |
| **2** | Compose Desktop App | 4–6 Tage |
| 2.1 | Build-Setup, Gradle, `Main.kt` | 0.5 Tage |
| 2.2 | Split-Layout Fenster + Navigation | 1 Tag |
| 2.3 | Screens adaptieren (ChatList, ChatDetail, Settings, etc.) | 2 Tage |
| 2.4 | SQLDelight DB + Repository | 1 Tag |
| 2.5 | System Tray + Notifications | 0.5 Tage |
| 2.6 | Webcam-QR-Scanner | 0.5 Tage |
| 2.7 | Settings Store | 0.5 Tage |
| **3** | Build-System-Integration | 1 Tag |
| **4** | Distribution & Auto-Updater | 1 Tag |
| **5** | Tests | 1–2 Tage |
| | **Gesamt** | **9–13 Tage** |

---

## Risiken & Abwägungen

| Risiko | Eintrittswahrscheinlichkeit | Mitigation |
|---|---|---|
| Compose Desktop hat Bugs/Inkompatibilitäten | Mittel | Screens schrittweise migrieren; Dialog-basierte Fallbacks |
| `Ktor` WebSocket verhält sich anders als OkHttp | Mittel | Protokoll ist einfach (JSON über WS); minimaler Unterschied |
| SQLDelight API-Änderungen seit letztem Release | Niedrig | Version-Pinning im Catalog |
| Webcam-Zugriff auf Linux (v4l2) instabil | Niedrig | Webcam-Capture-Bibliothek wrapped v4l2 sauber |
| `jpackage` generiert fehlerhafte native Pakete | Niedrig | JDK 23 ist stabil; GitHub Actions CI testet Build |
| Room ↔ SQLDelight Schema-Divergenz | Hoch | **Nur Desktop nutzt SQLDelight**; Android behält Room; kein Sync nötig |

---

## Alternativ-Ansatz diskutiert, aber verworfen

- **Kotlin Multiplatform (KMP) mit `commonMain`**: Würde bedeuten, das gesamte Android-Projekt in KMP umzubauen (expect/actual für alle Android-APIs). Das ist ein 2–3 Monate langes Projekt mit hohem Risiko, bestehende Android-Funktionalität zu brechen. Der `crisix-core`-Library-Ansatz ist risikoarm, isoliert das Android-Projekt und liefert dasselbe Ergebnis.

---

## Zusammenfassung

- **Sprache**: Kotlin → JVM-Bytecode (JDK 23)
- **UI**: Compose Multiplatform für Desktop (Skia-Renderer, identisch zu Android Compose)
- **Netzwerk**: Kademlia DHT, TCP-Streams, DNS-Tunnel, WebSocket-Relay (alle plattformunabhängig)
- **Datenbank**: SQLDelight (Dateibasiertes SQLite)
- **E2EE**: Bouncy Castle (Double Ratchet + X3DH) — läuft unverändert
- **Distribution**: Native `.msi` (Windows) / `.deb` + `.rpm` (Linux) mit eingebettetem JRE — kein Java-Install nötig
- **App-Größe**: ~65–70 MB
