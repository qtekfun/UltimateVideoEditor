package com.ultimatevideo.uveditor.data

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException

/** Reads and writes whole documents addressed by URI (SAF in the app, in-memory in tests). */
interface ProjectTransferIO {
    @Throws(IOException::class)
    fun read(uri: String): ByteArray

    @Throws(IOException::class)
    fun write(uri: String, bytes: ByteArray)
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
}
