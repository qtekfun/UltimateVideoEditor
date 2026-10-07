package com.ultimatevideo.uveditor.qa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source-level bans for mistakes that only showed up on a device. Each one names the defect it comes from.
 */
class PlatformPitfallsTest {
    /**
     * Importing a package from the picker failed with EACCES: the reader re-opened `/proc/self/fd/N` by path, which Android refuses for
     * files a document provider serves through FUSE (Downloads). Read the descriptor itself (positional reads, `FileInputStream(pfd.fileDescriptor)`).
     * The native `openIndependent` may try the path because it falls back to dup().
     */
    @Test
    fun `Kotlin never opens a file descriptor by its path`() {
        val offences = QaSources.mainSources("kt", "java").flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                val isComment = code.startsWith("*") || code.startsWith("/*")
                if (!isComment && (code.contains("/proc/self/fd") || code.contains("/dev/fd/"))) "${QaSources.relative(file)}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertEquals("opening a descriptor by path is refused (EACCES) for provider files:\n" + offences.joinToString("\n"), emptyList<String>(), offences)
    }
}
