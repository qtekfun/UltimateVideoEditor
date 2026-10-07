package com.ultimatevideo.uveditor.qa

import android.content.pm.ServiceInfo
import com.ultimatevideo.uveditor.ui.export.foregroundTypeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export foreground service crashed the app on start on Android 17 (targetSdk 36): `InvalidForegroundServiceTypeException:
 * Starting FGS with type none`, because androidx.core 1.19.1's `ServiceCompat.startForeground` passed no type. No test started the
 * service. These guard what a JVM can: the type is explicit and non-zero on every API the app runs on, it is one the manifest
 * declares and has the permission for, and nothing in the app can start a foreground service without a type.
 * The real start on a device is `scripts/qa-smoke.sh` (the "export service" check).
 */
class ExportServiceDeclarationTest {
    private val manifest = QaSources.main("AndroidManifest.xml").readText()

    private val typeByName = mapOf(
        "dataSync" to ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        "mediaProcessing" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
    )

    private val permissionByType = mapOf(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC to "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING to "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
    )

    private val serviceTag: String
        get() = Regex("""<service\b[^>]*ExportService[^>]*>""", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value
            ?: error("ExportService is not declared in the manifest")

    private fun declaredTypes(): Set<Int> {
        val attribute = Regex("""android:foregroundServiceType="([^"]+)"""").find(serviceTag)?.groupValues?.get(1)
            ?: error("ExportService declares no android:foregroundServiceType")
        return attribute.split('|').map { typeByName[it.trim()] ?: error("this test does not know service type '$it'") }.toSet()
    }

    @Test
    fun `the start type is explicit and not zero on every API from 31 to 37`() {
        for (sdk in 31..37) {
            val type = foregroundTypeFor(sdk)
            assertNotEquals("API $sdk must not start a foreground service with type none", 0, type)
            assertNotEquals("API $sdk must not use FOREGROUND_SERVICE_TYPE_MANIFEST", ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST, type)
        }
    }

    @Test
    fun `the start type is one the manifest declares`() {
        val declared = declaredTypes()
        for (sdk in 31..37) {
            assertTrue("API $sdk starts with ${foregroundTypeFor(sdk)}, the manifest declares $declared", foregroundTypeFor(sdk) in declared)
        }
    }

    @Test
    fun `every declared type holds its foreground service permission`() {
        val permissions = Regex("""<uses-permission\s[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertTrue("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE" in permissions)
        for (type in declaredTypes()) {
            assertTrue("the manifest declares type $type without ${permissionByType[type]}", permissionByType[type] in permissions)
        }
    }

    @Test
    fun `media processing from Android 15 and data sync before`() {
        for (sdk in 31..34) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foregroundTypeFor(sdk))
        for (sdk in 35..37) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, foregroundTypeFor(sdk))
        // A later release keeps the newest type rather than falling back.
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, foregroundTypeFor(40))
    }

    @Test
    fun `the service stays private`() {
        assertTrue("the export service must stay private", serviceTag.contains("""android:exported="false""""))
    }

    @Test
    fun `nothing in the app starts a foreground service without an explicit non-zero type`() {
        val offences = mutableListOf<String>()
        for (file in QaSources.mainSources("kt", "java")) {
            val text = file.readLines().joinToString("\n") { it.substringBefore("//") }
            // ServiceCompat.startForeground on androidx.core 1.19.1 started the service with type none on Android 17.
            if (Regex("""ServiceCompat\s*\.\s*startForeground""").containsMatchIn(text)) offences += "${QaSources.relative(file)}: ServiceCompat.startForeground"
            if (text.contains("FOREGROUND_SERVICE_TYPE_NONE")) offences += "${QaSources.relative(file)}: FOREGROUND_SERVICE_TYPE_NONE"
            for (args in QaSources.callArguments(text, "startForeground")) {
                val where = "${QaSources.relative(file)}: startForeground(${args.joinToString(", ")})"
                // (id, notification) alone starts with the manifest's types, which fails on a target of 34 and above.
                if (args.size < 3) offences += where
                else if (args[2] == "0" || args[2].endsWith("TYPE_NONE") || args[2].endsWith("TYPE_MANIFEST")) offences += where
            }
        }
        assertTrue("foreground service started without an explicit non-zero type:\n" + offences.joinToString("\n"), offences.isEmpty())
    }

    @Test
    fun `every startForeground in the export service passes foregroundType`() {
        val source = QaSources.main("kotlin/com/ultimatevideo/uveditor/ui/export/ExportService.kt").readText()
        val calls = QaSources.callArguments(source, "startForeground")
        assertTrue("expected the service to call startForeground", calls.isNotEmpty())
        for (args in calls) assertEquals("foregroundType()", args.getOrNull(2))
    }

    @Test
    fun `the checker itself flags the bad forms`() {
        fun bad(code: String) = QaSources.callArguments(code, "startForeground").any { it.size < 3 || it[2] == "0" }
        assertTrue(bad("startForeground(ID, build(x))"))
        assertTrue(bad("startForeground(ID, build(x), 0)"))
        assertTrue(!bad("startForeground(ID, build(x, y), foregroundType())"))
    }
}
