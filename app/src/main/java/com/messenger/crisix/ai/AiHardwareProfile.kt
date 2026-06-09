package com.messenger.crisix.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.settingsDataStore
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.edit

data class HardwareProfile(
    val totalRamMb: Long,
    val availRamMb: Long,
    val cpuCores: Int,
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidSdk: Int,
)

data class RecommendedSettings(
    val contextSize: Int,
    val threads: Int,
    val gpuLayers: Int,
    val vulkanEnabled: Boolean,
)

object AiHardwareProfile {

    private const val TAG = "AiHardware"

    private val VULKAN_GOOD = setOf(
        "google/pixel 6",
        "google/pixel 6 pro",
        "google/pixel 6a",
        "google/pixel 7",
        "google/pixel 7 pro",
        "google/pixel 7a",
        "google/pixel 8",
        "google/pixel 8 pro",
        "google/pixel 8a",
        "google/pixel 9",
        "google/pixel 9 pro",
        "google/pixel 9 pro xl",
        "google/pixel 9a",
        "google/pixel tablet",
        "google/pixel fold",

        "samsung/sm-s901", "samsung/sm-s906", "samsung/sm-s908",   // S22 series
        "samsung/sm-s911", "samsung/sm-s916", "samsung/sm-s918",   // S23 series
        "samsung/sm-s921", "samsung/sm-s926", "samsung/sm-s928",   // S24 series
        "samsung/sm-s931", "samsung/sm-s936", "samsung/sm-s938",   // S25 series
        "samsung/sm-x910", "samsung/sm-x916",                       // Tab S8 Ultra
        "samsung/sm-x810",                                          // Tab S9

        "oneplus/oneplus 11",
        "oneplus/oneplus 12",
        "oneplus/oneplus 13",
        "oneplus/oneplus 12r",
        "oneplus/ace 3",
        "oneplus/ace 5",

        "xiaomi/2210132c",    // Xiaomi 13 Pro
        "xiaomi/2211133g",    // Xiaomi 13
        "xiaomi/23127pn0cc",  // Xiaomi 14
        "xiaomi/2312dab50c",  // Xiaomi 13T
        "nothing/pixel 1",    // Nothing Phone 1
        "nothing/pixel 2",    // Nothing Phone 2

        "asus/ai2201",       // ROG Phone 6
        "asus/ai2301",       // ROG Phone 7

        "motorola/edge 40",
        "motorola/edge 50",
        "motorola/edge 50 pro",

        "sony/xq-bq52",      // Xperia 5 IV
        "sony/xq-dq44",      // Xperia 1 V
    )

    private val VULKAN_BAD = setOf(
        "oneplus/oneplus 8",
        "oneplus/oneplus 8 pro",
        "oneplus/oneplus 8t",
        "oneplus/oneplus 9",
        "oneplus/oneplus 9 pro",
        "oneplus/oneplus 9rt",

        "xiaomi/xiaomi 11",
        "xiaomi/xiaomi 11t",
        "xiaomi/xiaomi 11t pro",
        "xiaomi/xiaomi 11 lite",
        "xiaomi/mi 10",
        "xiaomi/mi 10 pro",
        "xiaomi/mi 10t",
        "xiaomi/mi 10t pro",
        "xiaomi/2107113sg",   // Xiaomi 11T
        "xiaomi/2107113si",   // Xiaomi 11T Pro

        "samsung/sm-g980",  // S20
        "samsung/sm-g985",
        "samsung/sm-g986",
        "samsung/sm-g988",
        "samsung/sm-g990",  // S20 FE
        "samsung/sm-g991",  // S21
        "samsung/sm-g996",
        "samsung/sm-g998",
        "samsung/sm-g9900", // S21 FE

        "oneplus/nord",
        "oneplus/nord 2",
        "oneplus/nord ce",
        "oneplus/nord ce 2",

        "oppo/find x3",
        "oppo/find x3 pro",
        "oppo/find x5",
        "oppo/find x5 lite",

        "fairphone/fp4",
        "fairphone/fp5",
    )

    private val VULKAN_ALWAYS_GOOD_CHIPS = setOf(
        "tensor",
        "exynos 2200", "exynos 2400",
        "kirin 9000", "kirin 9010",
        "dimensity 9000", "dimensity 9200", "dimensity 9300",
    )

    private val VULKAN_ALWAYS_BAD_CHIPS = setOf(
        "snapdragon 865", "sm8250",
        "snapdragon 870", "sm8250-ac",
        "snapdragon 888", "sm8350",
        "snapdragon 888+", "sm8350-ac",
        "exynos 2100",
        "exynos 990",
        "kirin 990",
    )

    private fun getVulkanSupport(profile: HardwareProfile): Boolean {
        val key = "${profile.manufacturer.lowercase()}/${profile.device.lowercase()}"

        if (key in VULKAN_BAD) return false
        if (key in VULKAN_GOOD) return true

        val hardware = profile.device.lowercase()
        val board = Build.BOARD.lowercase()
        for (chip in VULKAN_ALWAYS_BAD_CHIPS) {
            if (hardware.contains(chip) || board.contains(chip)) return false
        }
        for (chip in VULKAN_ALWAYS_GOOD_CHIPS) {
            if (hardware.contains(chip) || board.contains(chip)) return true
        }
        return true
    }

    fun detect(context: Context): HardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMemMb = memInfo.totalMem / 1_048_576
        val availMemMb = memInfo.availMem / 1_048_576
        val cpuCores = Runtime.getRuntime().availableProcessors()

        return HardwareProfile(
            totalRamMb = totalMemMb,
            availRamMb = availMemMb,
            cpuCores = cpuCores,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidSdk = Build.VERSION.SDK_INT,
        )
    }

    fun recommend(profile: HardwareProfile): RecommendedSettings {
        val usableRam = (profile.totalRamMb - 2048).coerceAtLeast(512)

        val contextSize = when {
            usableRam < 2048 -> 1024
            usableRam < 3072 -> 2048
            usableRam < 4096 -> 4096
            usableRam < 6144 -> 8192
            else -> 16384
        }
        val threads = (profile.cpuCores / 2).coerceIn(2, 4)
        val vulkan = getVulkanSupport(profile)
        val gpuLayers = if (vulkan) {
            when {
                usableRam >= 5120 -> 99
                usableRam >= 3072 -> 40
                else -> 0
            }
        } else 0

        return RecommendedSettings(
            contextSize = contextSize,
            threads = threads,
            gpuLayers = gpuLayers,
            vulkanEnabled = vulkan,
        )
    }

    suspend fun applyAutoConfig(context: Context): RecommendedSettings {
        val prefs = context.settingsDataStore.data.first()
        val alreadyApplied = prefs[SettingsKeys.AI_AUTO_CONFIG_APPLIED] ?: false

        if (alreadyApplied) {
            val current = RecommendedSettings(
                contextSize = prefs[SettingsKeys.AI_CONTEXT_SIZE] ?: 4096,
                threads = prefs[SettingsKeys.AI_THREADS] ?: 4,
                gpuLayers = prefs[SettingsKeys.AI_GPU_LAYERS] ?: 0,
                vulkanEnabled = !(prefs[SettingsKeys.AI_VULKAN_DISABLED] ?: false),
            )
            return current
        }

        val profile = detect(context)
        val rec = recommend(profile)

        context.settingsDataStore.edit { settings ->
            settings[SettingsKeys.AI_CONTEXT_SIZE] = rec.contextSize
            settings[SettingsKeys.AI_THREADS] = rec.threads
            settings[SettingsKeys.AI_GPU_LAYERS] = rec.gpuLayers
            settings[SettingsKeys.AI_VULKAN_DISABLED] = !rec.vulkanEnabled
            settings[SettingsKeys.AI_AUTO_CONFIG_APPLIED] = true

            settings[SettingsKeys.AI_BATCH_SIZE] = if ((profile.totalRamMb - 2048).coerceAtLeast(0) < 3072) 128 else 256
            settings[SettingsKeys.AI_AUTO_RAM_MB] = profile.totalRamMb.toInt()
            settings[SettingsKeys.AI_AUTO_CPU] = profile.cpuCores
        }

        return rec
    }
}
