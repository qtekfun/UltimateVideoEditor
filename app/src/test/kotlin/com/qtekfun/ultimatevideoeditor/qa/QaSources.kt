package com.qtekfun.ultimatevideoeditor.qa

import java.io.File

/** Locates the project's own files from a JVM unit test (the working directory is `app/` under Gradle, the repo root in some IDEs). */
internal object QaSources {
    val appDir: File = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }.canonicalFile
    val repoDir: File = checkNotNull(appDir.parentFile) { "the app directory has no parent" }

    fun main(path: String): File = File(appDir, "src/main/$path")

    /** Every file under `src/main` with one of [extensions], third-party code excluded. */
    fun mainSources(vararg extensions: String): List<File> =
        File(appDir, "src/main").walkTopDown()
            .filter { it.isFile && it.extension in extensions && !it.path.contains("/third_party/") }
            .toList()

    fun relative(file: File): String = file.relativeTo(repoDir).path

    /** The top-level arguments of every call of [function] in [source] (parentheses and brackets nest; strings are not parsed). */
    fun callArguments(source: String, function: String): List<List<String>> {
        val calls = mutableListOf<List<String>>()
        var from = 0
        while (true) {
            val at = Regex("""\b${Regex.escape(function)}\s*\(""").find(source, from) ?: break
            var depth = 1
            var i = at.range.last + 1
            val args = mutableListOf<String>()
            val current = StringBuilder()
            while (i < source.length && depth > 0) {
                val c = source[i]
                when {
                    c == '(' || c == '[' || c == '{' -> { depth++; current.append(c) }
                    c == ')' || c == ']' || c == '}' -> { depth--; if (depth > 0) current.append(c) }
                    c == ',' && depth == 1 -> { args += current.toString().trim(); current.clear() }
                    else -> current.append(c)
                }
                i++
            }
            if (current.isNotBlank()) args += current.toString().trim()
            calls.add(args)
            from = i
        }
        return calls
    }
}
