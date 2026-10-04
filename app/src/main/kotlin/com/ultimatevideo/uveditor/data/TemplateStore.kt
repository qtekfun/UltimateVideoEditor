package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.domain.templates.BuiltInTemplates
import com.ultimatevideo.uveditor.domain.templates.ProjectTemplate
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/**
 * The user's own templates, one `.uvtemplate` file each under [dir] (app-private storage). The built-in ones
 * are not stored; [all] lists them first. A file that no longer parses is skipped, never fatal.
 */
class TemplateStore(private val dir: File) {

    fun userTemplates(): List<ProjectTemplate> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file ->
                try {
                    TemplateFile.decode(file.readText())
                } catch (e: TemplateFormatException) {
                    null
                } catch (e: IOException) {
                    null
                }
            }

    /** The built-in templates followed by the user's. */
    fun all(): List<ProjectTemplate> = BuiltInTemplates.all + userTemplates()

    /**
     * Stores [template] under a free id (a built-in id or one already used gets a new suffix) and returns what
     * was stored. @throws IOException when it cannot be written.
     */
    fun save(template: ProjectTemplate): ProjectTemplate {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        val taken = all().map { it.id }.toSet()
        var id = template.id.ifBlank { "user-template" }
        if (id in taken || id.startsWith(BUILT_IN_PREFIX)) id = "user-${shortHash(template.name + template.timeline.toString())}"
        var n = 2
        val base = id
        while (id in taken) id = "$base-${n++}"
        val stored = template.copy(id = id)
        val target = File(dir, safeFileName(id) + EXTENSION)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(TemplateFile.encode(stored))
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Cannot save the template")
        }
        return stored
    }

    /** Reads [text] as a template file and stores it. @throws TemplateFormatException, IOException */
    fun import(text: String): ProjectTemplate = save(TemplateFile.decode(text))

    fun delete(id: String): Boolean = File(dir, safeFileName(id) + EXTENSION).delete()

    companion object {
        const val EXTENSION = ".uvtemplate"
        private const val BUILT_IN_PREFIX = "builtin-"

        private fun safeFileName(id: String) = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("").take(64)

        private fun shortHash(text: String): String {
            val crc = CRC32().apply { update(text.toByteArray(Charsets.UTF_8)) }
            return java.lang.Long.toHexString(crc.value)
        }
    }
}
