package com.messenger.crisix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.messenger.crisix.R
import com.messenger.crisix.data.Contact
import androidx.annotation.StringRes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bildschirm zum Anzeigen und Bearbeiten eines einzelnen Kontakts.
 *
 * Ermöglicht:
 * - Anzeigen aller Kontaktdetails
 * - Bearbeiten des Namens
 * - Hinzufügen/Bearbeiten einer Notiz
 * - Blockieren/Entblockieren des Kontakts
 * - Chat starten
 * - Kontakt löschen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    contact: Contact,
    onBackClick: () -> Unit,
    onSave: (Contact) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onStartChat: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var editedName by remember { mutableStateOf(contact.name) }
    var editedNote by remember { mutableStateOf(contact.note) }
    var isBlocked by remember { mutableStateOf(contact.isBlocked) }
    var hasChanges by remember { mutableStateOf(false) }

    val avatarColor = try {
        Color(android.graphics.Color.parseColor(contact.colorTag))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        contact.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // Bei Änderungen speichern
                        if (hasChanges) {
                            val updated = contact.copy(
                                name = editedName,
                                note = editedNote,
                                isBlocked = isBlocked
                            )
                            onSave(updated)
                        }
                        onBackClick()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // === Avatar ===
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(avatarColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = avatarColor
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === Name bearbeiten ===
            OutlinedTextField(
                value = editedName,
                onValueChange = {
                    editedName = it
                    hasChanges = true
                },
                label = { Text(stringResource(R.string.contact_detail_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // === Notiz bearbeiten ===
            OutlinedTextField(
                value = editedNote,
                onValueChange = {
                    editedNote = it
                    hasChanges = true
                },
                label = { Text(stringResource(R.string.contact_detail_note_label)) },
                placeholder = { Text(stringResource(R.string.contact_detail_note_placeholder)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // === Details ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    DetailRow(R.string.contact_detail_peer_id, contact.peerId)
                    DetailRow(R.string.contact_detail_short_id, contact.shortId)
                    if (contact.ipAddress != null) {
                        DetailRow(R.string.contact_detail_ip, contact.ipAddress)
                    }
                    if (contact.port != null) {
                        DetailRow(R.string.contact_detail_port, contact.port.toString())
                    }
                    DetailRow(R.string.contact_detail_added, dateFormat.format(Date(contact.addedAt)))
                    if (contact.lastSeen != null) {
                        DetailRow(R.string.contact_detail_last_seen, dateFormat.format(Date(contact.lastSeen)))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // === Blockieren ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.contact_detail_block_section),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isBlocked) stringResource(R.string.contact_detail_block_active)
                                   else stringResource(R.string.contact_detail_block_inactive),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isBlocked,
                        onCheckedChange = {
                            isBlocked = it
                            hasChanges = true
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === Chat starten ===
            Button(
                onClick = { onStartChat(contact.peerId, editedName) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_chat),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.contact_detail_chat_button))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // === Speichern ===
            Button(
                onClick = {
                    val updated = contact.copy(
                        name = editedName,
                        note = editedNote,
                        isBlocked = isBlocked
                    )
                    onSave(updated)
                    hasChanges = false
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = hasChanges,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_save))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // === Löschen ===
            Button(
                onClick = { onDelete(contact.id) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.contact_detail_delete_button))
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DetailRow(@StringRes labelRes: Int, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "${stringResource(labelRes)}:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
