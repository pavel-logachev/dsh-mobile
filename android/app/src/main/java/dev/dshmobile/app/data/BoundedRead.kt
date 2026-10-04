package dev.dshmobile.app.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** API26-compatible replacement for InputStream.readNBytes (Android API33). */
internal fun InputStream.readAtMost(maximum: Int): ByteArray {
    require(maximum >= 0)
    val output = ByteArrayOutputStream(minOf(maximum, 8192))
    val buffer = ByteArray(minOf(maximum, 8192))
    while (output.size() < maximum) {
        val count = read(buffer, 0, minOf(buffer.size, maximum - output.size()))
        if (count < 0) break
        if (count == 0) {
            val byte = read()
            if (byte < 0) break
            output.write(byte)
        } else output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
