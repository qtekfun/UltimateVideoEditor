package com.ultimatevideo.uveditor.ui.export

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ultimatevideo.uveditor.data.ContentResolverTransferIO
import com.ultimatevideo.uveditor.data.interchange.BundleChecker
import com.ultimatevideo.uveditor.data.interchange.BundleVerification
import com.ultimatevideo.uveditor.engine.export.NativeExportRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.IOException

/**
 * The long jobs of the process, each created on first use: the movie [ExportExecutor] and the project backup
 * [BundleExportExecutor]. They are outside any activity or view model so that a job survives rotation, leaving the editor and
 * the activity being destroyed. Starting a job starts [ExportService], which keeps the process in the foreground and shows the
 * notification. Only one long job runs at a time: each executor asks the other whether it is busy before it starts.
 */
object ExportCenter {
    private const val TAG = "UVExport"

    @Volatile
    private var instance: ExportExecutor? = null

    @Volatile
    private var bundleInstance: BundleExportExecutor? = null

    fun executor(context: Context): ExportExecutor {
        instance?.let { return it }
        val app = context.applicationContext
        return synchronized(this) {
            instance ?: ContentResolverExportIO(app).let { io ->
                ExportExecutor(
                    io = io,
                    runner = NativeExportRunner(),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    ioDispatcher = Dispatchers.IO,
                    onStarted = { startService(app) },
                    verifier = DeviceExportVerifier(io),
                    otherJobBusy = { bundleInstance?.state?.value?.let { if (it is BundleJobState.Running) "Backing up ${it.projectName}" else null } },
                ).also { instance = it }
            }
        }
    }

    /** The project backup executor; see [executor] for the movie export. */
    fun bundles(context: Context): BundleExportExecutor {
        bundleInstance?.let { return it }
        val app = context.applicationContext
        return synchronized(this) {
            bundleInstance ?: BundleExportExecutor(
                io = ContentResolverExportIO(app),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                ioDispatcher = Dispatchers.IO,
                onStarted = { startService(app) },
                verifier = deviceBundleVerifier(ContentResolverTransferIO(app.contentResolver)),
                otherJobBusy = { instance?.state?.value?.let { if (it is ExportJobState.Running) "Exporting ${it.projectName}" else null } },
            ).also { bundleInstance = it }
        }
    }

    private fun startService(app: Context) {
        try {
            app.startForegroundService(Intent(app, ExportService::class.java).setAction(ExportService.ACTION_START))
        } catch (e: RuntimeException) {
            // Not allowed to start a foreground service right now (e.g. the app is no longer in the foreground). The job
            // still runs, but the system may stop the process once the app is in the background.
            Log.w(TAG, "Could not start the export service; running without it", e)
        }
    }
}

/** Reads the saved bundle back by position (the descriptor of the document, no path), the way an import reads a package. */
internal fun deviceBundleVerifier(io: com.ultimatevideo.uveditor.data.ProjectTransferIO) = BundleVerifier { uri, written, cancelled ->
    if (cancelled()) {
        BundleVerification.Skipped
    } else {
        try {
            val document = io.openSeekable(uri) ?: return@BundleVerifier BundleVerification.CouldNotVerify("the provider cannot reopen the file")
            document.use { BundleChecker.check(it.access, written) }
        } catch (e: IOException) {
            BundleVerification.CouldNotVerify("the saved file could not be opened again (${e.javaClass.simpleName}: ${e.message})")
        } catch (e: SecurityException) {
            BundleVerification.CouldNotVerify("the permission to read the saved file was lost")
        }
    }
}
