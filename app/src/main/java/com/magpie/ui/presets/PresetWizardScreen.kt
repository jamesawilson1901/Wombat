package com.magpie.ui.presets

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.magpie.Graph
import com.magpie.data.PresetFolder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Candidate(
    val path: String,
    var description: String,
    var recommended: Boolean,
    var ticked: Boolean,
)

/**
 * §7.1 first-run preset wizard. Scans storage, optionally asks Claude for
 * one-line descriptions + recommendations (folder paths only, never file
 * contents), and MUST complete without an API key — in which case it's just
 * the folder checklist.
 */
@Composable
fun PresetWizardScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var aiUsed by remember { mutableStateOf(false) }
    val candidates = remember { mutableStateListOf<Candidate>() }

    LaunchedEffect(Unit) {
        val existing = Graph.db.presets().all().associateBy { it.path }
        val found = withContext(Dispatchers.IO) { scanForFolders() }
        val described = withContext(Dispatchers.IO) {
            Graph.claude.describeFolders(found)
        }
        aiUsed = described != null
        val byPath = described?.associateBy { it.path } ?: emptyMap()
        candidates.clear()
        found.forEach { path ->
            val d = byPath[path]
            candidates.add(
                Candidate(
                    path = path,
                    description = existing[path]?.description?.ifEmpty { d?.description ?: "" }
                        ?: (d?.description ?: ""),
                    recommended = d?.recommended ?: false,
                    // Pre-ticked for recommended ones; without AI, tick
                    // everything the user already had plus nothing else.
                    ticked = existing.containsKey(path) || (d?.recommended ?: (described == null)),
                )
            )
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Choose your folders", style = MaterialTheme.typography.titleLarge)
        Text(
            if (aiUsed) "Claude looked at the folder names (only the names) and suggested these."
            else "Untick the rubbish, keep the folders you actually file things into.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("  Scanning storage…")
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(candidates, key = { it.path }) { candidate ->
                    var ticked by remember(candidate.path) { mutableStateOf(candidate.ticked) }
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = ticked, onCheckedChange = {
                                ticked = it
                                candidate.ticked = it
                            })
                            Column {
                                Text(candidate.path.substringAfterLast('/'))
                                Text(
                                    candidate.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (candidate.description.isNotEmpty()) {
                                    Text(candidate.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        val chosen = candidates.filter { it.ticked }
                        chosen.forEachIndexed { index, c ->
                            val existing = Graph.db.presets().byPath(c.path)
                            Graph.db.presets().upsert(
                                existing?.copy(description = c.description, sortOrder = index)
                                    ?: PresetFolder(
                                        path = c.path,
                                        name = c.path.substringAfterLast('/'),
                                        description = c.description,
                                        sortOrder = index,
                                    )
                            )
                        }
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Done") }
        }
    }
}

/**
 * §7.1 qualification: at least one file, not hidden, not under Android/, not
 * a system media cache, within 4 levels of the storage root.
 */
private fun scanForFolders(): List<String> {
    val skipNames = setOf("android", "cache", ".cache", ".thumbnails", "lost.dir", ".trash", ".trashed")
    val results = mutableListOf<String>()
    for (root in Graph.storageRoots()) {
        val rootFile = File(root)
        fun walk(dir: File, depth: Int) {
            if (depth > 4 || results.size > 150) return
            val children = dir.listFiles() ?: return
            val qualifies = dir != rootFile &&
                !dir.name.startsWith(".") &&
                dir.name.lowercase() !in skipNames &&
                children.any { it.isFile && !it.name.startsWith(".") }
            if (qualifies) results.add(dir.absolutePath)
            children.filter { it.isDirectory && !it.name.startsWith(".") && it.name.lowercase() !in skipNames }
                .forEach { walk(it, depth + 1) }
        }
        walk(rootFile, 1)
    }
    return results.sorted()
}
