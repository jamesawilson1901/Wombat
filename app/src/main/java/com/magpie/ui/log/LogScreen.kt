package com.magpie.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.magpie.Graph
import com.magpie.overlay.elideMiddle
import com.magpie.service.Filer
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun LogScreen(onBack: () -> Unit) {
    val logs by Graph.db.moveLog().observeAll().collectAsState(initial = emptyList())
    val apiError by Graph.settings.lastApiError.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var undoError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Move log", style = MaterialTheme.typography.titleLarge)
            }
        }

        // §6.3: API errors are a small dismissible line here, never a dialog.
        apiError?.let { (msg, at) ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "API: $msg (${dateFormat.format(Date(at))})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { scope.launch { Graph.settings.clearApiError() } }) { Text("✕") }
                    }
                }
            }
        }

        undoError?.let { msg ->
            item {
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (logs.isEmpty()) {
            item { Text("No moves yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        items(logs, key = { it.id }) { log ->
            // refresh forces re-evaluation of file existence after an undo.
            val canUndo = remember(log, refresh) { Graph.filer.undoAvailable(log) }
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(elideMiddle(log.originalName), maxLines = 1)
                        Text(
                            "→ ${log.destFolder.substringAfterLast('/')}" +
                                (if (log.finalName != log.originalName) " as ${elideMiddle(log.finalName)}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${dateFormat.format(Date(log.timestamp))} · ${log.suggestedBy}" +
                                (if (log.accepted) " · accepted" else " · overrode") +
                                (if (log.undone) " · undone" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = canUndo,
                        onClick = {
                            scope.launch {
                                when (val r = Graph.filer.undo(log.id)) {
                                    is Filer.MoveResult.Error -> undoError = r.message
                                    is Filer.MoveResult.Ok -> undoError = null
                                }
                                refresh++
                            }
                        },
                    ) { Text("Undo") }
                }
            }
        }
    }
}
