package com.messenger.crisix.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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

    // Automatisch nach unten scrollen wenn neue Logs kommen
    LaunchedEffect(Unit) {
        InAppLogger.logCount.collect { count ->
            if (count > 0) {
                listState.animateScrollToItem(count - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "App-Log",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = "Zurück"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { InAppLogger.clear() }) {
                        Text(
                            "Löschen",
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
        val logs = InAppLogger.logs

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Noch keine Logs vorhanden",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(logs, key = { "${it.timestamp}-${it.message}-${System.identityHashCode(it)}" }) { entry ->
                    LogEntryRow(entry)
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
