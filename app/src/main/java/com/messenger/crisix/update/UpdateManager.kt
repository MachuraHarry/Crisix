package com.messenger.crisix.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.messenger.crisix.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val PREFS_NAME = "crisix_update"
    private const val KEY_LAST_CHECK = "last_check_timestamp"
    private const val THROTTLE_MS = 24 * 60 * 60 * 1000L

    private val githubOwner: String get() = BuildConfig.GITHUB_OWNER
    private val githubRepo: String get() = BuildConfig.GITHUB_REPO

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        object UpToDate : UpdateState()
        data class UpdateAvailable(
            val versionName: String,
            val versionCode: Int,
            val changelog: String,
            val downloadUrl: String,
            val sizeBytes: Long
        ) : UpdateState()
        data class Downloading(val progress: Float) : UpdateState()
        data class ReadyToInstall(val filePath: String) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .certificatePinner(
            CertificatePinner.Builder()
                .add("api.github.com", "sha256/QVnLDkTvhX8bfBbaP6XeqWLCOja893s79lYfjQc/hWI=")
                .add("github.com", "sha256/QVnLDkTvhX8bfBbaP6XeqWLCOja893s79lYfjQc/hWI=")
                .build()
        )
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun checkForUpdate(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        val now = System.currentTimeMillis()

        if (!force && now - lastCheck < THROTTLE_MS) {
            Log.d(TAG, "Update check throttled, last check: ${now - lastCheck}ms ago")
            return
        }

        scope.launch {
            _state.value = UpdateState.Checking
            try {
                val result = fetchLatestRelease()
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                _state.value = result
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                _state.value = UpdateState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun downloadUpdate(context: Context) {
        val currentState = _state.value
        if (currentState !is UpdateState.UpdateAvailable) return

        scope.launch {
            try {
                _state.value = UpdateState.Downloading(0f)

                val apkDir = File(context.filesDir, "apks")
                apkDir.mkdirs()
                val apkFile = File(apkDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()

                val request = Request.Builder()
                    .url(currentState.downloadUrl)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    _state.value = UpdateState.Error(
                        "Download failed: HTTP ${response.code}"
                    )
                    return@launch
                }

                val body = response.body ?: run {
                    _state.value = UpdateState.Error("Empty response")
                    return@launch
                }

                val totalBytes = body.contentLength()
                val buffer = ByteArray(8192)
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                _state.value = UpdateState.Downloading(
                                    downloadedBytes.toFloat() / totalBytes.toFloat()
                                )
                            }
                        }
                    }
                }

                if (!verifyApkSignature(context, apkFile)) {
                    apkFile.delete()
                    _state.value = UpdateState.Error(
                        "Signature verification failed — APK may be tampered with"
                    )
                    return@launch
                }

                _state.value = UpdateState.ReadyToInstall(apkFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _state.value = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun installUpdate(context: Context) {
        val currentState = _state.value
        if (currentState !is UpdateState.ReadyToInstall) return

        val apkFile = File(currentState.filePath)
        if (!apkFile.exists()) {
            _state.value = UpdateState.Error("APK file not found")
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                    ).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            _state.value = UpdateState.Error(e.message ?: "Installation failed")
        }
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    /**
     * Verifies that the downloaded APK is signed with the same certificate
     * as the currently installed APK. Prevents installation of tampered builds.
     */
    private fun verifyApkSignature(context: Context, apkFile: File): Boolean {
        return try {
            val pm = context.packageManager

            val currentInfo = pm.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
            )
            val currentCerts = currentInfo.signingInfo?.apkContentsSigners
                ?: return false

            val archiveInfo = pm.getPackageArchiveInfo(
                apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES
            ) ?: return false

            val archiveCerts = archiveInfo.signingInfo?.apkContentsSigners
                ?: return false

            val currentFingerprints = currentCerts.map { sha256(it) }
            val archiveFingerprints = archiveCerts.map { sha256(it) }

            val matches = currentFingerprints.toSet() == archiveFingerprints.toSet()

            if (!matches) {
                Log.e(TAG, "APK signature mismatch! Current: $currentFingerprints, Archive: $archiveFingerprints")
            }

            matches
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification error", e)
            false
        }
    }

    private fun sha256(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(signature.toByteArray())
        return hash.joinToString(":") { "%02x".format(it) }
    }

    private suspend fun fetchLatestRelease(): UpdateState = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$githubOwner/$githubRepo/releases/latest"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            return@withContext when (response.code) {
                404 -> UpdateState.UpToDate
                else -> UpdateState.Error("GitHub API error: HTTP ${response.code}")
            }
        }

        val body = response.body?.string()
            ?: return@withContext UpdateState.Error("Empty response")
        val json = JSONObject(body)

        val tagName = json.getString("tag_name")
        val remoteVersionCode = parseVersionCode(tagName)
            ?: return@withContext UpdateState.Error("Invalid tag format: $tagName")

        if (remoteVersionCode <= BuildConfig.VERSION_CODE) {
            return@withContext UpdateState.UpToDate
        }

        val changelog = json.optString("body", "")

        val assets = json.getJSONArray("assets")
        var downloadUrl = ""
        var sizeBytes = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.endsWith(".apk")) {
                downloadUrl = asset.getString("browser_download_url")
                sizeBytes = asset.optLong("size", 0)
                break
            }
        }

        if (downloadUrl.isEmpty()) {
            return@withContext UpdateState.Error("No APK found in release")
        }

        UpdateState.UpdateAvailable(
            versionName = tagName,
            versionCode = remoteVersionCode,
            changelog = changelog,
            downloadUrl = downloadUrl,
            sizeBytes = sizeBytes
        )
    }

    private fun parseVersionCode(tag: String): Int? {
        val cleaned = tag.trimStart('v', 'V')
        return cleaned.toIntOrNull()
    }
}
