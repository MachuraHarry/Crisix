package com.messenger.crisix.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.compose.foundation.lazy.LazyListState
import com.messenger.crisix.R
import com.messenger.crisix.ui.components.ChatListItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    entities: LazyPagingItems<ChatListItem>,
    listState: LazyListState,
    scope: CoroutineScope,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchItems = remember(entities, query) {
        if (query.isBlank()) emptyList()
        else entities.itemSnapshotList
            .filterNotNull()
            .filterIsInstance<ChatListItem.MessageItem>()
            .filter { it.message.text.contains(query, ignoreCase = true) }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.search_close),
                    modifier = Modifier.size(24.dp)
                )
            }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.action_search)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            if (searchItems.isNotEmpty()) {
                val displayIndex = if (searchItems.isEmpty()) 0 else matchIndex.mod(searchItems.size)
                Text(
                    text = "${displayIndex + 1}/${searchItems.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                IconButton(
                    onClick = {
                        val newIndex = if (searchItems.isEmpty()) 0 else (matchIndex - 1).mod(searchItems.size)
                        val idx = entities.itemSnapshotList.indexOf(searchItems[newIndex])
                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                        onPrevious()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.search_previous),
                        modifier = Modifier.size(20.dp).rotate(90f)
                    )
                }
                IconButton(
                    onClick = {
                        val newIndex = if (searchItems.isEmpty()) 0 else (matchIndex + 1).mod(searchItems.size)
                        val idx = entities.itemSnapshotList.indexOf(searchItems[newIndex])
                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                        onNext()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.search_next),
                        modifier = Modifier.size(20.dp).rotate(-90f)
                    )
                }
            }
        }
    }
}
