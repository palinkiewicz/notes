package pl.dakil.notes

import android.content.Context

/**
 * The one [AppContainer] for the process, built on first ask.
 *
 * Not an `Application` subclass, deliberately. `Application.onCreate` runs on *every* process
 * start — including a process the system spawned only to run a background job — so anything eager
 * in it becomes cold-start cost on the launcher path as well. This builds what the entry point
 * actually asked for, keeps construction in one place for the background work to come, and needs no
 * manifest entry at all.
 */
object AppGraph {

    @Volatile
    private var container: AppContainer? = null

    fun get(context: Context): AppContainer =
        container ?: synchronized(this) {
            container ?: AppContainer(context.applicationContext).also {
                container = it
                it.start()
            }
        }
}
