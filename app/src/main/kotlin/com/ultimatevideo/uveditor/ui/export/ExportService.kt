package com.ultimatevideo.uveditor.ui.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ultimatevideo.uveditor.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows the progress while an export runs, so it survives leaving the app, switching apps
 * and the screen turning off. The export itself is in [ExportExecutor]; this only mirrors its state into a notification.
 * It uses no network: the service types are about long-running work (media processing, data sync before Android 15).
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val executor = ExportCenter.executor(this)
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            executor.cancel()
            if (!executor.state.value.isRunning) stopSelf(startId)
            return START_NOT_STICKY
        }
        createChannel()
        notificationManager().cancel(RESULT_NOTIFICATION_ID) // the result of an earlier export is history now
        // The system gives a few seconds after startForegroundService() to call this, so it comes before anything else.
        // Platform call, not ServiceCompat: with androidx.core 1.19.1 on Android 17 the compat path started the service with type none,
        // which the system rejects (InvalidForegroundServiceTypeException). minSdk 31 has the three-argument form.
        val first = exportNotificationFor(executor.state.value) ?: PLACEHOLDER
        startForeground(NOTIFICATION_ID, build(first), foregroundType())
        if (watching == null) watching = scope.launch { watch(executor) }
        return START_NOT_STICKY
    }

    private suspend fun watch(executor: ExportExecutor) {
        executor.state.map { exportNotificationFor(it) to it }.distinctUntilChanged { a, b -> a.first == b.first && a.second.isRunning == b.second.isRunning }
            .collect { (model, state) ->
                if (state.isRunning && model != null) {
                    // Also re-enters the foreground when a second export starts before the service has stopped.
                    startForeground(NOTIFICATION_ID, build(model), foregroundType())
                } else if (!state.isRunning) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    if (model != null) notificationManager().notify(RESULT_NOTIFICATION_ID, build(model))
                    stopSelf(lastStartId)
                }
            }
    }

    private fun foregroundType(): Int = foregroundTypeFor(Build.VERSION.SDK_INT)

    /** Android 15+ (API 35): the system stops a media processing service after its daily budget (6 h) and expects a prompt stop. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "The system's time limit for the export service was reached (type $fgsType)")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Export", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of a movie export"
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun build(model: ExportNotificationModel): Notification {
        val open = openIntentFor(model.projectId)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (model.ongoing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_upload_done)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setOngoing(model.ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(!model.ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        builder.setContentIntent(open)
        if (model.ongoing) {
            builder.setProgress(PROGRESS_MAX, model.progressPercent ?: 0, model.indeterminate)
        }
        if (model.showCancel) {
            val cancel = PendingIntent.getService(
                this,
                1,
                Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Cancel", cancel)
        }
        return builder.build()
    }

    /**
     * Brings MainActivity forward (a running one gets onNewIntent, a killed one starts) with the project id, so the app can
     * open that project's editor with the export dialog. Without a project (the placeholder) it is a plain launch.
     */
    private fun openIntentFor(projectId: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ExportLaunch.ACTION_SHOW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (projectId.isNotEmpty()) intent.putExtra(ExportLaunch.EXTRA_PROJECT_ID, projectId)
        // One PendingIntent per project: UPDATE_CURRENT would otherwise rewrite the extras of an older notification's intent.
        return PendingIntent.getActivity(this, projectId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_START = "com.ultimatevideo.uveditor.export.START"
        const val ACTION_CANCEL = "com.ultimatevideo.uveditor.export.CANCEL"
        private const val TAG = "UVExport"
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 7001
        private const val RESULT_NOTIFICATION_ID = 7002
        private const val PROGRESS_MAX = 100
        private val PLACEHOLDER = ExportNotificationModel("Exporting", "Starting…", null, true, ongoing = true, showCancel = true)
    }
}

/**
 * The foreground service type to start the export service with on [sdk]. Never 0: Android 17 rejects a type of none
 * (InvalidForegroundServiceTypeException), and a type must be one the manifest declares and holds a permission for
 * (`ExportServiceDeclarationTest` checks both). Android 15 added media processing; before that the closest is data sync.
 */
internal fun foregroundTypeFor(sdk: Int): Int =
    if (sdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
