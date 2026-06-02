package com.messenger.crisix.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.compose.foundation.lazy.LazyListState
import com.messenger.crisix.R
import com.messenger.crisix.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    entities: LazyPagingItems<MessageEntity>,
    listState: LazyListState,
    scope: CoroutineScope,
) {
    val searchItems = remember(entities, query) {
        if (query.isBlank()) emptyList()
        else entities.itemSnapshotList
            .filterNotNull()
            .filter { it.text.contains(query, ignoreCase = true) }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.action_search)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            if (searchItems.isNotEmpty()) {
                Text(
                    text = "${matchIndex.coerceIn(0, searchItems.size - 1) + 1}/${searchItems.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                IconButton(onClick = {
                    val idx = entities.itemSnapshotList.indexOf(searchItems.getOrNull(matchIndex.coerceAtMost(searchItems.size - 2)))
                    if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                    onPrevious()
                }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.search_previous),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = {
                    val idx = entities.itemSnapshotList.indexOf(searchItems.getOrNull(matchIndex.coerceIn(0, searchItems.size - 1)))
                    if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                    onNext()
                }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.search_next),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
