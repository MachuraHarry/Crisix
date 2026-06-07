package com.messenger.crisix.data

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object ContactImportExport {

    private const val VERSION = 1
    private const val JSON_VERSION = "version"
    private const val JSON_EXPORTED_AT = "exportedAt"
    private const val JSON_CONTACTS = "contacts"

    fun toJson(contacts: List<Contact>): String {
        val json = JSONObject().apply {
            put(JSON_VERSION, VERSION)
            put(JSON_EXPORTED_AT, System.currentTimeMillis())
            put(JSON_CONTACTS, JSONArray().apply {
                contacts.forEach { put(it.toJson()) }
            })
        }
        return json.toString(2)
    }

    fun fromJson(jsonString: String): List<Contact> {
        return try {
            val json = JSONObject(jsonString)
            val version = json.optInt(JSON_VERSION, 1)
            val contactsArray = json.getJSONArray(JSON_CONTACTS)
            Contact.listFromJson(contactsArray)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse contacts JSON")
            emptyList()
        }
    }

    fun contactToJson(contact: Contact): String {
        return JSONObject().apply {
            put("type", "crisix_contact_share")
            put("contact", contact.toJson())
        }.toString()
    }

    fun contactFromJson(jsonString: String): Contact? {
        return try {
            val json = JSONObject(jsonString)
            if (json.optString("type") != "crisix_contact_share") return null
            val contactJson = json.getJSONObject("contact")
            Contact.fromJson(contactJson)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse shared contact")
            null
        }
    }
}
