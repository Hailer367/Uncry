package com.uncry

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Backup path for restoring [AppMonitorService] after boot/update/unlock.
 *
 * The direct startForegroundService() call in [BootReceiver] is officially
 * exempt from the Android 12+ background-start ban when it runs on a boot
 * broadcast, but OEM task killers, battery optimization, and Doze still kill
 * or block it in the wild. This worker runs inside WorkManager's own
 * foreground context (allowed from the background, quota-managed by the
 * system) and retries with exponential backoff until the service is up.
 */
class MonitorBootWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "MonitorBootWorker"
        const val WORK_NAME = "uncry-monitor-boot"
        private const val CHANNEL_ID = "uncry_boot"
        private const val NOTIF_ID = 2001

        fun enqueue(ctx: Context) {
            try {
                val req = OneTimeWorkRequestBuilder<MonitorBootWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(WORK_NAME)
                    .build()
                WorkManager.getInstance(ctx.applicationContext)
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
            } catch (e: Exception) {
                Log.w(TAG, "enqueue failed: ${e.message}")
            }
        }

        private fun foregroundInfoFor(ctx: Context): ForegroundInfo {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                try { nm.deleteNotificationChannel(CHANNEL_ID) } catch (_: Exception) {}
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            "Uncry startup",
                            NotificationManager.IMPORTANCE_MIN,
                        ).apply { setShowBadge(false) },
                    )
                }
            }
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(AppAlias.labelFor(AppAlias.current(ctx)))
                .setContentText("Running")
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                ForegroundInfo(NOTIF_ID, notif)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfoFor(applicationContext)

    override suspend fun doWork(): Result {
        if (AppMonitorService.running) return Result.success()
        return try {
            // Promote into a foreground context first: from here the service
            // start is not a background start, so no
            // ForegroundServiceStartNotAllowedException.
            setForeground(getForegroundInfo())
            AppMonitorService.start(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "boot start failed, will retry: ${e.message}")
            Result.retry()
        }
    }
}
