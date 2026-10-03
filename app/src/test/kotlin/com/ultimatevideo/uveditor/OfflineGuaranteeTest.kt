package com.ultimatevideo.uveditor

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app is offline by design (see docs/PRIVACY.md). This fails the build if a network permission,
 * a networking API, or an analytics / crash-reporting / ads dependency ever appears in the app.
 */
class OfflineGuaranteeTest {

    private val appDir: File = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }

    private fun sources(vararg extensions: String): List<File> =
        File(appDir, "src/main").walkTopDown()
            .filter { it.isFile && it.extension in extensions && !it.path.contains("/third_party/") }
            .toList()

    @Test
    fun `the manifest declares no network permission and no cleartext traffic`() {
        val manifest = File(appDir, "src/main/AndroidManifest.xml").readText()
        for (permission in listOf("INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_NETWORK_STATE", "NEARBY_WIFI_DEVICES")) {
            assertTrue("The manifest must not declare $permission", !manifest.contains("android.permission.$permission"))
        }
        assertTrue("Cleartext traffic must stay disabled", manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue("Backups must stay disabled", manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun `no networking api is used in the app sources`() {
        val banned = listOf(
            "java.net.HttpURLConnection", "javax.net.ssl", "java.net.Socket", "java.net.URL(", ".openConnection(", ".openStream(",
            "android.net.http", "android.net.ConnectivityManager", "android.app.DownloadManager", "android.webkit.WebView",
            "okhttp3", "retrofit2", "io.ktor", "com.google.firebase", "com.google.android.gms", "com.facebook", "io.sentry",
            "com.crashlytics", "com.amplitude", "com.mixpanel", "com.appsflyer", "com.adjust",
        )
        val offenders = sources("kt", "java").flatMap { file ->
            val text = file.readText()
            banned.filter { text.contains(it) }.map { "${file.name}: $it" }
        }
        assertTrue("Networking or tracking APIs found: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the native engine opens no sockets`() {
        val banned = listOf("<sys/socket.h>", "<netdb.h>", "<netinet/", "<arpa/inet.h>", "curl/curl.h", "getaddrinfo(", "socket(AF_")
        val offenders = sources("cpp", "h", "c", "cc", "hpp").flatMap { file ->
            val text = file.readText()
            banned.filter { text.contains(it) }.map { "${file.name}: $it" }
        }
        assertTrue("Network code found in the engine: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the build declares no analytics, crash reporting, ads or networking dependency`() {
        val build = File(appDir, "build.gradle.kts").readText()
        val catalog = File(appDir.parentFile ?: File(".."), "gradle/libs.versions.toml").takeIf { it.exists() }?.readText().orEmpty()
        val banned = listOf(
            "firebase", "crashlytics", "sentry", "okhttp", "retrofit", "ktor", "play-services", "analytics", "mixpanel",
            "amplitude", "appsflyer", "adjust", "admob", "facebook", "volley", "glide", "coil", "picasso",
        )
        val text = (build + "\n" + catalog).lowercase()
        val offenders = banned.filter { text.contains(it) }
        assertTrue("Banned dependencies found: $offenders", offenders.isEmpty())
    }
}
