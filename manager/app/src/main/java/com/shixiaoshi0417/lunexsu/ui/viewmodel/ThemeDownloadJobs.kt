package com.shixiaoshi0417.lunexsu.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** Keeps a cancelled download's late completion from removing its replacement. */
internal class ThemeDownloadJobs(
    private val scope: CoroutineScope,
    private val onActiveChanged: (Set<String>) -> Unit,
) {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    fun start(id: String, block: suspend () -> Unit) {
        val job = synchronized(lock) {
            if (jobs.containsKey(id)) return
            // Register before execution: an immediate dispatcher can finish launch inline.
            val newJob = scope.launch(start = CoroutineStart.LAZY) { block() }
            jobs[id] = newJob
            onActiveChanged(jobs.keys.toSet())
            // Also runs when the scope was already cancelled and the body never starts.
            newJob.invokeOnCompletion {
                synchronized(lock) {
                    if (jobs[id] === newJob) {
                        jobs.remove(id)
                        onActiveChanged(jobs.keys.toSet())
                    }
                }
            }
            newJob
        }
        job.start()
    }

    /** Publish only from the job started here, never from an obsolete collector. */
    suspend fun updateIfCurrent(id: String, update: () -> Unit) {
        val owner = currentCoroutineContext()[Job]
        synchronized(lock) {
            if (owner != null && owner.isActive && jobs[id] === owner) update()
        }
    }

    fun cancel(id: String, cancelDownload: () -> Unit) {
        synchronized(lock) {
            val job = jobs.remove(id)
            // A replacement cannot start between cancellation of the job and downloader.
            try {
                job?.cancel()
                cancelDownload()
            } finally {
                onActiveChanged(jobs.keys.toSet())
            }
        }
    }
}
