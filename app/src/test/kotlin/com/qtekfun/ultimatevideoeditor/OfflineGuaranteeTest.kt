package com.qtekfun.ultimatevideoeditor

import org.junit.Assert.assertEquals
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

    /** The only permissions the app may declare: running an export in the foreground and showing its notification (docs/PRIVACY.md). */
    private val allowedPermissions = setOf(
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.POST_NOTIFICATIONS",
    )

    private val networkPermissions = listOf("INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_NETWORK_STATE", "NEARBY_WIFI_DEVICES")

    private fun declaredPermissions(manifest: String): Set<String> =
        Regex("""<uses-permission(?:-sdk-\d+)?\s[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()

    /** What a manifest declares that is not on the allow-list; empty when it is fine. */
    private fun forbiddenPermissions(manifest: String): Set<String> = declaredPermissions(manifest) - allowedPermissions

    @Test
    fun `the manifest declares no network permission and no cleartext traffic`() {
        val manifest = File(appDir, "src/main/AndroidManifest.xml").readText()
        for (permission in networkPermissions) {
            assertTrue("The manifest must not declare $permission", !manifest.contains("android.permission.$permission"))
        }
        assertTrue("Cleartext traffic must stay disabled", manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue("Backups must stay disabled", manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun `the manifest declares exactly the foreground service and notification permissions`() {
        val manifest = File(appDir, "src/main/AndroidManifest.xml").readText()
        assertEquals(allowedPermissions, declaredPermissions(manifest))
    }

    @Test
    fun `the allow-list still rejects INTERNET and every other network permission`() {
        val real = File(appDir, "src/main/AndroidManifest.xml").readText()
        for (permission in networkPermissions) {
            val tampered = real.replace("<application", """<uses-permission android:name="android.permission.$permission" /><application""")
            assertEquals("$permission must be rejected", setOf("android.permission.$permission"), forbiddenPermissions(tampered))
        }
        // The attribute order and the sdk-specific variants do not hide a permission either.
        val sneaky = """<manifest><uses-permission-sdk-23 android:maxSdkVersion="34" android:name="android.permission.INTERNET"/></manifest>"""
        assertEquals(setOf("android.permission.INTERNET"), forbiddenPermissions(sneaky))
        assertTrue(forbiddenPermissions(real).isEmpty())
    }

    @Test
    fun `the foreground service is not exported and the app declares no other service or receiver`() {
        val manifest = File(appDir, "src/main/AndroidManifest.xml").readText()
        assertEquals(1, Regex("<service\\b").findAll(manifest).count())
        assertTrue(manifest.contains("android:name=\".ui.export.ExportService\"") && manifest.contains("android:exported=\"false\""))
        assertTrue("No receivers or providers", !manifest.contains("<receiver") && !manifest.contains("<provider"))
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
