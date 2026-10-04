package com.ultimatevideo.uveditor.ui.editor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ultimatevideo.uveditor.data.readAtMost
import java.io.IOException

/** Reads a picked `.cube` file through the content resolver, refusing anything implausibly large. */
class ContentResolverLutReader(private val context: Context) : LutFileReader {
    override fun read(uri: String): Pair<String, String> {
        val parsed = Uri.parse(uri)
        val name = context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: parsed.lastPathSegment ?: "LUT"
        val input = context.contentResolver.openInputStream(parsed) ?: throw IOException("The file cannot be opened")
        val text = input.use { stream ->
            val bytes = stream.readAtMost(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) throw IOException("The file is too large for a LUT")
            bytes.toString(Charsets.UTF_8)
        }
        return name to text
    }

    private companion object {
        /** A 65-point cube is about 8 MB of text; this leaves room for generous formatting. */
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
