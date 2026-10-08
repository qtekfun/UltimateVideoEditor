package com.qtekfun.ultimatevideoeditor.data.interchange

import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * A [MediaFolder] backed by a directory, standing in for a document tree. [free] and the fail switches model a full or
 * unplugged drive; [base] is the directory the addresses are relative to (`content://fake/<path below base>`).
 */
class FakeMediaFolder(
    val dir: File,
    var free: Long? = null,
    var failListing: Boolean = false,
    var failCreate: Boolean = false,
    private val base: File = dir,
) : MediaFolder {
    init {
        dir.mkdirs()
    }

    override val name: String get() = dir.name

    override fun children(): List<MediaChild> {
        if (failListing) throw IOException("unplugged")
        return dir.listFiles().orEmpty().map { MediaChild(it.name, it.isDirectory) }
    }

    override fun create(name: String, mimeType: String): MediaTarget {
        if (failCreate) throw IOException("read-only")
        val file = File(dir, name)
        check(file.createNewFile()) { "the import must not reuse a name: $name" }
        return object : MediaTarget {
            override val uri = "content://fake/" + file.relativeTo(base).path

            override fun openOutput(): OutputStream = file.outputStream()

            override fun freeBytes() = free

            override fun delete() = file.delete()
        }
    }

    override fun openFolder(name: String): MediaFolder {
        val child = File(dir, name)
        if (!child.isDirectory) throw IOException("no folder $name")
        return FakeMediaFolder(child, free, failListing, failCreate, base)
    }

    override fun createFolder(name: String): MediaFolder {
        if (failCreate) throw IOException("read-only")
        val child = File(dir, name)
        if (child.exists()) throw IOException("$name is taken")
        return FakeMediaFolder(child, free, failListing, failCreate, base)
    }

    override fun delete(): Boolean = dir.deleteRecursively()
}
