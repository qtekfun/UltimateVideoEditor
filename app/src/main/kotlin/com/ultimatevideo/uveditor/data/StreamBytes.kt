package com.ultimatevideo.uveditor.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads at most [max] bytes from the stream. `InputStream.readNBytes` only exists from Android 13 (API 33), and the
 * app supports Android 12 (API 31), so bounded reads go through this loop instead.
 */
fun InputStream.readAtMost(max: Int): ByteArray {
    require(max >= 0) { "max must not be negative" }
    val out = ByteArrayOutputStream(minOf(max, 8192))
    val buffer = ByteArray(8192)
    var left = max
    while (left > 0) {
        val n = read(buffer, 0, minOf(buffer.size, left))
        if (n < 0) break
        out.write(buffer, 0, n)
        left -= n
    }
    return out.toByteArray()
}
