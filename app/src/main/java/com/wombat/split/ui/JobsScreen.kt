package com.wombat.split.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wombat.split.R
import com.wombat.split.jobs.SplitJob

private const val MEGABYTE = 1024L * 1024L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(viewModel: JobsViewModel = viewModel()) {
    val jobs by viewModel.jobs.collectAsState()
    var showNewJob by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* jobs run either way; the notification is just invisible if denied */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showNewJob = true }) {
                Text(stringResource(R.string.new_job))
            }
        },
    ) { innerPadding ->
        if (jobs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.empty_state),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(jobs.asReversed(), key = { it.id }) { job ->
                    JobCard(job, onCancel = { viewModel.cancelJob(job.id) })
                }
            }
        }
    }

    if (showNewJob) {
        NewJobDialog(
            onDismiss = { showNewJob = false },
            onStart = { sources, destination, limitBytes, zipParts ->
                viewModel.startJobs(sources, destination, limitBytes, zipParts)
                showNewJob = false
            },
        )
    }
}

@Composable
private fun JobCard(job: SplitJob, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(job.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "≤ ${formatBytes(job.limitBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            when (val status = job.status) {
                is SplitJob.Status.Queued -> {
                    Text(
                        stringResource(R.string.status_queued),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is SplitJob.Status.Cancelled -> {
                    Text(
                        stringResource(R.string.status_cancelled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is SplitJob.Status.Scanning -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.status_scanning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is SplitJob.Status.Running -> {
                    val fraction =
                        if (status.bytesTotal > 0) {
                            status.bytesCopied.toFloat() / status.bytesTotal
                        } else {
                            0f
                        }
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.status_running,
                            formatBytes(status.bytesCopied),
                            formatBytes(status.bytesTotal),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is SplitJob.Status.Done -> {
                    Text(
                        stringResource(
                            R.string.status_done,
                            status.partCount,
                            status.fileCount,
                            formatBytes(status.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (status.chunkedFileCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.chunked_note, status.chunkedFileCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is SplitJob.Status.Failed -> {
                    Text(
                        status.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (job.isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun NewJobDialog(
    onDismiss: () -> Unit,
    onStart: (
        sources: List<SourceSelection>,
        destination: Uri,
        limitBytes: Long,
        zipParts: Boolean,
    ) -> Unit,
) {
    val context = LocalContext.current
    val sources = remember { mutableStateListOf<SourceSelection>() }
    var destinationUri by remember { mutableStateOf<Uri?>(null) }
    var limitText by remember { mutableStateOf("25") }
    var zipParts by remember { mutableStateOf(true) }

    fun persist(uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    fun addSource(uri: Uri, isFile: Boolean) {
        if (sources.any { it.uri == uri }) return
        persist(uri, write = !isFile)
        sources += SourceSelection(uri, isFile, sourceLabel(context, uri, isFile))
    }

    // SAF has no multi-folder picker — folders are added one at a time, while
    // files can be multi-selected in a single trip to the picker.
    val pickSourceFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { addSource(it, isFile = false) } }
    val pickSourceFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> uris.forEach { addSource(it, isFile = true) } }
    val pickDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { persist(it, write = true); destinationUri = it } }

    val limitMb = limitText.toLongOrNull()
    val canStart =
        sources.isNotEmpty() && destinationUri != null && limitMb != null && limitMb > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_job_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { pickSourceFolder.launch(null) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.add_folder)) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { pickSourceFiles.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.add_files)) }
                }
                Spacer(Modifier.height(4.dp))
                if (sources.isEmpty()) {
                    Text(
                        text = stringResource(R.string.source_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.queue_count, sources.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    sources.forEach { source ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = source.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { sources.remove(source) }) {
                                Text(stringResource(R.string.remove))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { pickDestination.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        destinationUri?.let { folderLabel(it) }
                            ?: stringResource(R.string.pick_destination)
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it },
                    label = { Text(stringResource(R.string.limit_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.zip_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(checked = zipParts, onCheckedChange = { zipParts = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canStart,
                onClick = {
                    onStart(
                        sources.toList(),
                        destinationUri!!,
                        limitMb!! * MEGABYTE,
                        zipParts,
                    )
                },
            ) { Text(stringResource(R.string.start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Readable name for a picked source: display name for files, path tail for folders. */
private fun sourceLabel(context: Context, uri: Uri, isFile: Boolean): String {
    if (isFile) {
        runCatching { DocumentFile.fromSingleUri(context, uri)?.name }
            .getOrNull()
            ?.let { return it }
    }
    return folderLabel(uri)
}

/** Human-ish label for a tree URI, e.g. "primary:Download/Big" -> "Download/Big". */
private fun folderLabel(uri: Uri): String {
    val segment = uri.lastPathSegment ?: return uri.toString()
    return segment.substringAfter(':').ifEmpty { segment }
}

private fun formatBytes(bytes: Long): String {
    val mb = MEGABYTE.toDouble()
    return when {
        bytes >= 1024 * MEGABYTE -> "%.1f GB".format(bytes / (1024 * mb))
        bytes >= MEGABYTE -> "%.1f MB".format(bytes / mb)
        bytes >= 1024 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }
}
