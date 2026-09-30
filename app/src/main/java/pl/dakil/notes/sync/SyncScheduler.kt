package pl.dakil.notes.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import pl.dakil.notes.data.SyncFrequency

/**
 * Periodic work on the platform's `JobScheduler`.
 *
 * Not WorkManager: it instantiates `Worker` subclasses reflectively and ships its own keep rules,
 * which is exactly what this codebase gives up R8 full mode to avoid, and it drags in Room for a
 * queue two jobs do not need. `JobScheduler` has been in the framework since long before `minSdk
 * 29` and costs nothing in the APK.
 */
object SyncScheduler {

    private const val JOB_BACKUP = 1001
    private const val JOB_SYNC = 1002

    fun scheduleBackup(context: Context, frequency: SyncFrequency, charging: Boolean, wifiOnly: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (frequency == SyncFrequency.MANUAL) {
            scheduler.cancel(JOB_BACKUP)
            return
        }
        scheduler.schedule(
            JobInfo.Builder(JOB_BACKUP, ComponentName(context, BackupJobService::class.java))
                .setPeriodic(frequency.intervalMs)
                // A backup writes to a folder, which may be a cloud client's — so the network
                // constraint is asked for even though the archive itself is written locally.
                .setRequiredNetworkType(
                    if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_NONE
                )
                .setRequiresCharging(charging)
                .setRequiresBatteryNotLow(true)
                // Survives a reboot, which is why the manifest asks for RECEIVE_BOOT_COMPLETED.
                .setPersisted(true)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
        )
    }

    fun scheduleSync(context: Context, frequency: SyncFrequency, wifiOnly: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (frequency == SyncFrequency.MANUAL) {
            scheduler.cancel(JOB_SYNC)
            return
        }
        scheduler.schedule(
            JobInfo.Builder(JOB_SYNC, ComponentName(context, SyncJobService::class.java))
                .setPeriodic(frequency.intervalMs)
                .setRequiredNetworkType(
                    if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
                )
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
        )
    }

    fun cancelAll(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(JOB_BACKUP)
        scheduler.cancel(JOB_SYNC)
    }
}
