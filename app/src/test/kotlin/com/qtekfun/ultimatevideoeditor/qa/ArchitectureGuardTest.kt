package com.qtekfun.ultimatevideoeditor.qa

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guards (docs/ARCHITECTURE.md section 6, DECISIONS.md "Guards, not prose"). Two kinds:
 *
 *  - Size ratchet: no hand-written file may grow past its limit. Files that already do are on `src/test/arch/oversized.txt`;
 *    the list only shrinks (a listed file that is back under the limit must be removed, so the gain cannot be lost).
 *  - Dependency rules between packages. Existing violations are on the other lists in `src/test/arch` with the same only-shrinks semantics;
 *    a new violation fails the build.
 *
 * Everything reads sources as text, so the whole class runs in well under a second.
 */
class ArchitectureGuardTest {
    private val kotlinRoot = QaSources.main("kotlin/com/qtekfun/ultimatevideoeditor")
    private val app = "com.qtekfun.ultimatevideoeditor"

    /** Limits in lines. Kotlin 800: a file a reviewer can hold in mind; C++ 1500 (long numeric kernels read worse when split); tests are looser. */
    private object Limit {
        const val KOTLIN_MAIN = 800
        const val KOTLIN_TEST = 1500
        const val CPP_MAIN = 1500
        const val CPP_TEST = 2000
    }

    private fun allowList(name: String): Set<String> =
        File(QaSources.appDir, "src/test/arch/$name").readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    private fun ratchet(rule: String, fix: String, found: Set<String>, listed: Set<String>, listName: String) {
        val added = (found - listed).sorted()
        val stale = (listed - found).sorted()
        val message = buildString {
            if (added.isNotEmpty()) {
                appendLine("$rule: new violation(s). $fix")
                added.forEach { appendLine("  + $it") }
            }
            if (stale.isNotEmpty()) {
                appendLine("$rule: fixed or moved; remove these lines from app/src/test/arch/$listName so the list only shrinks:")
                stale.forEach { appendLine("  - $it") }
            }
        }
        assertTrue(message, added.isEmpty() && stale.isEmpty())
    }

    // --- size ---

    private fun sizeCandidates(): List<Pair<File, Int>> {
        val result = mutableListOf<Pair<File, Int>>()
        QaSources.mainSources("kt").forEach { result.add(it to Limit.KOTLIN_MAIN) }
        QaSources.mainSources("cpp", "h", "hpp", "cc").forEach {
            result.add(it to if (it.path.contains("/cpp/tests/")) Limit.CPP_TEST else Limit.CPP_MAIN)
        }
        for (dir in listOf("src/test", "src/androidTest")) {
            File(QaSources.appDir, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }
                .forEach { result.add(it to Limit.KOTLIN_TEST) }
        }
        return result
    }

    @Test
    fun filesStayUnderTheirSizeLimit() {
        val oversized = sizeCandidates().filter { (file, limit) -> file.useLines { it.count() } > limit }
            .map { (file, _) -> QaSources.relative(file) }.toSet()
        ratchet(
            rule = "File size (Kotlin ${Limit.KOTLIN_MAIN}, C++ ${Limit.CPP_MAIN}, C++ tests ${Limit.CPP_TEST}, Kotlin tests ${Limit.KOTLIN_TEST} lines)",
            fix = "Split the file by responsibility (docs/ARCHITECTURE.md section 6); do not add it to the list.",
            found = oversized,
            listed = allowList("oversized.txt"),
            listName = "oversized.txt",
        )
    }

    // --- dependencies ---

    private fun kotlinFiles(sub: String): List<File> =
        File(kotlinRoot, sub).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun imports(file: File): List<String> =
        file.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }

    private fun inPackageFolder(file: File) = file.relativeTo(kotlinRoot).path

    @Test
    fun domainIsPureKotlin() {
        val forbidden = Regex("""^(android\.|androidx\.|${Regex.escape(app)}\.(ui|engine|data)\.)""")
        val found = kotlinFiles("domain").filter { f -> imports(f).any { forbidden.containsMatchIn(it) } }
            .map { inPackageFolder(it) }.toSet()
        ratchet(
            rule = "domain/ must not import android.*, androidx.*, ui, engine or data",
            fix = "Move the type it needs into domain/ (or pass it in as a parameter); domain stays testable on the JVM.",
            found = found, listed = allowList("domain-imports.txt"), listName = "domain-imports.txt",
        )
    }

    @Test
    fun onlyEngineTouchesJni() {
        val jni = Regex("""\bexternal\s+fun\b|System\.loadLibrary\s*\(|System\.load\s*\(""")
        val engine = File(kotlinRoot, "engine").path + File.separator
        val found = QaSources.mainSources("kt").filter { !it.path.startsWith(engine) }
            .filter { jni.containsMatchIn(it.readText()) }.map { QaSources.relative(it) }
        assertTrue(
            "Only engine/ may declare JNI or load the native library (engineering principle 1); found in: $found",
            found.isEmpty(),
        )
    }

    @Test
    fun domainAndDataHaveNoCompose() {
        val found = (kotlinFiles("domain") + kotlinFiles("data")).filter { f ->
            imports(f).any { it.startsWith("androidx.compose.") || it.startsWith("androidx.activity.compose.") }
        }.map { inPackageFolder(it) }
        assertTrue("Compose belongs to ui/ only; found in: $found", found.isEmpty())
    }

    @Test
    fun uiUsesDataOnlyFromAllowListedFiles() {
        val data = Regex("""^${Regex.escape(app)}\.data\.""")
        val found = kotlinFiles("ui").filter { f -> imports(f).any { data.containsMatchIn(it) } }
            .map { inPackageFolder(it) }.toSet()
        ratchet(
            rule = "ui/ must not import data/ implementation classes",
            fix = "Depend on an interface or a model type owned by domain/ or ui/ and wire the data class in the factory.",
            found = found, listed = allowList("ui-data-imports.txt"), listName = "ui-data-imports.txt",
        )
    }
}
