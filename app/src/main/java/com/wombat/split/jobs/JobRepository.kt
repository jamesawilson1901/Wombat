package com.wombat.split.jobs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SplitJob(
    val id: Long,
    val name: String,
    val sourceUri: String,
    val destinationUri: String,
    val limitBytes: Long,
    val status: Status,
) {
    sealed interface Status {
        data object Queued : Status
        data object Scanning : Status
        data class Running(val bytesCopied: Long, val bytesTotal: Long) : Status
        data class Done(
            val partCount: Int,
            val fileCount: Int,
            val totalBytes: Long,
            val oversizedCount: Int,
        ) : Status

        data class Failed(val message: String) : Status
        data object Cancelled : Status
    }

    val isActive: Boolean
        get() = status is Status.Queued || status is Status.Scanning || status is Status.Running
}

/**
 * Single source of truth for split jobs. The UI observes [jobs]; the
 * foreground service reads job specs from here and writes status updates.
 */
object JobRepository {

    private val _jobs = MutableStateFlow<List<SplitJob>>(emptyList())
    val jobs: StateFlow<List<SplitJob>> = _jobs.asStateFlow()

    private val lock = Any()
    private var nextId = 1L

    fun create(sourceUri: String, destinationUri: String, limitBytes: Long): SplitJob {
        synchronized(lock) {
            val job = SplitJob(
                id = nextId++,
                name = JobNames.next(_jobs.value.map { it.name }),
                sourceUri = sourceUri,
                destinationUri = destinationUri,
                limitBytes = limitBytes,
                status = SplitJob.Status.Queued,
            )
            _jobs.update { it + job }
            return job
        }
    }

    fun get(id: Long): SplitJob? = _jobs.value.firstOrNull { it.id == id }

    fun update(id: Long, transform: (SplitJob) -> SplitJob) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}
