package com.messenger.crisix.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.crisix.R

/**
 * Zeigt die gesamten InApp-Logs in einer scrollbaren Liste an.
 * Nützlich zum Debuggen und zur Fehleranalyse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBackClick: () -> Unit
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var activeFilters by remember { mutableStateOf(setOf("D", "I", "W", "E")) }
    val exportLabel = stringResource(R.string.log_viewer_export)

    // Automatisch nach unten scrollen wenn neue Logs kommen
    LaunchedEffect(Unit) {
        InAppLogger.logCount.collect { count ->
            if (count > 0) {
                listState.animateScrollToItem(count - 1)
            }
        }
    }

    val allLogs = InAppLogger.logs
    val filteredLogs = remember(allLogs, activeFilters) {
        allLogs.filter { it.level in activeFilters }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.log_viewer_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = allLogs.joinToString("\n") { "${it.timestamp} [${it.level}] ${it.tag}: ${it.message}" }
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, text)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, exportLabel)
                        context.startActivity(shareIntent)
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_send),
                            contentDescription = exportLabel
                        )
                    }
                    TextButton(onClick = { InAppLogger.clear() }) {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = Color(0xFF1A1A1A) // Dunkler Hintergrund wie Terminal
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                listOf("D", "I", "W", "E").forEach { level ->
                    val isActive = level in activeFilters
                    val chipColor = when (level) {
                        "D" -> Color(0xFF6A9955)
                        "I" -> Color(0xFF4FC1FF)
                        "W" -> Color(0xFFE5C07B)
                        "E" -> Color(0xFFFF5370)
                        else -> Color.LightGray
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isActive) chipColor.copy(alpha = 0.3f) else Color(0xFF2A2A2A))
                            .clickable {
                                activeFilters = if (isActive && activeFilters.size > 1) {
                                    activeFilters - level
                                } else if (!isActive) {
                                    activeFilters + level
                                } else {
                                    activeFilters
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = level,
                            color = if (isActive) chipColor else Color(0xFF666666),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.log_viewer_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    items(filteredLogs, key = { "${it.timestamp}-${it.message}-${System.identityHashCode(it)}" }) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: InAppLogger.LogEntry) {
    val levelColor = when (entry.level) {
        "D" -> Color(0xFF6A9955) // Grün für Debug
        "I" -> Color(0xFF4FC1FF) // Hellblau für Info
        "W" -> Color(0xFFE5C07B) // Gelb für Warning
        "E" -> Color(0xFFFF5370) // Rot für Error
        else -> Color.LightGray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp)
    ) {
        // Level-Badge
        Text(
            text = entry.level,
            color = levelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(20.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Timestamp
        Text(
            text = entry.timestamp,
            color = Color(0xFF808080),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(80.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Tag
        Text(
            text = entry.tag,
            color = Color(0xFFDCDCAA),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(80.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Message
        Text(
            text = entry.message,
            color = Color(0xFFD4D4D4),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}
