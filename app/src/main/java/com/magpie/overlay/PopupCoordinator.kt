package com.magpie.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.magpie.Graph
import com.magpie.api.FileMeta
import com.magpie.domain.LocalSuggester
import com.magpie.domain.PathGuard
import com.magpie.domain.RenameHeuristics
import com.magpie.service.Filer
import com.magpie.service.Notifications
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** All state one popup renders from; mutable state so the AI answer swaps in live (§6.3). */
class PopupSession(files: List<File>) {
    data class Entry(val path: String, val name: String, val size: Long)

    val entries: List<Entry> = files.map { Entry(it.absolutePath, it.name, it.length()) }
    val ticked = mutableStateMapOf<String, Boolean>().apply { entries.forEach { put(it.path, true) } }

    /** folder paths to offer; second = whether the AI produced them */
    val suggestions = mutableStateOf<List<String>>(emptyList())
    val fromAi = mutableStateOf(false)

    /** editable rename proposals, keyed by path — only present when proposed (§9) */
    val renames = mutableStateMapOf<String, String>()

    /** AI-suggested new-folder name, editable before creation (§6.2) */
    val newFolderName = mutableStateOf("")

    val error = mutableStateOf<String?>(null)
    val busy = mutableStateOf(false)
}

/**
 * Owns the overlay window queue: one popup at a time, groups queue behind it.
 * Falls back to the §6.4 notification path when the overlay isn't available
 * (e.g. overlay permission suppressed during phone calls).
 */
class PopupCoordinator(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = ArrayDeque<List<String>>()
    private var current: OverlayHandle? = null
    private var apiJob: Job? = null

    private class OverlayHandle(
        val view: ComposeView,
        val owner: OverlayLifecycleOwner,
        val session: PopupSession,
    )

    fun showGroup(paths: List<String>) {
        scope.launch { enqueue(paths, front = false) }
    }

    /** §13: everything in Download, batches of 20, one popup per batch. */
    fun enqueueBacklog(paths: List<String>) {
        scope.launch {
            paths.chunked(20).forEach { enqueue(it, front = false) }
        }
    }

    private suspend fun enqueue(paths: List<String>, front: Boolean) {
        val files = paths.map(::File).filter { it.isFile }
        if (files.isEmpty()) return
        if (current != null) {
            if (front) queue.addFirst(paths) else queue.addLast(paths)
            return
        }
        present(files)
    }

    private suspend fun present(files: List<File>) {
        val session = PopupSession(files)

        // Popup opens immediately with local suggestions (§6.3 MUST).
        val (localSuggestions, presets, decisions) = withContext(Dispatchers.IO) {
            val presets = Graph.db.presets().all()
            val decisions = Graph.db.decisions().recent(50)
            val local = LocalSuggester.suggest(files.first().name, decisions, presets)
            Triple(local, presets, decisions)
        }
        session.suggestions.value = localSuggestions
        files.forEach { f ->
            RenameHeuristics.proposeRename(f.name)?.let { session.renames[f.absolutePath] = it }
        }

        if (!Settings.canDrawOverlays(context)) {
            // §3 quirk: overlay may be suppressed (e.g. during calls) — fall
            // back to notifications rather than dropping the files.
            files.forEach { f ->
                val local = LocalSuggester.suggest(f.name, decisions, presets)
                Notifications.postFallback(context, f.absolutePath, f.name, local)
            }
            showNext()
            return
        }

        attachOverlay(session)

        // The API call runs in parallel and swaps the buttons in place when it
        // lands; on any failure the local suggestions simply stay (§6.3).
        apiJob = scope.launch {
            val ai = withContext(Dispatchers.IO) {
                Graph.claude.suggestForGroup(
                    files.map { FileMeta(it.name, it.length()) },
                    presets,
                    decisions.take(20),
                )
            } ?: return@launch
            val guard = Graph.pathGuard()
            val valid = guard.filterSuggestions(ai.suggestions, presets.map { p -> p.path })
            if (valid.isNotEmpty() && current?.session == session) {
                session.suggestions.value = (valid + session.suggestions.value).distinct().take(3)
                session.fromAi.value = true
            }
            ai.newFolderName?.let { name ->
                if (current?.session == session && session.newFolderName.value.isEmpty()) {
                    session.newFolderName.value = name.replace(Regex("""[\\/:*?"<>|]"""), " ").trim()
                }
            }
            ai.renames.forEach { (fileName, proposed) ->
                val entry = session.entries.firstOrNull { it.name == fileName } ?: return@forEach
                val ok = RenameHeuristics.acceptAiRename(fileName, proposed) ?: return@forEach
                if (current?.session == session && !session.renames.containsKey(entry.path)) {
                    session.renames[entry.path] = ok
                }
            }
        }
    }

    private fun attachOverlay(session: PopupSession) {
        val owner = OverlayLifecycleOwner()
        owner.create()
        val view = ComposeView(context).apply {
            owner.attachTo(this)
            setContent {
                PopupContent(
                    session = session,
                    onChoose = { dest -> onDestinationChosen(session, dest) },
                    onNewFolder = { name -> onNewFolder(session, name) },
                    onSomewhereElse = { onSomewhereElse(session) },
                    onNotNow = { dismiss() },
                )
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (rename fields need typing) + dimmed behind; not full-screen.
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.55f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            windowManager().addView(view, params)
            current = OverlayHandle(view, owner, session)
        } catch (e: Exception) {
            // addView can throw even when canDrawOverlays() said yes.
            owner.destroy()
            session.entries.forEach {
                Notifications.postFallback(context, it.path, it.name, session.suggestions.value)
            }
            scope.launch { showNext() }
        }
    }

    private fun onDestinationChosen(session: PopupSession, destFolder: String) {
        if (session.busy.value) return
        session.busy.value = true
        scope.launch {
            val ticked = session.entries.filter { session.ticked[it.path] == true }
            val unticked = session.entries.filter { session.ticked[it.path] != true }
            var failed = false
            for (entry in ticked) {
                val rename = session.renames[entry.path]?.takeIf { it.isNotBlank() && it != entry.name }
                val result = Graph.filer.performMove(
                    sourcePath = entry.path,
                    destFolder = destFolder,
                    finalName = rename,
                    suggestedBy = if (session.fromAi.value) "ai" else "local",
                    accepted = destFolder in session.suggestions.value,
                )
                if (result is Filer.MoveResult.Error) {
                    // §10: failures are loud — popup stays open, error inline.
                    session.error.value = result.message
                    session.busy.value = false
                    failed = true
                    break
                }
            }
            if (!failed) {
                dismiss(showNextGroup = false)
                // Split behaviour (§6.2): unticked files immediately get their
                // own fresh popup.
                if (unticked.isNotEmpty()) {
                    enqueue(unticked.map { it.path }, front = true)
                } else {
                    showNext()
                }
            }
        }
    }

    private fun onNewFolder(session: PopupSession, name: String) {
        val clean = name.replace(Regex("""[\\/:*?"<>|]"""), " ").trim()
        if (clean.isEmpty()) return
        scope.launch {
            val root = com.magpie.data.SettingsRepository.storageRoot
            val folder = File(root, clean)
            val created = withContext(Dispatchers.IO) {
                (folder.isDirectory || folder.mkdirs()).also { ok ->
                    if (ok) {
                        Graph.db.presets().upsert(
                            com.magpie.data.PresetFolder(path = folder.absolutePath, name = clean)
                        )
                    }
                }
            }
            if (created) onDestinationChosen(session, folder.absolutePath)
            else session.error.value = "Could not create folder $clean"
        }
    }

    private fun onSomewhereElse(session: PopupSession) {
        // SAF needs an Activity; hand the ticked files to MainActivity, which
        // opens the tree picker, adds the pick as a preset (§7.2) and moves.
        val ticked = session.entries.filter { session.ticked[it.path] == true }.map { it.path }
        val unticked = session.entries.filter { session.ticked[it.path] != true }.map { it.path }
        dismiss(showNextGroup = false)
        scope.launch {
            if (unticked.isNotEmpty()) enqueue(unticked, front = true) else showNext()
        }
        val intent = android.content.Intent(context, com.magpie.MainActivity::class.java).apply {
            action = com.magpie.MainActivity.ACTION_PICK_DESTINATION
            putExtra(com.magpie.MainActivity.EXTRA_FILES, ticked.toTypedArray())
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun dismiss(showNextGroup: Boolean = true) {
        apiJob?.cancel()
        apiJob = null
        current?.let { handle ->
            try {
                windowManager().removeView(handle.view)
            } catch (_: Exception) {
            }
            handle.owner.destroy()
        }
        current = null
        if (showNextGroup) scope.launch { showNext() }
    }

    private suspend fun showNext() {
        current = null
        val next = queue.removeFirstOrNull() ?: return
        val files = next.map(::File).filter { it.isFile }
        if (files.isEmpty()) showNext() else present(files)
    }

    private fun windowManager() =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
}
