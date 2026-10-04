package com.ultimatevideo.uveditor

import android.app.Application
import android.os.Build
import com.ultimatevideo.uveditor.crash.CrashContext
import com.ultimatevideo.uveditor.crash.CrashHandler
import com.ultimatevideo.uveditor.crash.CrashReportStore
import com.ultimatevideo.uveditor.ui.about.AppVersion
import java.io.File

class UVEditorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Local only: the report is a text file in private storage that the About screen can show, copy or share
        // when the user asks. Nothing is sent anywhere.
        CrashHandler.install(
            store = CrashReportStore(File(filesDir, "crash")),
            context = CrashContext(
                appVersion = AppVersion.of(this).display,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
            ),
        )
    }
}
