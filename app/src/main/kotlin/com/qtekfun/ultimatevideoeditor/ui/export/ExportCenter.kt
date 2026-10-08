package com.qtekfun.ultimatevideoeditor.ui.export

import android.content.Context
import android.content.Intent
import android.util.Log
import com.qtekfun.ultimatevideoeditor.data.ContentResolverTransferIO
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleChecker
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleVerification
import com.qtekfun.ultimatevideoeditor.engine.export.NativeExportRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.IOException

/**
 * The long jobs of the process, each created on first use: the movie [ExportExecutor], the project backup
 * [BundleExportExecutor] and the project import [BundleImportExecutor]. They are outside any activity or view model so that a job survives rotation, leaving the editor and
 * the activity being destroyed. Starting a job starts [ExportService], which keeps the process in the foreground and shows the
 * notification. Only one long job runs at a time: each executor asks the other two whether they are busy before it starts.
 */
object ExportCenter {
    private const val TAG = "UVExport"

    @Volatile
    private var instance: ExportExecutor? = null

    @Volatile
    private var bundleInstance: BundleExportExecutor? = null

    @Volatile
    private var importInstance: BundleImportExecutor? = null

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
                    otherJobBusy = { runningJob(except = LongJobs.Kind.MOVIE) },
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
                otherJobBusy = { runningJob(except = LongJobs.Kind.BACKUP) },
            ).also { bundleInstance = it }
        }
    }

    /** The project import executor; see [executor] for the movie export. */
    fun imports(context: Context): BundleImportExecutor {
        importInstance?.let { return it }
        val app = context.applicationContext
        return synchronized(this) {
            importInstance ?: BundleImportExecutor(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                ioDispatcher = Dispatchers.IO,
                onStarted = { startService(app) },
                displayName = ContentResolverExportIO(app)::displayName,
                otherJobBusy = { runningJob(except = LongJobs.Kind.IMPORT) },
            ).also { importInstance = it }
        }
    }

    /** What the other long jobs are doing, in words for a refusal ("Exporting Holiday"), or null when none is running. */
    private fun runningJob(except: LongJobs.Kind): String? =
        LongJobs.describe(instance?.state?.value, bundleInstance?.state?.value, importInstance?.state?.value, except)

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
internal fun deviceBundleVerifier(io: com.qtekfun.ultimatevideoeditor.data.ProjectTransferIO) = BundleVerifier { uri, written, cancelled ->
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

/**
 * The one-long-job-at-a-time rule in words: which of the movie export, the project backup and the project import is running,
 * seen from the job that wants to start ([except] is that job). Pure, so the refusal each of them gives is tested.
 */
object LongJobs {
    enum class Kind { MOVIE, BACKUP, IMPORT }

    fun describe(movie: ExportJobState?, backup: BundleJobState?, import: ImportJobState?, except: Kind): String? {
        if (except != Kind.MOVIE) (movie as? ExportJobState.Running)?.let { return "Exporting ${it.projectName}" }
        if (except != Kind.BACKUP) (backup as? BundleJobState.Running)?.let { return "Backing up ${it.projectName}" }
        if (except != Kind.IMPORT) (import as? ImportJobState.Running)?.let { return "Importing ${it.sourceName}" }
        return null
    }
}
