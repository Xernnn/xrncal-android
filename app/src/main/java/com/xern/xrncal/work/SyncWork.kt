package com.xern.xrncal.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xern.xrncal.SyncEnd
import com.xern.xrncal.XrnCalApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * Syncs with the app closed, so an event made or moved on the laptop rings on the phone without
 * the app being opened: every 30 minutes with a network, and once soon after the phone starts.
 * Each sync sets the reminder alarm again (Graph.syncNow).
 */
object SyncWork {
    const val EVERY = "sync-every"
    const val NOW = "sync-now"
    const val EVERY_MINUTES = 30L

    /** Android can be a moment behind in giving a job the network it waited for. */
    const val BLOCKED_WAIT_MS = 3_000L

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun every(minutes: Long = EVERY_MINUTES): PeriodicWorkRequest = PeriodicWorkRequest.Builder(SyncWorker::class.java, maxOf(15L, minutes), TimeUnit.MINUTES)
        .setConstraints(network())
        .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
        .build()

    fun once(): OneTimeWorkRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setConstraints(network())
        .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
        .build()

    /**
     * Books the sync every [minutes] (Options, Synchronisation; 30 by default), or keeps the one
     * booked: UPDATE keeps its timing, so opening the app doesn't push it back, and a new interval
     * or a later version's request replaces this one's.
     */
    fun keep(context: Context, minutes: Int = EVERY_MINUTES.toInt()) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(EVERY, ExistingPeriodicWorkPolicy.UPDATE, every(minutes.toLong()))
    }

    /** Signed out: no more background syncs. */
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(EVERY)
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
    }

    /** One sync as soon as there is a network (after a reboot). */
    fun now(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, once())
    }
}

/**
 * One background sync. False asks WorkManager to try again later: while the app's process is alive,
 * even idle, WorkManager runs due work in it rather than as a job, and Android refuses an idle app
 * the network (xrnhub found it, 2026-11), which says nothing about the server. So a refusal is
 * waited out once, then left for the retry, and never shown as the server being unreachable.
 */
suspend fun backgroundSync(blocked: () -> Boolean, wait: suspend (Long) -> Unit, sync: suspend () -> SyncEnd): Boolean {
    if (blocked()) {
        wait(SyncWork.BLOCKED_WAIT_MS)
        if (blocked()) return false
    }
    return sync() != SyncEnd.REFUSED
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as XrnCalApp).graph
        val done = backgroundSync(::blocked, { delay(it) }) { graph.syncNow(refused = ::blocked) }
        return if (done) Result.success() else Result.retry()
    }

    /** Whether Android is refusing this app the network right now. */
    @Suppress("DEPRECATION") // no newer call says this about the calling app
    private fun blocked(): Boolean =
        applicationContext.getSystemService(ConnectivityManager::class.java).activeNetworkInfo?.detailedState == NetworkInfo.DetailedState.BLOCKED
}
