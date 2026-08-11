package com.magpie.overlay

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magpie.ui.theme.MagpieTheme
import java.util.Locale

/** §6.2 — the popup itself. Feels like a system dialog: one glance, big targets. */
@Composable
fun PopupContent(
    session: PopupSession,
    onChoose: (String) -> Unit,
    onNewFolder: (String) -> Unit,
    onSomewhereElse: () -> Unit,
    onNotNow: () -> Unit,
) {
    MagpieTheme {
        var dragTotal by remember { mutableStateOf(0f) }
        var showNewFolderField by remember { mutableStateOf(false) }
        val suggestions by session.suggestions
        val error by session.error
        val busy by session.busy

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .pointerInput(Unit) {
                    // Swiping the popup away = Not now (§6.2).
                    detectVerticalDragGestures(
                        onDragEnd = { if (kotlin.math.abs(dragTotal) > 220f) onNotNow() else dragTotal = 0f },
                    ) { _, dragAmount -> dragTotal += dragAmount }
                }
                .animateContentSize(tween(200)),
        ) {
            Column(Modifier.padding(20.dp)) {
                val count = session.entries.size
                Text(
                    text = if (count == 1) "1 new file" else "$count new files",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))

                session.entries.forEach { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Checkbox(
                            checked = session.ticked[entry.path] == true,
                            onCheckedChange = { session.ticked[entry.path] = it },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = elideMiddle(entry.name),
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = formatSize(entry.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Editable rename field, shown only when proposed (§9).
                            session.renames[entry.path]?.let { proposed ->
                                OutlinedTextField(
                                    value = proposed,
                                    onValueChange = { session.renames[entry.path] = it },
                                    label = { Text("Rename to") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                suggestions.take(3).forEach { folder ->
                    Button(
                        onClick = { onChoose(folder) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).height(48.dp),
                    ) {
                        Text(folder.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Spacer(Modifier.height(6.dp))

                if (showNewFolderField) {
                    var name by session.newFolderName
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("New folder name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onNewFolder(name) }, enabled = !busy && name.isNotBlank()) {
                            Text("Create")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showNewFolderField = true },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("New folder") }
                        OutlinedButton(
                            onClick = onSomewhereElse,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Somewhere else…") }
                    }
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onNotNow, modifier = Modifier.align(Alignment.End)) {
                    Text("Not now")
                }
            }
        }
    }
}

/** Elide the middle, keep the extension visible (§6.2). */
fun elideMiddle(name: String, max: Int = 34): String {
    if (name.length <= max) return name
    val keepEnd = 12
    val keepStart = max - keepEnd - 1
    return name.take(keepStart) + "…" + name.takeLast(keepEnd)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1 shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1 shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f kB", bytes / 1024.0)
    else -> "$bytes B"
}
