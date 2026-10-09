package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.ui.text.resolve
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.qtekfun.ultimatevideoeditor.MainActivity
import com.qtekfun.ultimatevideoeditor.ui.language.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows the progress while a long job (a movie export, a project backup or a project import) runs, so it survives leaving the app, switching apps
 * and the screen turning off. The work itself is in [ExportExecutor], [BundleExportExecutor] and [BundleImportExecutor]; this only mirrors their state into a notification.
 * It uses no network: the service types are about long-running work (media processing, data sync before Android 15).
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watching = ArrayList<Job>()
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    // The notification is worded in the app language, which on Android 12 and 12L is the one picked in About.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val movie = ExportCenter.executor(this)
        val bundle = ExportCenter.bundles(this)
        val imports = ExportCenter.imports(this)
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            // Only one long job runs at a time, so Cancel on whichever notification is showing stops that one.
            movie.cancel()
            bundle.cancel()
            imports.cancel()
            if (!movie.state.value.isRunning && !bundle.state.value.isRunning && !imports.state.value.isRunning) stopSelf(startId)
            return START_NOT_STICKY
        }
        createChannel()
        notificationManager().cancel(RESULT_NOTIFICATION_ID) // the result of an earlier job is history now
        notificationManager().cancel(BUNDLE_RESULT_NOTIFICATION_ID)
        notificationManager().cancel(IMPORT_RESULT_NOTIFICATION_ID)
        // The system gives a few seconds after startForegroundService() to call this, so it comes before anything else.
        // Platform call, not ServiceCompat: with androidx.core 1.19.1 on Android 17 the compat path started the service with type none,
        // which the system rejects (InvalidForegroundServiceTypeException). minSdk 31 has the three-argument form.
        val first = bundleNotificationFor(bundle.state.value)?.takeIf { it.ongoing }
            ?: importNotificationFor(imports.state.value)?.takeIf { it.ongoing }
            ?: exportNotificationFor(movie.state.value) ?: PLACEHOLDER
        startForeground(NOTIFICATION_ID, build(first), foregroundType())
        if (watching.isEmpty()) {
            watching += scope.launch {
                watch(movie.state, { exportNotificationFor(it) }, { it.isRunning }, RESULT_NOTIFICATION_ID, movie, bundle, imports)
            }
            watching += scope.launch {
                watch(bundle.state, { bundleNotificationFor(it) }, { it.isRunning }, BUNDLE_RESULT_NOTIFICATION_ID, movie, bundle, imports)
            }
            watching += scope.launch {
                // A running import whose dialog has not been shown yet (the first moments) has no notification: the service waits too.
                watch(imports.state, { importNotificationFor(it) }, { it.isRunning }, IMPORT_RESULT_NOTIFICATION_ID, movie, bundle, imports)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Mirrors one job's state into the notification. A result is only posted for a job this service saw running: the state of an
     * older, unacknowledged job is not news. The service stops when neither job is running.
     */
    private suspend fun <S> watch(
        states: Flow<S>,
        model: (S) -> ExportNotificationModel?,
        running: (S) -> Boolean,
        resultId: Int,
        movie: ExportExecutor,
        bundle: BundleExportExecutor,
        imports: BundleImportExecutor,
    ) {
        var wasRunning = false
        states.map { model(it) to running(it) }.distinctUntilChanged().collect { (shown, isRunning) ->
            if (isRunning && shown != null) {
                wasRunning = true
                // Also re-enters the foreground when a second job starts before the service has stopped.
                startForeground(NOTIFICATION_ID, build(shown), foregroundType())
            } else if (!isRunning && wasRunning) {
                wasRunning = false
                val otherRunning = movie.state.value.isRunning || bundle.state.value.isRunning || imports.state.value.isRunning
                if (!otherRunning) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                if (shown != null) notificationManager().notify(resultId, build(shown))
                if (!otherRunning) stopSelf(lastStartId)
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
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notif_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun build(model: ExportNotificationModel): Notification {
        val open = openIntentFor(model.projectId, model.bundle, model.import)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (model.ongoing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_upload_done)
            .setContentTitle(model.title.resolve(this))
            .setContentText(model.text.resolve(this))
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
        model.shareUri?.let { uri ->
            val chooser = shareBundleIntent(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.addAction(0, getString(R.string.common_share), PendingIntent.getActivity(this, 3, chooser, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (model.showCancel) {
            val cancel = PendingIntent.getService(
                this,
                1,
                Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, getString(R.string.common_cancel), cancel)
        }
        return builder.build()
    }

    /**
     * Brings MainActivity forward (a running one gets onNewIntent, a killed one starts) with the project id, so the app can
     * open that project's editor with the export dialog. Without a project (the placeholder) it is a plain launch.
     */
    private fun openIntentFor(projectId: String, bundle: Boolean, import: Boolean): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ExportLaunch.ACTION_SHOW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (projectId.isNotEmpty()) intent.putExtra(ExportLaunch.EXTRA_PROJECT_ID, projectId)
        if (bundle) intent.putExtra(ExportLaunch.EXTRA_BUNDLE, true)
        if (import) intent.putExtra(ExportLaunch.EXTRA_IMPORT, true)
        // One PendingIntent per project: UPDATE_CURRENT would otherwise rewrite the extras of an older notification's intent.
        return PendingIntent.getActivity(this, projectId.hashCode() * 4 + (if (bundle) 1 else 0) + (if (import) 2 else 0), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_START = "com.qtekfun.ultimatevideoeditor.export.START"
        const val ACTION_CANCEL = "com.qtekfun.ultimatevideoeditor.export.CANCEL"
        private const val TAG = "UVExport"
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 7001
        private const val RESULT_NOTIFICATION_ID = 7002
        private const val BUNDLE_RESULT_NOTIFICATION_ID = 7003
        private const val IMPORT_RESULT_NOTIFICATION_ID = 7004
        private const val PROGRESS_MAX = 100
        private val PLACEHOLDER = ExportNotificationModel(UiText.res(R.string.notif_placeholder_title), UiText.res(R.string.notif_placeholder_text), null, true, ongoing = true, showCancel = true)
    }
}

/** The chooser that shares a saved backup file (a `.uvbundle` is a zip with a name of our own, so the generic binary type). */
fun shareBundleIntent(uri: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, null)
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
