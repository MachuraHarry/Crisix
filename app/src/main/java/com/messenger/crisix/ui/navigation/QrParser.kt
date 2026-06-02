package com.messenger.crisix.ui.navigation

import android.util.Base64
import android.util.Log
import com.messenger.crisix.crypto.X3DHSession

fun isHandshakeQr(content: String): Boolean {
    return content.startsWith("crisix://handshake")
}

fun extractBundleFromQr(content: String): X3DHSession.PreKeyBundle? {
    return try {
        val uri = android.net.Uri.parse(content)
        val bundleB64 = uri.getQueryParameter("bundle") ?: return null
        val bundleJson = String(Base64.decode(bundleB64, Base64.URL_SAFE))
        X3DHSession.PreKeyBundle.fromJson(bundleJson)
    } catch (e: Exception) {
        Log.e("CrisixApp", "Fehler beim Parsen des Handshake-QR-Bundles", e)
        null
    }
}

fun extractPeerIdFromQr(content: String): String? {
    return try {
        val uri = android.net.Uri.parse(content)
        uri.getQueryParameter("key")
    } catch (e: Exception) {
        null
    }
}

fun extractNameFromQr(content: String): String? {
    return try {
        val uri = android.net.Uri.parse(content)
        uri.getQueryParameter("name")
    } catch (e: Exception) {
        null
    }
}

fun extractIpFromQr(content: String): String? {
    return try {
        val uri = android.net.Uri.parse(content)
        uri.getQueryParameter("ip")
    } catch (e: Exception) {
        null
    }
}

fun extractPortFromQr(content: String): Int? {
    return try {
        val uri = android.net.Uri.parse(content)
        uri.getQueryParameter("port")?.toIntOrNull()
    } catch (e: Exception) {
        null
    }
}
