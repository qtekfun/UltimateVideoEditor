package com.qtekfun.ultimatevideoeditor.qa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A package rename (or a moved class) breaks JNI only at run time: the app starts, then dies with `UnsatisfiedLinkError` on the first
 * native call. This cross-checks, on the JVM, every Kotlin `external fun` against the `Java_<package>_<Class>_<method>` symbols
 * the JNI sources define (directly or through a `JNI_FN` macro), in both directions, and every class named by a JNI string
 * (`FindClass`) against the Kotlin sources.
 */
class JniSymbolsTest {
    private fun mangle(text: String): String = buildString {
        for (c in text) when {
            c == '.' || c == '/' -> append('_')
            c == '_' -> append("_1")
            c == ';' -> append("_2")
            c == '[' -> append("_3")
            c.isLetterOrDigit() && c.code < 128 -> append(c)
            else -> append("_0").append(String.format("%04x", c.code))
        }
    }

    private val classDecl = Regex("""^(?:(?:internal|public|private|data|sealed|abstract|open|enum)\s+)*(?:class|object|interface)\s+(\w+)""")
    private val externalFun = Regex("""\bexternal\s+fun\s+(\w+)""")

    /** `Java_<mangled class>_<mangled method>` for each external function, with where it was declared. */
    private fun kotlinSymbols(): Map<String, String> {
        val symbols = linkedMapOf<String, String>()
        for (file in QaSources.mainSources("kt")) {
            val lines = file.readLines()
            if (lines.none { externalFun.containsMatchIn(it) }) continue
            val pkg = lines.first { it.startsWith("package ") }.removePrefix("package ").trim()
            var current: String? = null
            lines.forEachIndexed { i, line ->
                classDecl.find(line)?.let { current = it.groupValues[1] }
                externalFun.find(line)?.let { m ->
                    val owner = checkNotNull(current) { "${QaSources.relative(file)}:${i + 1}: external fun outside a top-level class or object" }
                    val symbol = "Java_" + mangle("$pkg.$owner") + "_" + mangle(m.groupValues[1])
                    val where = "${QaSources.relative(file)}:${i + 1}"
                    assertTrue("$symbol declared twice (overloads need a signature suffix; $where)", symbols.put(symbol, where) == null)
                }
            }
        }
        return symbols
    }

    private fun jniFiles(): List<File> = File(QaSources.appDir, "src/main/cpp/jni").listFiles { f -> f.extension == "cpp" }.orEmpty().sorted()

    /** Symbols the JNI sources define, resolving `#define JNI_FN(name) Java_x_##name` per file. */
    private fun nativeSymbols(): Map<String, String> {
        val symbols = linkedMapOf<String, String>()
        val macro = Regex("""#define\s+JNI_FN\s*\(\s*name\s*\)\s+(Java_\w+?)_##name""")
        val macroUse = Regex("""\bJNI_FN\s*\(\s*(\w+)\s*\)\s*\(""")
        val direct = Regex("""\b(Java_\w+)\s*\(""")
        for (file in jniFiles()) {
            val text = file.readText()
            val prefix = macro.find(text)?.groupValues?.get(1)
            if (prefix != null) macroUse.findAll(text).forEach { symbols["${prefix}_${it.groupValues[1]}"] = file.name }
            direct.findAll(text).forEach { symbols[it.groupValues[1]] = file.name }
        }
        return symbols
    }

    @Test
    fun `every external fun has a native symbol and every native symbol has an external fun`() {
        val kotlin = kotlinSymbols()
        val native = nativeSymbols()
        assertTrue("no external functions found: the parser broke", kotlin.size > 50)
        val missing = kotlin.filterKeys { it !in native }.map { "${it.value}: ${it.key}" }
        val stale = native.filterKeys { it !in kotlin }.map { "${it.value}: ${it.key}" }
        assertTrue("Kotlin external fun without a JNI definition (UnsatisfiedLinkError at run time):\n" + missing.joinToString("\n"), missing.isEmpty())
        assertTrue("JNI definition without a Kotlin external fun (wrong package or class name):\n" + stale.joinToString("\n"), stale.isEmpty())
        assertEquals(kotlin.keys, native.keys)
    }

    @Test
    fun `classes named by JNI strings exist in the Kotlin sources`() {
        val literal = Regex(""""(com/qtekfun/ultimatevideoeditor/[A-Za-z0-9_/$]+)"""")
        val sources = QaSources.mainSources("kt")
        val problems = mutableListOf<String>()
        var found = 0
        for (file in QaSources.mainSources("cpp", "h")) {
            for (m in literal.findAll(file.readText())) {
                found++
                val path = m.groupValues[1]
                val pkg = path.substringBeforeLast('/').replace('/', '.')
                val simple = path.substringAfterLast('/').substringBefore('$')
                val declared = sources.any { kt ->
                    val text = kt.readText()
                    text.lineSequence().firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim() == pkg &&
                        Regex("""\b(class|object|interface)\s+$simple\b""").containsMatchIn(text)
                }
                if (!declared) problems += "${QaSources.relative(file)}: $path"
            }
        }
        assertTrue("JNI strings should name at least one class", found > 0)
        assertTrue("JNI class strings with no matching Kotlin declaration:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the application id and namespace match the Kotlin package`() {
        val gradle = File(QaSources.appDir, "build.gradle.kts").readText()
        assertTrue(gradle.contains("""namespace = "com.qtekfun.ultimatevideoeditor""""))
        assertTrue(gradle.contains("""applicationId = "com.qtekfun.ultimatevideoeditor""""))
        val dir = File(QaSources.appDir, "src/main/kotlin/com/qtekfun/ultimatevideoeditor")
        assertTrue("source tree is not where the package says", dir.isDirectory)
    }
}
