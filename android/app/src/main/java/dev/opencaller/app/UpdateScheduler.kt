package dev.opencaller.app

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * F4 scheduling: a weekly WorkManager job matching the pipeline's weekly
 * epoch. The user owns the schedule (PRD §5 promise 4): OFF cancels the
 * job entirely, WIFI_ONLY (default) constrains to unmetered networks.
 * Battery-not-low is always required — a DB refresh is never urgent.
 */
object UpdateScheduler {
  private const val WORK_NAME = "shard-update"

  /** Idempotent; call on app start and whenever the mode setting changes. */
  fun sync(context: Context) {
    val wm = WorkManager.getInstance(context)
    val mode = Prefs.updateMode(context)
    if (mode == Prefs.UpdateMode.OFF) {
      wm.cancelUniqueWork(WORK_NAME)
      return
    }
    val network = when (mode) {
      Prefs.UpdateMode.WIFI_ONLY -> NetworkType.UNMETERED
      else -> NetworkType.CONNECTED
    }
    val request = PeriodicWorkRequestBuilder<UpdateWorker>(7, TimeUnit.DAYS)
      .setConstraints(
        Constraints.Builder()
          .setRequiredNetworkType(network)
          .setRequiresBatteryNotLow(true)
          .build(),
      )
      .build()
    wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
  }
}

class UpdateWorker(context: Context, params: WorkerParameters) :
  Worker(context, params) {

  override fun doWork(): Result {
    // Mode may have changed to OFF after enqueue; re-check as a backstop.
    if (Prefs.updateMode(applicationContext) == Prefs.UpdateMode.OFF) {
      return Result.success()
    }
    val outcome = UpdateManager.checkAndApply(applicationContext)
    Log.i("OpenCaller", "scheduled update: $outcome")
    // The transaction is atomic and self-healing; a failed fetch simply
    // retries next period. Never Result.retry() — updates are not urgent.
    return Result.success()
  }
}
