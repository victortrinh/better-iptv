package dev.betteriptv.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the app's process alive while a Sync or Matching runs ([BetterIptvApp.catalogJobs]), so
 * Fire OS doesn't end it when the Viewer switches to another app. It runs nothing itself: the work
 * stays in the app's scope, and the service stops once that is idle. A process ended anyway (force
 * stop, reboot) picks up on the next launch: a stale Sync starts again and Matching resumes.
 */
class CatalogWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called at once on every start: Android ends an app whose foreground service doesn't.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // Stops once idle, unless work started again meanwhile: its own start then stops it.
        watcher?.cancel()
        watcher = scope.launch {
            (application as BetterIptvApp).catalogJobs.first { it == 0 }
            stopSelf(startId)
        }
        // A process ended mid-work has nothing to resume here: the next launch does it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(getString(R.string.catalog_work_notification))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setSilent(true)
        .setOngoing(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "catalog-work"
        private const val NOTIFICATION_ID = 1

        /**
         * Starts the service, or tells a running one again. Android refuses a start from the background
         * on newer versions; the work then runs as before, in the app's scope alone.
         */
        fun start(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.catalog_work_channel))
                    .build(),
            )
            try {
                ContextCompat.startForegroundService(context, Intent(context, CatalogWorkService::class.java))
            } catch (e: IllegalStateException) {
                Log.w("Sync", "Couldn't keep the app alive for the catalog's work: ${e.message}")
            }
        }
    }
}
