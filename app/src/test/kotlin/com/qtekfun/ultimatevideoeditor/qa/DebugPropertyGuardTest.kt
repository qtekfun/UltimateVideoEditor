package com.qtekfun.ultimatevideoeditor.qa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A `debug.uveditor.decode_gap=0` left on the phone by a diagnostic run silently disabled the decoder fix, so three hours of
 * full exports measured nothing (DECISIONS.md, "No debug switch for the decoder's gap marking"). The rule: a debug property
 * may only add logging or shrink a cache; it may never switch off a correctness or speed path. This fails when the code reads a
 * `debug.uveditor.*` property that is not on the allow-list below, so a new one has to be argued for here, in review.
 * `scripts/qa-smoke.sh` checks the other half: no such property is set on the device.
 */
class DebugPropertyGuardTest {
    /** Every property the code may read, and why it is harmless. */
    private val allowed = mapOf(
        "debug.uveditor.timeline_stats" to "logs the timeline renderer's frame statistics",
        "debug.uveditor.export_perf" to "logs per-frame export timing (value 2 adds a glFinish to separate GPU time; diagnostics only)",
        "debug.uveditor.atlas_bytes" to "debug builds only: shrinks the thumbnail atlas to exercise eviction",
    )

    private val reading = Regex("""debug\.uveditor\.[A-Za-z0-9_.]+""")

    private fun codeFiles(): List<File> {
        val cpp = File(QaSources.appDir, "src/main/cpp").walkTopDown()
            .filter { it.isFile && it.extension in setOf("cpp", "h", "hpp", "cc", "c") && !it.path.contains("/third_party/") }
        return cpp.toList() + QaSources.mainSources("kt", "java") +
            File(QaSources.appDir, "src/debug").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `the code reads only allow-listed debug properties`() {
        val unknown = mutableListOf<String>()
        for (file in codeFiles()) {
            file.readLines().forEachIndexed { i, line ->
                for (name in reading.findAll(line).map { it.value }) {
                    if (name !in allowed) unknown += "${QaSources.relative(file)}:${i + 1}: $name"
                }
            }
        }
        assertTrue(
            "a debug property that is not on the allow-list (it must never disable a correctness or speed path):\n" + unknown.joinToString("\n"),
            unknown.isEmpty(),
        )
    }

    @Test
    fun `native code reads no system property other than the allow-listed ones`() {
        val offences = mutableListOf<String>()
        for (file in codeFiles().filter { it.extension in setOf("cpp", "h", "hpp", "cc") }) {
            val text = file.readText()
            for (call in QaSources.callArguments(text, "__system_property_get")) {
                val name = call.firstOrNull().orEmpty().trim('"')
                if (name !in allowed) offences += "${QaSources.relative(file)}: __system_property_get($name)"
            }
        }
        assertTrue("native system property reads outside the allow-list:\n" + offences.joinToString("\n"), offences.isEmpty())
    }

    @Test
    fun `no property may switch a feature off`() {
        // The removed switches, by name, so that bringing one back fails with a pointer to the decision.
        val removed = listOf("decode_gap", "export_enc_flags")
        val text = codeFiles().joinToString("\n") { it.readText() }
        for (name in removed) assertTrue("debug.uveditor.$name was removed (DECISIONS.md, \"No debug switch\")", !text.contains("debug.uveditor.$name"))
    }

    @Test
    fun `a script that sets a debug property resets it in a trap`() {
        val missing = File(QaSources.repoDir, "scripts").listFiles { f -> f.extension == "sh" }.orEmpty()
            .filter { script ->
                val text = script.readText()
                // Reads (getprop) and the allowed log tag are fine; writing a debug.uveditor.* value needs the trap that undoes it.
                Regex("""setprop\s+debug\.uveditor\.""").containsMatchIn(text) && !Regex("""\btrap\b.*setprop\s+debug\.uveditor\.""").containsMatchIn(text)
            }
            .map { it.name }
        assertEquals("scripts that set a debug.uveditor.* property without a trap that resets it: $missing", emptyList<String>(), missing)
    }
}
