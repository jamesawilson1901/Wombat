package com.magpie.filer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magpie.filer.R
import com.magpie.filer.core.Formatting
import com.magpie.filer.core.Naming
import com.magpie.filer.ai.Rule
import com.magpie.filer.ai.Rules
import com.magpie.filer.core.PlanResult
import com.magpie.filer.move.BuildReport
import com.magpie.filer.move.Destinations
import com.magpie.filer.move.Filed
import com.magpie.filer.move.MoveOutcome
import com.magpie.filer.ui.theme.SectionLabel
import com.magpie.filer.watch.SpottedFile
import com.magpie.filer.watch.SuggestionSettings

@Composable
fun MagpieScreen(viewModel: MainViewModel) {
    val waiting by viewModel.waiting.collectAsState()
    val ignored by viewModel.ignored.collectAsState()
    val problems by viewModel.problems.collectAsState()
    val watching by viewModel.watching.collectAsState()
    val readiness by viewModel.readiness.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val selecting by viewModel.selecting.collectAsState()
    val step by viewModel.step.collectAsState()
    val inFolders by viewModel.inFolders.collectAsState()
    val filed by viewModel.filed.collectAsState()
    val rules by viewModel.rules.collectAsState()
    val extraRoots by viewModel.extraRoots.collectAsState()
    val treeText by viewModel.treeText.collectAsState()
    val treePlan by viewModel.treePlan.collectAsState()
    val treeReport by viewModel.treeReport.collectAsState()
    val buildingAt by viewModel.buildingAt.collectAsState()
    val suggestionSettings by viewModel.suggestionSettings.collectAsState()

    val context = LocalContext.current
    var showIgnored by rememberSaveable { mutableStateOf(false) }
    var showInFolders by rememberSaveable { mutableStateOf(false) }
    var showFiled by rememberSaveable { mutableStateOf(false) }
    var showRules by rememberSaveable { mutableStateOf(false) }
    var showFolders by rememberSaveable { mutableStateOf(false) }
    var showBuilder by rememberSaveable { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { chosen -> viewModel.onFolderChosen(chosen) }

    val libraryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { chosen -> if (chosen != null) viewModel.setLibrary(chosen) }

    val watchFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { chosen -> viewModel.addWatchedFolder(chosen) }

    val buildPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { chosen -> viewModel.onBuildFolderChosen(chosen) }

    // Opens exactly once per request, the same rule the filing picker follows.
    var launchedBuild by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(buildingAt) {
        if (buildingAt != 0L && buildingAt != launchedBuild) {
            launchedBuild = buildingAt
            buildPicker.launch(viewModel.lastDestination)
        }
    }

    val askForNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.refresh() else viewModel.onNotificationRequestRefused()
    }

    // The picker opens once per request. Keying on the token alone is not
    // enough: the activity can be recreated while the picker is still on
    // screen, which would rebuild the composition and launch a second one.
    var launchedRequest by rememberSaveable { mutableStateOf(-1L) }
    val choosing = step as? FilingStep.ChooseFolder
    LaunchedEffect(choosing?.token) {
        val token = choosing?.token
        if (token != null && token != launchedRequest) {
            launchedRequest = token
            folderPicker.launch(choosing?.openAt ?: viewModel.lastDestination)
        }
    }

    Scaffold(
        bottomBar = {
            if (selecting) {
                SelectionBar(
                    count = waiting.count { it.path in selection },
                    onSelectAll = viewModel::selectAll,
                    onCancel = viewModel::stopSelecting,
                    onFile = viewModel::fileSelected,
                )
            }
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.padding(insets),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BrandHeader() }

            item {
                StatusCard(
                    readiness = readiness,
                    watching = watching,
                    onGrantAccess = { openAllFilesAccess(context, viewModel) },
                    onAllowNotifications = {
                        // Android only shows its dialog once. After a refusal the
                        // only way through is the settings screen.
                        val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !viewModel.notificationRequestRefused
                        if (canAsk) {
                            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openSettings(context, viewModel.notificationSettingsIntent(), viewModel)
                        }
                    },
                    onWatchingChange = viewModel::setWatching,
                )
            }

            item {
                SuggestionsCard(
                    settings = suggestionSettings,
                    onKeyChange = viewModel::setApiKey,
                    onEnabledChange = viewModel::setSuggestionsEnabled,
                    onPickLibrary = { libraryPicker.launch(suggestionSettings.library) },
                    onClearLibrary = { viewModel.setLibrary(null) },
                )
            }

            items(problems, key = { it.id }) { problem ->
                ProblemCard(problem.message) { viewModel.dismissProblem(problem.id) }
            }

            item {
                SectionHeading(
                    title = if (waiting.isEmpty()) "WAITING" else "WAITING · ${waiting.size}",
                    action = if (waiting.isEmpty() || selecting) null else "Select",
                    onAction = viewModel::startSelecting,
                )
            }

            // Asking about a backlog one file at a time is a request each and a
            // wait each. One request covers the lot.
            if (waiting.size > 1 && suggestionSettings.usable && !selecting) {
                item {
                    TextButton(onClick = viewModel::suggestForWaiting) {
                        Text("Ask Claude about all ${waiting.size} at once")
                    }
                }
            }

            if (waiting.isEmpty()) {
                item { EmptyWaiting(watching) }
            } else {
                items(waiting, key = { it.path }) { file ->
                    FileRow(
                        file = file,
                        selecting = selecting,
                        selected = file.path in selection,
                        onTap = {
                            if (selecting) viewModel.toggleSelected(file)
                            else viewModel.beginFiling(file)
                        },
                        onLongPress = { viewModel.beginSelecting(file) },
                        trailing = {
                            if (!selecting) {
                                TextButton(onClick = { viewModel.ignore(file) }) {
                                    Text("Leave it")
                                }
                            }
                        },
                    )
                }
            }

            if (ignored.isNotEmpty()) {
                item {
                    SectionHeading(
                        title = "IGNORED · ${ignored.size}",
                        action = if (showIgnored) "Hide" else "Show",
                        onAction = { showIgnored = !showIgnored },
                    )
                }
                if (showIgnored) {
                    items(ignored, key = { "ignored:" + it.path }) { file ->
                        FileRow(
                            file = file,
                            selecting = false,
                            selected = false,
                            onTap = { viewModel.restore(file) },
                            onLongPress = {},
                            trailing = {
                                TextButton(onClick = { viewModel.restore(file) }) {
                                    Text("Put back")
                                }
                            },
                        )
                    }
                    item {
                        Text(
                            text = "Ignored files are never offered again by notification. " +
                                "They are still in their folder, untouched.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionHeading(
                    title = "BUILD A FOLDER TREE",
                    action = if (showBuilder) "Hide" else "Show",
                    onAction = { showBuilder = !showBuilder },
                )
            }
            if (showBuilder) {
                item {
                    TreeBuilderCard(
                        text = treeText,
                        plan = treePlan,
                        onTextChange = viewModel::setTreeText,
                        onBuild = viewModel::chooseWhereToBuild,
                    )
                }
            }

            item {
                SectionHeading(
                    title = "FOLDERS WATCHED",
                    action = if (showFolders) "Hide" else "Show",
                    onAction = { showFolders = !showFolders },
                )
            }
            if (showFolders) {
                item {
                    Text(
                        text = "Downloads, both Screenshots folders and a memory card's " +
                            "Download folder are watched already. You can add others — a " +
                            "messaging app's folder, or where a scanner app saves. Each one " +
                            "means a notification when something lands in it, so add the " +
                            "ones you actually file from.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(extraRoots, key = { "root:$it" }) { path ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.removeWatchedFolder(path) }) {
                                Text("Stop")
                            }
                        }
                    }
                }
                item {
                    Button(onClick = { watchFolderPicker.launch(null) }) {
                        Text("Watch another folder")
                    }
                }
            }

            item {
                SectionHeading(
                    title = if (rules.isEmpty()) "RULES" else "RULES · ${rules.size}",
                    action = if (showRules) "Hide" else "Show",
                    onAction = { showRules = !showRules },
                )
            }
            if (showRules) {
                item {
                    Text(
                        text = if (rules.isEmpty()) {
                            "No rules yet. After you file something, Magpie offers to " +
                                "remember where that kind of file goes. A rule answers " +
                                "instantly, works offline, and costs nothing — so the only " +
                                "files worth asking Claude about are the ones no rule covers."
                        } else {
                            "Tried in order, top first. A rule opens the folder picker " +
                                "already at the right folder; it never files anything " +
                                "without you confirming."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(rules, key = { "rule:" + it.extension + it.word + it.folder }) { rule ->
                    RuleRow(rule) { viewModel.removeRule(rule) }
                }
            }

            item {
                SectionHeading(
                    title = if (filed.isEmpty()) "SAFE TO CLEAR" else "SAFE TO CLEAR · ${filed.size}",
                    action = if (showFiled) "Hide" else "Show",
                    onAction = {
                        showFiled = !showFiled
                        if (showFiled) viewModel.refreshFiled()
                    },
                )
            }
            if (showFiled) {
                if (filed.isEmpty()) {
                    item {
                        Text(
                            text = "Nothing here yet. Once you file something, the original " +
                                "it was copied from is listed here so you know it is safe to " +
                                "remove.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item {
                        Text(
                            text = "Each of these has a copy that Magpie checked byte for " +
                                "byte, so the original is redundant. Magpie will not remove " +
                                "them — that is the fail-safe — but you can, in your file " +
                                "manager. Anything whose copy has since gone is dropped from " +
                                "this list rather than left saying something untrue.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(filed, key = { "filed:" + it.originalPath }) { entry ->
                        FiledRow(entry) { viewModel.forgetFiled(entry.originalPath) }
                    }
                }
            }

            item {
                SectionHeading(
                    title = "ALREADY IN YOUR FOLDERS",
                    action = if (showInFolders) "Hide" else "Show",
                    onAction = {
                        showInFolders = !showInFolders
                        if (showInFolders) viewModel.loadInFolders()
                    },
                )
            }
            if (showInFolders) {
                if (inFolders.isEmpty()) {
                    item {
                        Text(
                            text = "Nothing else in the folders Magpie watches.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item {
                        Text(
                            text = "Files that were already there when Magpie started " +
                                "watching. They are never offered by notification, but you " +
                                "can still file one from here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(inFolders, key = { "folder:" + it.path }) { file ->
                        FileRow(
                            file = file,
                            selecting = false,
                            selected = false,
                            onTap = { viewModel.beginFiling(file) },
                            onLongPress = {},
                            trailing = {},
                        )
                    }
                }
            }
        }
    }

    treeReport?.let { report ->
        BuildReportDialog(report, viewModel::dismissTreeReport)
    }

    when (val current = step) {
        is FilingStep.Consulting -> WorkingDialog(
            message = "Asking Claude about ${current.file.name}…",
            // Asking can take most of a minute on a poor connection, and no
            // convenience gets to hold a file hostage for that long.
            onSkip = viewModel::skipSuggestion,
        )

        is FilingStep.Suggested -> SuggestionDialog(
            step = current,
            onAccept = viewModel::acceptSuggestion,
            onDecline = viewModel::declineSuggestion,
        )

        is FilingStep.Rename -> RenameDialog(
            step = current,
            rules = rules,
            onConfirm = viewModel::confirmRename,
            onCancel = viewModel::cancelFiling,
        )

        is FilingStep.Working -> WorkingDialog(current.message)

        is FilingStep.Report -> ReportDialog(
            outcomes = current.outcomes,
            // Only offer a rule for something no rule already covers, so the
            // same offer does not come back every time.
            offer = current.outcomes.singleOrNull()
                ?.let { it as? MoveOutcome.Copied }
                ?.takeIf { Rules.match(it.fileName, rules) == null }
                ?.let { Rules.suggestFor(it.fileName, it.destination) },
            onRemember = viewModel::addRule,
            onDismiss = viewModel::dismissReport,
        )

        else -> Unit
    }
}

// ---- header ----------------------------------------------------------------

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.magpie_tile),
            contentDescription = stringResource(R.string.brand_tile_description),
            modifier = Modifier.size(76.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = "Catches files as they land, and files them where you want them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---- status ----------------------------------------------------------------

@Composable
private fun StatusCard(
    readiness: Readiness,
    watching: Boolean,
    onGrantAccess: () -> Unit,
    onAllowNotifications: () -> Unit,
    onWatchingChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Watch my download folders", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = watchingSummary(readiness, watching),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = watching,
                    onCheckedChange = onWatchingChange,
                    enabled = readiness.allFilesAccess,
                )
            }

            if (!readiness.allFilesAccess) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Magpie needs all-files access to see what lands in your " +
                            "download folders and to move it for you. Without it, downloads " +
                            "still work exactly as they do now — Magpie just cannot help.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onGrantAccess) { Text("Grant all-files access") }
                }
            }

            if (!readiness.notificationsAllowed) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Notifications are switched off, so Magpie cannot tell you " +
                            "when a file arrives. Everything it spots still shows up in the " +
                            "waiting list below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onAllowNotifications) { Text("Allow notifications") }
                }
            }
        }
    }
}

private fun watchingSummary(readiness: Readiness, watching: Boolean): String = when {
    !readiness.allFilesAccess -> "Off — all-files access is needed first."
    watching && readiness.serviceRunning -> "On. New files will be offered as they arrive."
    watching -> "On, but the background service is not running. Android may have stopped " +
        "it — turn this off and on again to restart it."

    else -> "Off. Nothing is being watched."
}

// ---- lists -----------------------------------------------------------------

@Composable
private fun SectionHeading(title: String, action: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = SectionLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: SpottedFile,
    selecting: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onTap() })
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${Formatting.fileSize(file.size)} · " +
                        "${Formatting.typeLabel(file.name)} · ${file.source}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing()
        }
    }
}

@Composable
private fun EmptyWaiting(watching: Boolean) {
    Text(
        text = if (watching) {
            "Nothing waiting. The next file that lands in Downloads or Screenshots " +
                "will show up here."
        } else {
            "Nothing waiting. Turn watching on and new downloads will appear here as " +
                "they arrive."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun ProblemCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onFile: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Scaffold puts the bottom bar flush against the window, and
                // the app is edge-to-edge, so the inset is ours to add.
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (count == 1) "1 file selected" else "$count files selected",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll) { Text("All") }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onFile, enabled = count > 0) { Text("File these") }
        }
    }
}

@Composable
private fun SuggestionsCard(
    settings: SuggestionSettings,
    onKeyChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPickLibrary: () -> Unit,
    onClearLibrary: () -> Unit,
) {
    val context = LocalContext.current
    var key by rememberSaveable { mutableStateOf(settings.apiKey) }
    val libraryName = remember(settings.library) {
        settings.library?.let { Destinations.label(context, it) }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ask Claude for a name", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (settings.usable) {
                            "On. Magpie will suggest a name and a folder before you file."
                        } else if (settings.enabled) {
                            "Needs an API key below before it can ask anything."
                        } else {
                            "Off. Magpie files everything without touching the network."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = settings.enabled, onCheckedChange = onEnabledChange)
            }

            if (settings.enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "This is the only part of Magpie that uses the internet. It sends " +
                        "the filename, its size and type, the folder it landed in, and the " +
                        "names of the folders in your library. It never sends the file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        onKeyChange(it)
                    },
                    singleLine = true,
                    label = { Text("Anthropic API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (settings.apiKey.isBlank()) {
                        "Get one from console.anthropic.com. It is kept on this phone only, " +
                            "in Magpie's own private storage."
                    } else {
                        "Saved on this phone only. Calls are billed to your own Anthropic account."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = if (libraryName == null) {
                        "Pick the folder your filing lives under, and Claude can also suggest " +
                            "which folder inside it a file belongs in. Without one, it only " +
                            "suggests a name."
                    } else {
                        "Suggesting folders from inside \"$libraryName\"."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickLibrary) {
                        Text(if (libraryName == null) "Choose library folder" else "Change")
                    }
                    if (libraryName != null) {
                        TextButton(onClick = onClearLibrary) { Text("Clear") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FiledRow(entry: Filed, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = entry.originalName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Copied to ${entry.destination}" +
                    if (entry.savedAs != entry.originalName) " as \"${entry.savedAs}\"" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = entry.originalPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Formatting.fileSize(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDismiss) { Text("Done with it") }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: Rule, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rule.describe(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRemove) { Text("Forget") }
        }
    }
}

@Composable
private fun TreeBuilderCard(
    text: String,
    plan: PlanResult?,
    onTextChange: (String) -> Unit,
    onBuild: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Paste a folder tree and Magpie will make it inside a folder you " +
                    "choose. Indent to nest. Folders that already exist are left exactly " +
                    "as they are, with everything in them — nothing is replaced or emptied.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text("Folder tree") },
                placeholder = { Text("PROJECT/\n  01_STILLS/\n    S01_ARRIVAL/") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            when (plan) {
                is PlanResult.Ready -> {
                    Text(
                        text = "${plan.folders.size} folders, deepest " +
                            "${plan.folders.maxOf { it.depth }} levels down.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Showing the paths before anything is made is the whole
                    // point: a mis-indented paste is obvious here and not after.
                    SelectionContainer {
                        Text(
                            text = plan.folders.joinToString("\n") { it.path },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is PlanResult.Rejected -> Text(
                    text = plan.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                null -> Unit
            }

            Button(
                onClick = onBuild,
                enabled = plan is PlanResult.Ready,
            ) { Text("Choose where and build") }
        }
    }
}

@Composable
private fun BuildReportDialog(report: BuildReport, onDismiss: () -> Unit) {
    val title = when {
        report.total == 0 -> "Nothing to make"
        !report.allWell && report.made.isEmpty() -> "Nothing was made"
        !report.allWell -> "${report.made.size} of ${report.total} made"
        report.made.isEmpty() -> "All of them were already there"
        else -> "${report.made.size} folders made"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (report.reused.isNotEmpty()) {
                    Text(
                        text = "${report.reused.size} were already there and were left as " +
                            "they were, with everything in them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (report.made.isNotEmpty()) {
                    Text("Made", style = SectionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer {
                        Text(
                            text = report.made.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (report.failed.isNotEmpty()) {
                    Text(
                        "Not made",
                        style = SectionLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for ((path, reason) in report.failed) {
                        Text(
                            text = "$path — $reason",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

// ---- dialogs ---------------------------------------------------------------

@Composable
private fun RenameDialog(
    step: FilingStep.Rename,
    rules: List<Rule>,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(step.file.path) { mutableStateOf(step.initial) }

    // What the file will actually be called: characters no folder accepts are
    // stripped, and the extension is put back if the edit lost it. Showing it
    // means the rename step never surprises anyone.
    val finalName = Naming.withExtensionOf(Naming.sanitise(name), step.file.name)
    val usable = Naming.isUsable(finalName)

    // Which rule this name will fire, shown as you type so the folder is never
    // a surprise a step later.
    val willMatch = if (usable) Rules.match(finalName, rules) else null

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Name it") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Call it what it is. The name decides where it goes: a rule " +
                        "that matches it opens the folder picker already at that folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = name.isNotBlank() && !usable,
                    label = { Text("File name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (usable) {
                        "Will be saved as \"$finalName\"."
                    } else {
                        "There would be no name left — only characters a folder will " +
                            "not accept, or nothing before the extension."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (usable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (willMatch != null) {
                    Text(
                        text = "That name matches a rule: the picker will open at " +
                            "\"${willMatch.folder}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (step.suggestions.size > 1) {
                    Text(
                        text = "OR USE",
                        style = SectionLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (suggestion in step.suggestions) {
                        Surface(
                            onClick = { name = suggestion },
                            color = if (suggestion == name) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = usable,
            ) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun WorkingDialog(message: String, onSkip: (() -> Unit)? = null) {
    AlertDialog(
        // A move in progress cannot be abandoned safely, so it has no way out;
        // anything that passes onSkip can be given up on without risk.
        onDismissRequest = onSkip ?: {},
        confirmButton = {
            if (onSkip != null) {
                TextButton(onClick = onSkip) { Text("Skip and file it myself") }
            }
        },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}

@Composable
private fun ReportDialog(
    outcomes: List<MoveOutcome>,
    offer: Rule?,
    onRemember: (Rule) -> Unit,
    onDismiss: () -> Unit,
) {
    // A duplicate still arrived safely, so it counts as filed; the line
    // underneath says where it actually went.
    val copied = outcomes.count {
        it is MoveOutcome.Copied || it is MoveOutcome.Duplicated
    }
    val title = when {
        copied == 0 -> if (outcomes.size == 1) "Not copied" else "Nothing copied"
        copied < outcomes.size -> "$copied of ${outcomes.size} filed"
        outcomes.any { it is MoveOutcome.Duplicated } -> "Filed, duplicates kept apart"
        outcomes.size == 1 -> "Filed"
        else -> "All $copied filed"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (outcome in outcomes) {
                    Column {
                        Text(
                            text = outcome.fileName,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        SelectionContainer {
                            Text(
                                text = explain(outcome),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (offer != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Do this again next time?",
                        style = SectionLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = offer.describe(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "A rule opens the picker at that folder straight away, with " +
                            "no waiting and nothing to pay. You still confirm every file, " +
                            "and you can forget the rule whenever you like.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (offer == null) null else {
            {
                TextButton(onClick = { onRemember(offer); onDismiss() }) { Text("Remember this") }
            }
        },
    )
}

@Composable
private fun SuggestionDialog(
    step: FilingStep.Suggested,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Claude suggests") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = step.file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Name it", style = SectionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(step.suggestion.name, style = MaterialTheme.typography.bodyLarge)
                }

                Text("File it in", style = SectionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = step.folderName
                        ?: "No folder in your library fitted — the picker will open where you left it.",
                    style = MaterialTheme.typography.bodyLarge,
                )

                if (step.suggestion.reason.isNotBlank()) {
                    Text(
                        text = step.suggestion.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "You still choose the folder yourself on the next screen, and you " +
                        "can edit the name after that.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Use this") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Choose myself") } },
    )
}

private fun explain(outcome: MoveOutcome): String = when (outcome) {
    is MoveOutcome.Copied -> buildString {
        append("Copied to ${outcome.destination}")
        if (outcome.savedAs != outcome.fileName) append(", as \"${outcome.savedAs}\"")
        append(", and checked against the original byte for byte. ")
        append("The original is still at ${outcome.originalPath} — Magpie never deletes ")
        append("anything, so remove it yourself when you are happy with the copy.")
        for (note in outcome.notes) {
            append(" ")
            append(note)
        }
    }

    is MoveOutcome.Duplicated -> buildString {
        when {
            outcome.sameContentAs != null && !outcome.nameWasTaken ->
                append(
                    "This is the same file as \"${outcome.sameContentAs}\", already in " +
                        "${outcome.destination} under a different name — the contents match " +
                        "exactly. "
                )

            outcome.sameContentAs != null ->
                append(
                    "\"${outcome.savedAs}\" was already in ${outcome.destination}, and the " +
                        "contents match exactly, so it is the same file twice. "
                )

            outcome.identical == false ->
                append(
                    "Something called \"${outcome.savedAs}\" was already in " +
                        "${outcome.destination}, but it is a different file — that one is " +
                        "${Formatting.fileSize(outcome.existingSize ?: 0L)} and this one is " +
                        "${Formatting.fileSize(outcome.incomingSize)}. "
                )

            else ->
                append(
                    "Something called \"${outcome.savedAs}\" was already in " +
                        "${outcome.destination}, and Magpie could not compare the contents " +
                        "to say whether it is the same file. "
                )
        }
        append("Nothing was written over: the copy went to ${outcome.duplicatesFolder} ")
        append("instead, and was checked byte for byte. ")
        append("The original is still at ${outcome.originalPath}.")
        for (note in outcome.notes) {
            append(" ")
            append(note)
        }
    }

    is MoveOutcome.Refused ->
        "Nothing was read or written. ${outcome.reason} Magpie only ever touches ordinary " +
            "files on your own storage."

    is MoveOutcome.Failed -> "Not copied. ${outcome.reason}"

    is MoveOutcome.Unchanged ->
        "It is already in ${outcome.destination} under that name, so nothing was changed."
}

// ---- settings shortcuts ----------------------------------------------------

private fun openAllFilesAccess(context: Context, viewModel: MainViewModel) {
    try {
        context.startActivity(viewModel.allFilesAccessIntent())
    } catch (e: ActivityNotFoundException) {
        // Some builds only offer the whole-device list rather than the per-app
        // screen; fall back to that before giving up on the user.
        openSettings(context, viewModel.allFilesAccessFallbackIntent(), viewModel)
    }
}

private fun openSettings(context: Context, intent: Intent, viewModel: MainViewModel) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        viewModel.report(
            "This phone has no settings screen for that (${e.message ?: "no matching activity"}). " +
                "Open Android settings, find Magpie under Apps, and grant it there."
        )
    }
}
