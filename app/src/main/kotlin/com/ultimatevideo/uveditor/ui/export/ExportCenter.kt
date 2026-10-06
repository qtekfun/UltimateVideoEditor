package com.ultimatevideo.uveditor.ui.export

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ultimatevideo.uveditor.engine.export.NativeExportRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one [ExportExecutor] of the process, created on first use. It is outside any activity or view model so that an
 * export survives rotation, leaving the editor and the activity being destroyed. Starting a job starts
 * [ExportService], which keeps the process in the foreground and shows the notification.
 */
object ExportCenter {
    private const val TAG = "UVExport"

    @Volatile
    private var instance: ExportExecutor? = null

    fun executor(context: Context): ExportExecutor {
        instance?.let { return it }
        val app = context.applicationContext
        return synchronized(this) {
            instance ?: ExportExecutor(
                io = ContentResolverExportIO(app),
                runner = NativeExportRunner(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                ioDispatcher = Dispatchers.IO,
                onStarted = { startService(app) },
            ).also { instance = it }
        }
    }

    private fun startService(app: Context) {
        try {
            app.startForegroundService(Intent(app, ExportService::class.java).setAction(ExportService.ACTION_START))
        } catch (e: RuntimeException) {
            // Not allowed to start a foreground service right now (e.g. the app is no longer in the foreground). The export
            // still runs, but the system may stop the process once the app is in the background.
            Log.w(TAG, "Could not start the export service; exporting without it", e)
        }
    }
}
