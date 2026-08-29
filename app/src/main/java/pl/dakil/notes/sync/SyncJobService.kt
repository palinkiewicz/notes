package pl.dakil.notes.sync

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.dakil.notes.AppGraph

/** Runs a sync pass in the background, on the container the process already has. */
class SyncJobService : JobService() {

    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = AppGraph.get(this)
        job = container.applicationScope.launch {
            val outcome = container.syncCoordinator.syncNow()
            // `shouldRetry` only for a failure the next attempt might survive; a misconfigured
            // account would otherwise be retried on a backoff forever.
            jobFinished(params, outcome.shouldRetry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // The engine writes its state after every applied action, so a cancelled pass resumes
        // from what it finished rather than replanning against stale bookkeeping.
        job?.cancel()
        return true
    }
}
