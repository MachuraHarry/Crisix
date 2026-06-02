package com.messenger.crisix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.messenger.crisix.R
import com.messenger.crisix.data.Contact

/**
 * Bildschirm zur Anzeige und Verwaltung aller gespeicherten Kontakte.
 *
 * Zeigt:
 * - Alle dauerhaft gespeicherten Kontakte
 * - Suchfunktion (nach Name/Peer-ID)
 * - Löschen von Kontakten
 * - Chat starten mit einem Kontakt
 * - Bearbeiten eines Kontakts (Name, Notiz, Blockieren)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    contacts: List<Contact>,
    onBackClick: () -> Unit,
    onContactClick: (Contact) -> Unit = {},
    onDeleteContact: (String) -> Unit = {},
    onStartChat: (String, String) -> Unit = { _, _ -> },
    onAddContact: (String, String, String?, Int?) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<Contact?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    // State für manuelles Hinzufügen
    var newPeerId by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newIp by remember { mutableStateOf("") }
    var newPort by remember { mutableStateOf("") }

    // Kontakte nach Suchbegriff filtern
    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.peerId.contains(searchQuery, ignoreCase = true) ||
                it.shortId.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // Dialog: Kontakt manuell hinzufügen
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.contact_list_add_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newPeerId,
                        onValueChange = { newPeerId = it },
                        label = { Text(stringResource(R.string.contact_list_peer_id_label)) },
                        placeholder = { Text(stringResource(R.string.contact_list_peer_id_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.contact_list_name_label)) },
                        placeholder = { Text(stringResource(R.string.contact_list_name_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newIp,
                        onValueChange = { newIp = it },
                        label = { Text(stringResource(R.string.contact_list_ip_optional_label)) },
                        placeholder = { Text(stringResource(R.string.contact_list_ip_optional_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPort,
                        onValueChange = { newPort = it },
                        label = { Text(stringResource(R.string.contact_list_port_optional_label)) },
                        placeholder = { Text(stringResource(R.string.contact_list_port_optional_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPeerId.isNotBlank() && newName.isNotBlank()) {
                            val port = newPort.toIntOrNull()
                            val ip = newIp.ifBlank { null }
                            onAddContact(newPeerId.trim(), newName.trim(), ip, port)
                            showAddDialog = false
                            newPeerId = ""
                            newName = ""
                            newIp = ""
                            newPort = ""
                        }
                    },
                    enabled = newPeerId.isNotBlank() && newName.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Lösch-Dialog
    val contactToDelete = showDeleteDialog
    if (contactToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.contact_list_delete_title)) },
            text = {
                Text(stringResource(R.string.contact_list_delete_body, contactToDelete.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteContact(contactToDelete.id)
                    showDeleteDialog = null
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.action_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        Text(
                            stringResource(R.string.contact_list_title, filteredContacts.size),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    if (isSearchActive) {
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = stringResource(R.string.action_cancel)
                            )
                        }
                    } else {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_search),
                                contentDescription = stringResource(R.string.action_search)
                            )
                        }
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_person),
                            contentDescription = stringResource(R.string.contact_list_add_fab_description),
                            tint = MaterialTheme.colorScheme.primary
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
        if (contacts.isEmpty()) {
            // Leerer Zustand
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_person),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.contact_list_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.contact_list_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(
                    items = filteredContacts,
                    key = { it.id }
                ) { contact ->
                    ContactListItem(
                        contact = contact,
                        onClick = { onContactClick(contact) },
                        onDelete = { showDeleteDialog = contact },
                        onStartChat = { onStartChat(contact.peerId, contact.name) }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        modifier = Modifier.padding(start = 72.dp)
                    )
                }
            }
        }
    }
}

/**
 * Einzelner Kontakt-Eintrag in der Liste.
 */
@Composable
private fun ContactListItem(
    contact: Contact,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onStartChat: () -> Unit
) {
    val avatarColor = try {
        Color(android.graphics.Color.parseColor(contact.colorTag))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(avatarColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = avatarColor
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Text
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (contact.isBlocked) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.contact_list_blocked_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.contact_list_id_label, contact.shortId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (contact.note.isNotBlank()) {
                Text(
                    text = contact.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Chat-Button
        IconButton(onClick = onStartChat) {
            Icon(
                painter = painterResource(id = R.drawable.ic_chat),
                contentDescription = stringResource(R.string.contact_list_chat_start_description),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // Lösch-Button
        IconButton(onClick = onDelete) {
            Icon(
                painter = painterResource(id = R.drawable.ic_delete),
                contentDescription = stringResource(R.string.contact_list_delete_description),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            )
        }
    }
}
