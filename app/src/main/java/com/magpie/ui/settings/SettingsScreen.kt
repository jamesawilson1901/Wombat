package com.magpie.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.magpie.Graph
import com.magpie.api.ClaudeClient
import com.magpie.data.ApiKeyCrypto
import com.magpie.data.SettingsRepository
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings by Graph.settings.snapshots.collectAsState(initial = null)
    val presets by Graph.db.presets().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = settings ?: return

    var keyField by remember { mutableStateOf("") }
    var keyStatus by remember { mutableStateOf<String?>(null) }
    var model by remember(s.model) { mutableStateOf(s.model) }
    var cap by remember(s.spendCapUsd) { mutableStateOf(s.spendCapUsd.toString()) }
    var inRate by remember(s.inputRatePerMTok) { mutableStateOf(s.inputRatePerMTok.toString()) }
    var outRate by remember(s.outputRatePerMTok) { mutableStateOf(s.outputRatePerMTok.toString()) }
    var debounce by remember(s.debounceSeconds) { mutableStateOf(s.debounceSeconds.toString()) }
    var sweepDest by remember(s.sweepDestination) { mutableStateOf(s.sweepDestination) }
    var newWatched by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
        }

        // ---- Claude API key --------------------------------------------------
        item {
            SectionCard("Claude API key") {
                Text(
                    if (s.hasApiKey) "A key is stored (encrypted)." else "No key stored. Everything still works without one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = keyField,
                    onValueChange = { keyField = it },
                    label = { Text("sk-ant-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.getText()?.text?.trim()?.let { keyField = it }
                    }) { Text("Paste") }
                    Button(
                        enabled = keyField.isNotBlank(),
                        onClick = {
                            scope.launch {
                                Graph.settings.setApiKeyCipher(ApiKeyCrypto.encrypt(context, keyField.trim()))
                                keyStatus = "Key saved."
                                keyField = ""
                            }
                        },
                    ) { Text("Save") }
                    OutlinedButton(
                        enabled = keyField.isNotBlank() || s.hasApiKey,
                        onClick = {
                            keyStatus = "Testing…"
                            scope.launch {
                                val plain = keyField.trim().ifEmpty {
                                    ApiKeyCrypto.decrypt(context, s.apiKeyCipher) ?: ""
                                }
                                keyStatus = when (val out = Graph.claude.testKey(plain)) {
                                    is ClaudeClient.Outcome.Ok -> "Key works ✓"
                                    is ClaudeClient.Outcome.Failed -> "Failed: ${out.reason}"
                                    ClaudeClient.Outcome.Skipped -> "Skipped"
                                }
                            }
                        },
                    ) { Text("Test key") }
                }
                keyStatus?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---- Model + spend ---------------------------------------------------
        item {
            SectionCard("Model & spend") {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cap,
                    onValueChange = { cap = it },
                    label = { Text("Monthly spend cap (USD)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = inRate,
                        onValueChange = { inRate = it },
                        label = { Text("$/M input tok") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = outRate,
                        onValueChange = { outRate = it },
                        label = { Text("$/M output tok") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "This month: $%.4f · All-time: $%.4f · %d calls".format(
                        Locale.US, s.monthSpendUsd, s.allTimeSpendUsd, s.callCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            Graph.settings.setModel(model.ifBlank { SettingsRepository.DEFAULT_MODEL })
                            cap.toDoubleOrNull()?.let { Graph.settings.setSpendCap(it) }
                            val i = inRate.toDoubleOrNull()
                            val o = outRate.toDoubleOrNull()
                            if (i != null && o != null) Graph.settings.setRates(i, o)
                            keyStatus = null
                        }
                    }) { Text("Save") }
                    OutlinedButton(onClick = { scope.launch { Graph.settings.resetMonthSpend() } }) {
                        Text("Reset monthly total")
                    }
                }
            }
        }

        // ---- Preset folders --------------------------------------------------
        item { Text("Preset folders", style = MaterialTheme.typography.titleMedium) }
        items(presets, key = { it.path }) { preset ->
            var desc by remember(preset.path, preset.description) { mutableStateOf(preset.description) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                preset.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val index = presets.indexOf(preset)
                        TextButton(enabled = index > 0, onClick = {
                            scope.launch { swapOrder(presets, index, index - 1) }
                        }) { Text("↑") }
                        TextButton(enabled = index < presets.size - 1, onClick = {
                            scope.launch { swapOrder(presets, index, index + 1) }
                        }) { Text("↓") }
                        TextButton(onClick = { scope.launch { Graph.db.presets().delete(preset.path) } }) {
                            Text("Remove")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = desc,
                            onValueChange = { desc = it },
                            label = { Text("Description") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            scope.launch { Graph.db.presets().upsert(preset.copy(description = desc)) }
                        }) { Text("Save") }
                    }
                }
            }
        }

        // ---- Screenshot sweep ------------------------------------------------
        item {
            SectionCard("Screenshot sweep") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sweep stray screenshots", Modifier.weight(1f))
                    Switch(
                        checked = s.sweepEnabled,
                        onCheckedChange = { scope.launch { Graph.settings.setSweepEnabled(it) } },
                    )
                }
                OutlinedTextField(
                    value = sweepDest,
                    onValueChange = { sweepDest = it },
                    label = { Text("Destination folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { scope.launch { Graph.settings.setSweepDestination(sweepDest.trim()) } }) {
                    Text("Save destination")
                }
            }
        }

        // ---- Debounce + watched folders -------------------------------------
        item {
            SectionCard("Detection") {
                OutlinedTextField(
                    value = debounce,
                    onValueChange = { debounce = it },
                    label = { Text("Debounce window (seconds)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    debounce.toIntOrNull()?.let { d -> scope.launch { Graph.settings.setDebounceSeconds(d) } }
                }) { Text("Save debounce") }

                Spacer(Modifier.height(6.dp))
                Text("Watched folders", style = MaterialTheme.typography.titleMedium)
                s.watchedFolders.sorted().forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folder, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            scope.launch { Graph.settings.setWatchedFolders(s.watchedFolders - folder) }
                        }) { Text("Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newWatched,
                        onValueChange = { newWatched = it },
                        label = { Text("Add folder path") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = newWatched.isNotBlank(),
                        onClick = {
                            scope.launch {
                                Graph.settings.setWatchedFolders(s.watchedFolders + newWatched.trim())
                                newWatched = ""
                            }
                        },
                    ) { Text("Add") }
                }
            }
        }

        item {
            Text(
                "Theme follows the system setting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private suspend fun swapOrder(presets: List<com.magpie.data.PresetFolder>, a: Int, b: Int) {
    // Persist a full re-numbering so ordering is stable from then on.
    val reordered = presets.toMutableList().also { list ->
        val tmp = list[a]; list[a] = list[b]; list[b] = tmp
    }
    Graph.db.presets().upsertAll(reordered.mapIndexed { i, p -> p.copy(sortOrder = i) })
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
