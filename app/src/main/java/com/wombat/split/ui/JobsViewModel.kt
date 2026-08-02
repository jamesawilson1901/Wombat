package com.wombat.split.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.wombat.split.jobs.JobRepository
import com.wombat.split.jobs.SplitJob
import com.wombat.split.split.SplitService
import kotlinx.coroutines.flow.StateFlow

class JobsViewModel(application: Application) : AndroidViewModel(application) {

    val jobs: StateFlow<List<SplitJob>> = JobRepository.jobs

    /** Creates the job and hands it to the foreground service; returns its name. */
    fun startJob(sourceTree: Uri, destinationTree: Uri, limitBytes: Long): String {
        val job = JobRepository.create(
            sourceUri = sourceTree.toString(),
            destinationUri = destinationTree.toString(),
            limitBytes = limitBytes,
        )
        SplitService.start(getApplication(), job.id)
        return job.name
    }

    fun cancelJob(id: Long) {
        SplitService.cancel(getApplication(), id)
    }
}
