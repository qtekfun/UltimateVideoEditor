package com.qtekfun.ultimatevideoeditor.data

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A document opened for random access (a zip's central directory needs it): [access] reads it by position until [close]. */
interface SeekableDocument : java.io.Closeable {
    val access: com.qtekfun.ultimatevideoeditor.data.interchange.RandomAccess
}

/** Reads and writes whole documents addressed by URI (SAF in the app, in-memory in tests). */
interface ProjectTransferIO {
    /** The document as a seekable file, or null when this source cannot offer one (the importer then reads it as a stream). */
    @Throws(IOException::class)
    fun openSeekable(uri: String): SeekableDocument? = null

    @Throws(IOException::class)
    fun read(uri: String): ByteArray

    @Throws(IOException::class)
    fun write(uri: String, bytes: ByteArray)

    /** A stream over the document, for files too big to hold in memory (bundles with media). */
    @Throws(IOException::class)
    fun openInput(uri: String): InputStream = ByteArrayInputStream(read(uri))

    /** A stream that replaces the document's content; closing it completes the write. */
    @Throws(IOException::class)
    fun openOutput(uri: String): OutputStream {
        val buffer = ByteArrayOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) = buffer.write(b)

            override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)

            override fun close() {
                this@ProjectTransferIO.write(uri, buffer.toByteArray())
            }
        }
    }
}

class ContentResolverTransferIO(private val resolver: ContentResolver) : ProjectTransferIO {
    override fun read(uri: String): ByteArray {
        val stream = resolver.openInputStream(Uri.parse(uri))
            ?: throw FileNotFoundException("Cannot open $uri for reading")
        return stream.use { it.readBytes() }
    }

    override fun write(uri: String, bytes: ByteArray) {
        // "wt" truncates so overwriting a longer file does not leave trailing bytes.
        val stream = resolver.openOutputStream(Uri.parse(uri), "wt")
            ?: throw FileNotFoundException("Cannot open $uri for writing")
        stream.use { it.write(bytes) }
    }

    override fun openInput(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("Cannot open $uri for reading")

    override fun openSeekable(uri: String): SeekableDocument? {
        val pfd = resolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
        // Positional reads on the descriptor itself: re-opening /proc/self/fd/N by path is refused (EACCES) for files that
        // the provider serves through FUSE (Downloads), so nothing may be opened again by path.
        val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
        return object : SeekableDocument {
            override val access = com.qtekfun.ultimatevideoeditor.data.interchange.FileRandomAccess(channel)
            override fun close() = pfd.close()
        }
    }

    override fun openOutput(uri: String): OutputStream =
        resolver.openOutputStream(Uri.parse(uri), "wt") ?: throw FileNotFoundException("Cannot open $uri for writing")
}
