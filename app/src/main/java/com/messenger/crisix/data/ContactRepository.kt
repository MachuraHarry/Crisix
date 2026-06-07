package com.messenger.crisix.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.absoluteValue
import org.json.JSONArray
import java.util.UUID

/**
 * Repository für die dauerhafte Speicherung von Kontakten.
 *
 * ## Aktuelle Implementierung
 * Speichert Kontakte als JSON-Array in SharedPreferences.
 * Einfach, robust, kein Room/DB-Overhead nötig.
 *
 * ## Zukünftige Verschlüsselung
 * Sobald ein Encryption-Key gesetzt wird (z.B. aus dem libp2p-Private-Key
 * abgeleitet), werden alle Kontakte mit AES-256-GCM verschlüsselt.
 *
 * ### Migration zu verschlüsselter Speicherung:
 * ```kotlin
 * repository.setEncryptionKey(myKey) // Schaltet automatisch auf encrypt/decrypt um
 * repository.saveContacts(contacts)  // Wird jetzt verschlüsselt gespeichert
 * ```
 *
 * Der Encryption-Key wird NIEMALS in SharedPreferences gespeichert,
 * sondern immer aus dem Private-Key abgeleitet.
 */
class ContactRepository(private val context: Context) {

    companion object {
        private const val TAG = "ContactRepository"
        private const val PREFS_NAME = "crisix_contacts"
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_ENCRYPTED = "contacts_encrypted"

        private val AVATAR_COLORS = listOf(
            "#00475D", "#1B3A5C", "#0D47A1", "#B71C1C",
            "#E65100", "#01579B", "#37474F", "#263238",
        )
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Der Encryption-Key für zukünftige AES-256-GCM-Verschlüsselung.
     * Solange dieser null ist, werden Kontakte im Klartext gespeichert.
     */
    @Volatile
    var encryptionKey: ByteArray? = null

    // =========================================================================
    // Öffentliche API
    // =========================================================================

    /**
     * Lädt alle gespeicherten Kontakte.
     *
     * @return Liste aller Kontakte (leere Liste, wenn keine vorhanden)
     */
    fun loadContacts(): List<Contact> {
        return try {
            val jsonString = prefs.getString(KEY_CONTACTS, null)
            if (jsonString != null) {
                val jsonArray = JSONArray(jsonString)
                val contacts = Contact.listFromJson(jsonArray)
                Log.d(TAG, "${contacts.size} Kontakt(e) geladen")
                contacts
            } else {
                Log.d(TAG, "Keine Kontakte gefunden")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Laden der Kontakte: ${e.message}")
            emptyList()
        }
    }

    /**
     * Speichert eine Liste von Kontakten.
     * Überschreibt alle vorherigen Kontakte.
     *
     * @param contacts Die zu speichernden Kontakte
     */
    fun saveContacts(contacts: List<Contact>) {
        try {
            val jsonArray = JSONArray()
            for (contact in contacts) {
                jsonArray.put(contact.toJson())
            }
            prefs.edit().putString(KEY_CONTACTS, jsonArray.toString()).apply()
            Log.d(TAG, "${contacts.size} Kontakt(e) gespeichert")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Speichern der Kontakte: ${e.message}")
        }
    }

    /**
     * Fügt einen neuen Kontakt hinzu oder aktualisiert einen vorhandenen.
     *
     * @param contact Der hinzuzufügende/aktualisierende Kontakt
     * @return Die aktualisierte Kontaktliste
     */
    @Synchronized
    fun addOrUpdateContact(contact: Contact): List<Contact> {
        val contacts = loadContacts().toMutableList()
        val existingIndex = contacts.indexOfFirst { it.id == contact.id || it.peerId == contact.peerId }

        if (existingIndex >= 0) {
            // Vorhandenen Kontakt aktualisieren
            val existing = contacts[existingIndex]
            val updated = contact.copy(
                addedAt = existing.addedAt // Ursprüngliches Datum beibehalten
            )
            contacts[existingIndex] = updated
            Log.d(TAG, "Kontakt aktualisiert: ${contact.name} (${contact.shortId})")
        } else {
            // Neuen Kontakt hinzufügen
            contacts.add(contact)
            Log.d(TAG, "Kontakt hinzugefügt: ${contact.name} (${contact.shortId})")
        }

        saveContacts(contacts)
        return contacts
    }

    /**
     * Entfernt einen Kontakt anhand seiner ID.
     *
     * @param contactId Die ID des zu entfernenden Kontakts
     * @return Die aktualisierte Kontaktliste
     */
    @Synchronized
    fun removeContact(contactId: String): List<Contact> {
        val contacts = loadContacts().toMutableList()
        contacts.removeAll { it.id == contactId }
        saveContacts(contacts)
        Log.d(TAG, "Kontakt entfernt: $contactId")
        return contacts
    }

    /**
     * Findet einen Kontakt anhand der Peer-ID.
     *
     * @param peerId Die Peer-ID (Fingerprint oder UUID)
     * @return Der gefundene Kontakt, oder null
     */
    fun findContactByPeerId(peerId: String): Contact? {
        return loadContacts().find { it.peerId == peerId }
    }

    /**
     * Findet einen Kontakt anhand seiner internen ID.
     *
     * @param contactId Die interne Kontakt-ID
     * @return Der gefundene Kontakt, oder null
     */
    fun findContactById(contactId: String): Contact? {
        return loadContacts().find { it.id == contactId }
    }

    /**
     * Erstellt einen neuen Kontakt aus den gegebenen Daten.
     * Generiert automatisch eine UUID.
     *
     * @param peerId Die Peer-ID
     * @param name Der Anzeigename
     * @param ipAddress Optionale IP-Adresse
     * @param port Optionaler Port
     * @return Der erstellte Kontakt
     */
    fun createContact(
        peerId: String,
        name: String,
        ipAddress: String? = null,
        port: Int? = null,
        phoneNumber: String? = null
    ): Contact {
        val idx = name.hashCode().absoluteValue % AVATAR_COLORS.size
        return Contact(
            id = UUID.randomUUID().toString(),
            peerId = peerId,
            name = name,
            ipAddress = ipAddress,
            port = port,
            phoneNumber = phoneNumber,
            colorTag = AVATAR_COLORS[idx],
            addedAt = System.currentTimeMillis()
        )
    }

    /**
     * Gibt die Anzahl der gespeicherten Kontakte zurück.
     */
    fun getContactCount(): Int {
        return loadContacts().size
    }

    /**
     * Löscht alle gespeicherten Kontakte.
     */
    fun clearAll() {
        prefs.edit().remove(KEY_CONTACTS).apply()
        Log.d(TAG, "Alle Kontakte gelöscht")
    }
}
