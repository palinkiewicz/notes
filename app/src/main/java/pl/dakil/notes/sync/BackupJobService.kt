package pl.dakil.notes.sync

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.dakil.notes.AppGraph
import pl.dakil.notes.data.sync.BackupResult

/** Writes a scheduled archive to the folder the user chose. */
class BackupJobService : JobService() {

    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = AppGraph.get(this)
        job = container.applicationScope.launch {
            val result = container.backupRunner.backUpNow()
            jobFinished(params, result is BackupResult.Failed)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }
}
