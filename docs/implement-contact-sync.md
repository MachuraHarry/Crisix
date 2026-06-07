# Crisix — Kontakt-Import/Export & Synchronisation

## 1. Ziel

Kontakte zwischen Crisix-Geräten austauschbar machen und vor Datenverlust schützen.

**V1 — Import/Export:**
- Alle Kontakte als JSON-Datei exportieren (Teilen via Android Share Sheet)
- Kontakte aus JSON-Datei importieren (File Picker)

**V2 — P2P-Synchronisation:**
- Kontakt via Crisix-Nachricht teilen (spezieller Nachrichtentyp `crisix_contact_share`)
- Beim Empfang automatisch zum ContactRepository hinzufügen

---

## 2. Implementierungsdateien

### Neue Dateien

| Datei | Zeilen | Beschreibung |
|-------|--------|-------------|
| `data/ContactImportExport.kt` | ~60 | JSON Serialisierung/Deserialisierung aller Kontakte |

### Zu ändernde Dateien

| Datei | Änderung |
|-------|----------|
| `ui/screens/ContactListScreen.kt` | Import/Export-Buttons in der TopBar |
| `ui/screens/AddContactScreen.kt` | "Aus Datei importieren"-Option |
| `message/MessageProcessor.kt` | `crisix_contact_share`-Typ verarbeiten |
| `message/MessageSender.kt` | `sendContact()`-Methode |
| `ui/screens/ContactDetailScreen.kt` | "Kontakt teilen"-Button |
| `res/values/strings.xml` | Neue Strings |
| `res/values-en/strings.xml` | Englische Strings |

---

## 3. Export

```
ContactRepository.loadContacts()
        ↓
ContactImportExport.toJson(contacts)
        ↓
String (JSON) als .crisix.json Datei
        ↓
Android Share Sheet (ACTION_SEND, FileProvider)
```

Format:
```json
{
  "version": 1,
  "exportedAt": 1717800000000,
  "contacts": [
    {
      "id": "uuid-1",
      "peerId": "abc123...",
      "name": "Harry",
      "phoneNumber": "+49123...",
      "ipAddress": "192.168.1.5",
      "port": 39199,
      "note": "...",
      "colorTag": "#4CAF50",
      "addedAt": 1717700000000
    }
  ]
}
```

## 4. Import

```
File Picker → .crisix.json / .json auswählen
        ↓
ContactImportExport.fromJson(content)
        ↓
List<Contact>
        ↓
ContactRepository.addOrUpdateContact() für jeden
        ↓
Merge-Strategie: per peerId matchen → aktualisieren, sonst neu
```

## 5. P2P-Kontakt-Teilen

```
Absender:
  ContactDetailScreen → "Kontakt teilen"-Button
  MessageSender.sendContact(peerId, contact)
  Format: JSON mit type=crisix_contact_share

Empfänger:
  MessageProcessor erkennt type=crisix_contact_share
  Auto-Add zum ContactRepository
  Snackbar: "Kontakt von <name> hinzugefügt"
```

## 6. Strings

```xml
<!-- DE -->
<string name="contact_list_export">Export</string>
<string name="contact_list_import">Import</string>
<string name="contact_export_title">Crisix-Kontakte</string>
<string name="contact_import_success">%d Kontakte importiert</string>
<string name="contact_import_none">Keine gültigen Kontakte gefunden</string>
<string name="contact_share_button">Kontakt teilen</string>
<string name="contact_received_toast">Kontakt von %s gespeichert</string>
```
