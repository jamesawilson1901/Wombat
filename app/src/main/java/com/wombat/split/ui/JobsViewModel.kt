package com.wombat.split.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wombat.split.jobs.JobNames
import com.wombat.split.split.FolderSplitter
import com.wombat.split.split.SplitPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SplitJob(
    val id: Long,
    val name: String,
    val limitBytes: Long,
    val status: Status,
) {
    sealed interface Status {
        data object Scanning : Status
        data class Running(val bytesCopied: Long, val bytesTotal: Long) : Status
        data class Done(val result: FolderSplitter.Result) : Status
        data class Failed(val message: String) : Status
    }
}

class JobsViewModel(application: Application) : AndroidViewModel(application) {

    private val _jobs = MutableStateFlow<List<SplitJob>>(emptyList())
    val jobs: StateFlow<List<SplitJob>> = _jobs.asStateFlow()

    private var nextId = 0L

    /** Starts a split job; returns its auto-assigned name. */
    fun startJob(sourceTree: Uri, destinationTree: Uri, limitBytes: Long): String {
        val name = JobNames.next(_jobs.value.map { it.name })
        val id = nextId++
        _jobs.update { it + SplitJob(id, name, limitBytes, SplitJob.Status.Scanning) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val source = DocumentFile.fromTreeUri(context, sourceTree)
                    ?: error("Source folder is not accessible")
                val destination = DocumentFile.fromTreeUri(context, destinationTree)
                    ?: error("Destination folder is not accessible")

                val splitter = FolderSplitter(context)
                val scanned = splitter.scan(source)
                val plan = SplitPlanner.plan(scanned.map { it.entry }, limitBytes)

                update(id) { it.copy(status = SplitJob.Status.Running(0, plan.totalBytes)) }
                val result = splitter.execute(plan, scanned, destination, name) { copied, total ->
                    update(id) { it.copy(status = SplitJob.Status.Running(copied, total)) }
                }
                update(id) { it.copy(status = SplitJob.Status.Done(result)) }
            } catch (e: Exception) {
                update(id) { it.copy(status = SplitJob.Status.Failed(e.message ?: "Split failed")) }
            }
        }
        return name
    }

    private fun update(id: Long, transform: (SplitJob) -> SplitJob) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}
