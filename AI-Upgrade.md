# KI-Upgrade-Plan: Crisix auf Pixel 9 (Mali-G715 / Tensor G4)

## Aktuelle Lage (Stand Juni 2026)

| Komponente | Wert |
|---|---|
| Modell | ~3B, Q4_0 GGUF, Gemma 4-basiert |
| CPU-Backend | `armv8.2-a+dotprod+i8mm` → **kein SVE2** (Tensor G4 hat SVE2) |
| Vulkan-Backend | Kompiliert, aber Mali-G715 treiberseitig lahm (~0,4 tok/s) |
| CPU-Modus | ~1,0 tok/s, TTFT ~29s |
| llama.cpp Version | `cui-llama.rn` Fork (ca. Ende 2024) |
| KV Cache | F16 hardcoded, Q8_0/Q4_0 nicht nutzbar |
| Native Lib | `librnllama_v8_2_dotprod_i8mm.so` |

Bekannte Probleme:
- Native SIGABRT in `initContextWithFd` bei `gpu=20` + `LM_GGML_VK_DISABLE_HOST_VISIBLE_VIDMEM=1`
- Mali-Vulkan langsamer als CPU (veralteter llama.cpp ohne Mali-Tuning aus PR #18493)
- `vkWaitForFences` Hang bei Backgrounding (Mali Bug #23359)

---

## Schritt 1: Thread-Defaults vereinheitlichen (Kotlin, ~5 min)

**Problem:** `SettingsViewModel` zeigt standardmäßig 4 Threads an, aber `AiModelManager` und `AiInferenceController` fallen auf 6 zurück (`?: 6`). Nach Auto-Config wird zwar korrekt 4 geschrieben (8 Kerne / 2), aber falls das Config nicht läuft, sind Runtime und UI inkonsistent.

**Änderungen:**
- `AiModelManager.getSavedThreads()`: `?: 6` → `?: 4`
- `AiInferenceController.load()`: `?: 6` → `?: 4`
- `AiHardwareProfile.applyAutoConfig()`: `?: 6` → `?: 4`

---

## Schritt 2: SVE2 Native Library (kotlinllamacpp, ~2-4 h)

**Ziel:** CPU-Durchsatz von ~1,0 tok/s auf ~1,5-2,0 tok/s steigern.

**Was:**
1. Neues CMake Target in `CMakeLists.txt`: `-march=armv9-a+dotprod+i8mm+sve2`
2. Baut `librnllama_v9_sve2.so`
3. Runtime CPU-Erkennung in `LlamaAndroid.kt`: `/proc/cpuinfo` auf `"sve2"` prüfen
4. Passende `.so` zur Laufzeit laden (fallback auf v8.2 wenn kein SVE2)

**Warum:** Tensor G4 Cortex-A720 Kerne unterstützen SVE2 256-bit. llama.cpp hat SVE2-optimierte Kernel (PR #11227), die ~1,5-2x schneller sind als reinen ARMv8.2-Code.

**Status: ✅ ABGESCHLOSSEN (Juni 2026)**
- `CMakeLists.txt`: `rnllama_v9_sve2` Target mit `-march=armv9-a+dotprod+i8mm+sve2` hinzugefügt
- `LlamaAndroid.kt`: SVE2-Erkennung via `/proc/cpuinfo` + Library-Loading
- `build.gradle.kts`: `rnllama_v9_sve2` zur Build-Liste hinzugefügt
- AAR gebaut und nach `app/libs/llamaCpp-release.aar` kopiert
- Commit in kotlinllamacpp (lokal, kein Push-Zugriff auf ljcamargo Remote)

---

## Schritt 3: llama.cpp Upgrade (kotlinllamacpp, ~4-8 h)

**Ziel:** Vulkan auf Mali-G715 nutzbar machen (~7-10 tok/s erwartet).

**Was:**
1. `cui-llama.rn` Fork mit upstream llama.cpp syncen (Target b9190+)
2. Mali-G715 Vulkan-Tuning aus PR #18493 übernehmen:
   - FP32 forced (Mali hat kein schnelles FP16/BF16)
   - 4x4 Warptile (korrekt für Mali Warp Size = 16)
   - Suballocation 256 MB Limit
3. KV Cache Typ (`type_k`, `type_v`) via JNI-Parameter exposen
4. `GGML_CPU_ALL_VARIANTS=ON` aktivieren (automatischer CPU-Kernel-Dispatch)
5. Alle `.so`-Varianten neu bauen

**Erwartete Performance:**

| Szenario | Aktuell | Ziel |
|---|---|---|
| CPU (aktuell, v8.2) | 1,0 tok/s | – |
| CPU + SVE2 | – | 1,5-2,0 tok/s |
| Vulkan (alt) | 0,4 tok/s | – |
| Vulkan (Mali-Tuning) | – | 7-10 tok/s |

---

## Dateien mit Änderungsbedarf

### Crisix (Android Studio Projekt)
- `AiModelManager.kt` – Thread default, KV Cache params, buildEngineConfig
- `AiInferenceController.kt` – Thread default
- `AiHardwareProfile.kt` – Thread default, evtl. Tensor-spezifische Tuning-Parameter

### kotlinllamacpp (native Library)
- `CMakeLists.txt` – SVE2 Target, KleidiAI, Varianten
- `LlamaAndroid.kt` – Runtime .so Auswahl
- `LLamaContext.kt` – type_k/type_v Parameter
- `jni.cpp` – Neue JNI-Parameter
- `rn-llama.cpp` – KV Cache Typ Parsing
- `build.gradle.kts` – NDK Version, ABI Filter

---

## Commit-Historie (geplant)
1. ✅ `fix: unify thread defaults to 4 across all code paths`
2. ✅ `feat: add SVE2 native library variant for Tensor G4`
3. `feat: upgrade llama.cpp with Mali-G715 Vulkan tuning`
