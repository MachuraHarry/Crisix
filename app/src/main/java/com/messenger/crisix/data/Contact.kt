package com.messenger.crisix.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Repräsentiert einen gespeicherten Kontakt in Crisix.
 *
 * @property id Eindeutige ID (UUID)
 * @property peerId Peer-ID / Fingerprint (z.B. "12D3KooW..." oder UUID)
 * @property name Anzeigename (vom Benutzer festgelegt)
 * @property ipAddress Letzte bekannte IP-Adresse (optional)
 * @property port Letzter bekannter Port (optional)
 * @property note Persönliche Notiz (optional)
 * @property colorTag Farbcode für den Avatar (z.B. "#4CAF50")
 * @property isBlocked Wurde der Kontakt blockiert?
 * @property addedAt Zeitstempel der ersten Speicherung (Unix Millis)
 * @property lastSeen Zeitstempel der letzten Sichtung (Unix Millis, optional)
 * @property phoneNumber Telefonnummer für SMS-Fallback (optional)
 */
data class Contact(
    val id: String,
    val peerId: String,
    val name: String,
    val ipAddress: String? = null,
    val port: Int? = null,
    val note: String = "",
    val colorTag: String = "#4CAF50",
    val isBlocked: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeen: Long? = null,
    val phoneNumber: String? = null,
) {
    companion object {
        private const val TAG = "Contact"

        /**
         * Erstellt einen Contact aus einem JSONObject.
         */
        fun fromJson(json: JSONObject): Contact {
            return Contact(
                id = json.getString("id"),
                peerId = json.getString("peerId"),
                name = json.getString("name"),
                ipAddress = json.optString("ipAddress", null as String?),
                port = if (json.has("port") && !json.isNull("port")) json.getInt("port") else null,
                note = json.optString("note", ""),
                colorTag = json.optString("colorTag", "#4CAF50"),
                isBlocked = json.optBoolean("isBlocked", false),
                addedAt = json.optLong("addedAt", System.currentTimeMillis()),
                lastSeen = if (json.has("lastSeen") && !json.isNull("lastSeen")) json.getLong("lastSeen") else null,
                phoneNumber = json.optString("phoneNumber", null as String?),
            )
        }

        /**
         * Parst eine JSON-Liste von Kontakten.
         */
        fun listFromJson(jsonArray: JSONArray): List<Contact> {
            val contacts = mutableListOf<Contact>()
            for (i in 0 until jsonArray.length()) {
                contacts.add(fromJson(jsonArray.getJSONObject(i)))
            }
            return contacts
        }
    }

    /**
     * Konvertiert diesen Contact in ein JSONObject.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("peerId", peerId)
            put("name", name)
            put("ipAddress", ipAddress ?: JSONObject.NULL)
            put("port", port ?: JSONObject.NULL)
            put("note", note)
            put("colorTag", colorTag)
            put("isBlocked", isBlocked)
            put("addedAt", addedAt)
            put("lastSeen", lastSeen ?: JSONObject.NULL)
            if (phoneNumber != null) put("phoneNumber", phoneNumber)
        }
    }

    /**
     * Gibt die Kurz-ID (erste 8 Zeichen der Peer-ID) zurück.
     */
    val shortId: String get() = peerId.take(8).padEnd(8, '?')

    /**
     * Erstellt eine Kopie mit aktualisierter `lastSeen`.
     */
    fun withLastSeen(): Contact = copy(lastSeen = System.currentTimeMillis())
}
