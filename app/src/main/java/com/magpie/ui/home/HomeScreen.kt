package com.magpie.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.magpie.Graph
import com.magpie.overlay.elideMiddle
import com.magpie.overlay.formatSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onRerunWizard: () -> Unit,
) {
    val settings by Graph.settings.snapshots.collectAsState(initial = null)
    val pending by Graph.db.pending().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val s = settings ?: return

    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Magpie", style = MaterialTheme.typography.titleLarge)
            Text(
                "Watching ${s.watchedFolders.size} folders",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // §14: cap reached is a stop, not a warning.
        if (s.hasApiKey && s.capReached) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Monthly spend cap reached", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "API calls are stopped. Magpie is running on local rules only. " +
                                "Raise the cap or reset the monthly total in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        // §15: single dismissible card when no key is set. Don't nag beyond it.
        if (!s.hasApiKey && !s.apiCardDismissed) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("No Claude API key set", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Everything works without one — suggestions just come from your own filing history. " +
                                "Add a key in Settings for smarter suggestions.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row {
                            TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                            TextButton(onClick = { scope.launch { Graph.settings.setApiCardDismissed(true) } }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    scope.launch {
                        val files = withContext(Dispatchers.IO) {
                            File("${com.magpie.data.SettingsRepository.storageRoot}/Download")
                                .listFiles()
                                ?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".magpie-tmp") }
                                ?.sortedByDescending { it.lastModified() }
                                ?.map { it.absolutePath }
                                ?: emptyList()
                        }
                        // §13: same suggestion flow, batches of 20, one popup per batch.
                        Graph.popups.enqueueBacklog(files)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Sort backlog") }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenLog, modifier = Modifier.weight(1f)) { Text("Log") }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
                OutlinedButton(onClick = onRerunWizard, modifier = Modifier.weight(1f)) { Text("Folders") }
            }
        }

        if (pending.isNotEmpty()) {
            item {
                Spacer(Modifier.height(6.dp))
                Text("Waiting to be filed", style = MaterialTheme.typography.titleMedium)
            }
            items(pending, key = { it.path }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(elideMiddle(p.path.substringAfterLast('/')), maxLines = 1)
                            Text(
                                formatSize(p.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { Graph.popups.showGroup(listOf(p.path)) }) { Text("File it") }
                    }
                }
            }
        }
    }
}
