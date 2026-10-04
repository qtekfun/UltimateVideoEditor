package com.ultimatevideo.uveditor

import android.app.Application
import android.os.Build
import com.ultimatevideo.uveditor.crash.CrashContext
import com.ultimatevideo.uveditor.crash.CrashHandler
import com.ultimatevideo.uveditor.crash.AndroidProcessExitSource
import com.ultimatevideo.uveditor.crash.CrashReportStore
import com.ultimatevideo.uveditor.crash.ProcessExitRecorder
import com.ultimatevideo.uveditor.ui.about.AppVersion
import java.io.File

class UVEditorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Local only: the report is a text file in private storage that the About screen can show, copy or share
        // when the user asks. Nothing is sent anywhere.
        val store = CrashReportStore(File(filesDir, "crash"))
        val context = CrashContext(
            appVersion = AppVersion.of(this).display,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
        )
        CrashHandler.install(store = store, context = context)
        // The Java handler cannot see a segmentation fault or an ANR: ask the system what happened to the previous
        // process and add a short local summary to the same report. Off the main thread, so start-up never waits.
        Thread({
            runCatching {
                ProcessExitRecorder(
                    source = AndroidProcessExitSource(this),
                    store = store,
                    handled = File(filesDir, "crash/last-exit-handled.txt"),
                    context = context,
                ).recordNewExits()
            }
        }, "exit-info").start()
    }
}
