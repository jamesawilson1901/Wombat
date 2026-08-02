package com.wombat.split.jobs

import android.content.Context
import com.wombat.split.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SplitJob(
    val id: Long,
    val name: String,
    val sourceUri: String,
    val destinationUri: String,
    val limitBytes: Long,
    val zipParts: Boolean,
    val sourceIsFile: Boolean,
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
            val chunkedFileCount: Int,
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
 *
 * The list is persisted to a JSON file in app-private storage so job history
 * survives the app being closed. Jobs that were still active when the
 * process died can't be resumed and reload as failed ("interrupted").
 */
object JobRepository {

    private val _jobs = MutableStateFlow<List<SplitJob>>(emptyList())
    val jobs: StateFlow<List<SplitJob>> = _jobs.asStateFlow()

    private val lock = Any()
    private var nextId = 1L

    private var storeFile: File? = null
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveMutex = Mutex()

    fun init(context: Context) {
        synchronized(lock) {
            if (storeFile != null) return
            val file = File(context.filesDir, "jobs.json")
            storeFile = file
            val interrupted = context.getString(R.string.error_interrupted)
            val loaded = runCatching { fromJson(file.readText()) }.getOrDefault(emptyList())
            val sanitized = loaded.map {
                if (it.isActive) it.copy(status = SplitJob.Status.Failed(interrupted)) else it
            }
            _jobs.value = sanitized
            nextId = (sanitized.maxOfOrNull { it.id } ?: 0L) + 1
            if (sanitized != loaded) persist()
        }
    }

    fun create(
        sourceUri: String,
        destinationUri: String,
        limitBytes: Long,
        zipParts: Boolean,
        sourceIsFile: Boolean,
    ): SplitJob {
        synchronized(lock) {
            val job = SplitJob(
                id = nextId++,
                name = JobNames.next(_jobs.value.map { it.name }),
                sourceUri = sourceUri,
                destinationUri = destinationUri,
                limitBytes = limitBytes,
                zipParts = zipParts,
                sourceIsFile = sourceIsFile,
                status = SplitJob.Status.Queued,
            )
            _jobs.update { it + job }
            persist()
            return job
        }
    }

    fun get(id: Long): SplitJob? = _jobs.value.firstOrNull { it.id == id }

    fun update(id: Long, transform: (SplitJob) -> SplitJob) {
        var statusTypeChanged = false
        _jobs.update { list ->
            list.map {
                if (it.id == id) {
                    val updated = transform(it)
                    if (updated.status::class != it.status::class) statusTypeChanged = true
                    updated
                } else {
                    it
                }
            }
        }
        // Running progress ticks arrive many times a second; only status
        // transitions are worth a disk write.
        if (statusTypeChanged) persist()
    }

    private fun persist() {
        val file = storeFile ?: return
        val snapshot = _jobs.value
        saveScope.launch {
            saveMutex.withLock {
                runCatching { file.writeText(toJson(snapshot).toString()) }
            }
        }
    }

    // --- JSON mapping -----------------------------------------------------

    private fun toJson(jobs: List<SplitJob>): JSONArray =
        JSONArray().apply { jobs.forEach { put(jobToJson(it)) } }

    private fun jobToJson(job: SplitJob): JSONObject = JSONObject().apply {
        put("id", job.id)
        put("name", job.name)
        put("sourceUri", job.sourceUri)
        put("destinationUri", job.destinationUri)
        put("limitBytes", job.limitBytes)
        put("zipParts", job.zipParts)
        put("sourceIsFile", job.sourceIsFile)
        put("status", statusToJson(job.status))
    }

    private fun statusToJson(status: SplitJob.Status): JSONObject = JSONObject().apply {
        when (status) {
            is SplitJob.Status.Queued -> put("type", "queued")
            is SplitJob.Status.Scanning -> put("type", "scanning")
            is SplitJob.Status.Running -> {
                put("type", "running")
                put("bytesCopied", status.bytesCopied)
                put("bytesTotal", status.bytesTotal)
            }
            is SplitJob.Status.Done -> {
                put("type", "done")
                put("partCount", status.partCount)
                put("fileCount", status.fileCount)
                put("totalBytes", status.totalBytes)
                put("chunkedFileCount", status.chunkedFileCount)
            }
            is SplitJob.Status.Failed -> {
                put("type", "failed")
                put("message", status.message)
            }
            is SplitJob.Status.Cancelled -> put("type", "cancelled")
        }
    }

    private fun fromJson(text: String): List<SplitJob> {
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SplitJob(
                id = o.getLong("id"),
                name = o.getString("name"),
                sourceUri = o.getString("sourceUri"),
                destinationUri = o.getString("destinationUri"),
                limitBytes = o.getLong("limitBytes"),
                zipParts = o.getBoolean("zipParts"),
                sourceIsFile = o.getBoolean("sourceIsFile"),
                status = statusFromJson(o.getJSONObject("status")),
            )
        }
    }

    private fun statusFromJson(o: JSONObject): SplitJob.Status = when (o.getString("type")) {
        "queued" -> SplitJob.Status.Queued
        "scanning" -> SplitJob.Status.Scanning
        "running" -> SplitJob.Status.Running(o.getLong("bytesCopied"), o.getLong("bytesTotal"))
        "done" -> SplitJob.Status.Done(
            o.getInt("partCount"),
            o.getInt("fileCount"),
            o.getLong("totalBytes"),
            o.getInt("chunkedFileCount"),
        )
        "failed" -> SplitJob.Status.Failed(o.getString("message"))
        "cancelled" -> SplitJob.Status.Cancelled
        else -> SplitJob.Status.Cancelled
    }
}
