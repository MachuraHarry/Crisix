package com.messenger.crisix.transport.internet

import java.security.MessageDigest

/**
 * Zentrale Konfiguration für die DHT-basierte Peer-Findung.
 *
 * ## 🌍 Globales Topic
 * Alle Crisix-Geräte verwenden DASSELBE Topic für die DHT-Registrierung.
 * Dadurch können sich Peers gegenseitig in der globalen DHT finden.
 *
 * ## Warum ein festes Topic?
 * Die Mainline DHT (BitTorrent) organisiert Peers nach Topics (info_hash).
 * Wenn jedes Gerät ein zufälliges Topic generiert, sind sie in
 * unterschiedlichen "Telefonbüchern" und finden sich nicht.
 *
 * ## Änderung des Topics
 * Bei einem Breaking Change im Protokoll muss das Topic geändert werden,
 * damit alte und neue Versionen nicht miteinander kommunizieren.
 * Erhöhe dann die Versionsnummer: "crisix-messenger-v2"
 */
object DhtConfig {
    /** Versionsstring – bei Breaking Changes erhöhen */
    private const val PROTOCOL_VERSION = "crisix-messenger-v1"

    /** Festes globales Topic (SHA-1 = 20 Bytes, kompatibel mit BEP 5) */
    val GLOBAL_TOPIC: ByteArray by lazy {
        MessageDigest.getInstance("SHA-1").digest(PROTOCOL_VERSION.toByteArray(Charsets.UTF_8))
    }

    /** Hex-Darstellung des globalen Topics für Logging */
    val GLOBAL_TOPIC_HEX: String by lazy {
        GLOBAL_TOPIC.joinToString("") { "%02x".format(it) }
    }
}
