package com.wombat.split.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.wombat.split.jobs.JobRepository
import com.wombat.split.jobs.SplitJob
import com.wombat.split.split.SplitService
import kotlinx.coroutines.flow.StateFlow

/** A source picked in the new-job dialog, waiting to be turned into a job. */
data class SourceSelection(
    val uri: Uri,
    val isFile: Boolean,
    val label: String,
)

class JobsViewModel(application: Application) : AndroidViewModel(application) {

    val jobs: StateFlow<List<SplitJob>> = JobRepository.jobs

    /**
     * Creates one job per selected source — each gets its own name from the
     * animal pool — and hands them all to the foreground service, which runs
     * them one after another.
     */
    fun startJobs(
        sources: List<SourceSelection>,
        destinationTree: Uri,
        limitBytes: Long,
        zipParts: Boolean,
    ) {
        for (source in sources) {
            val job = JobRepository.create(
                sourceUri = source.uri.toString(),
                destinationUri = destinationTree.toString(),
                limitBytes = limitBytes,
                zipParts = zipParts,
                sourceIsFile = source.isFile,
            )
            SplitService.start(getApplication(), job.id)
        }
    }

    fun cancelJob(id: Long) {
        SplitService.cancel(getApplication(), id)
    }
}
