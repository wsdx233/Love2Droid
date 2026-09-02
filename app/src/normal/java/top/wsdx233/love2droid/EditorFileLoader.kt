package top.wsdx233.love2droid

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal enum class EditorLineEnding(val value: String) {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r"),
}

internal data class EditorFileSnapshot(
    val length: Long,
    val lastModified: Long,
)

internal sealed interface EditorFileLoadResult {
    data class Text(
        val content: String,
        val lineEnding: EditorLineEnding,
        val snapshot: EditorFileSnapshot,
    ) : EditorFileLoadResult

    data class TooLarge(val size: Long) : EditorFileLoadResult
    data object Binary : EditorFileLoadResult
    data object InvalidUtf8 : EditorFileLoadResult
    data object ChangedDuringRead : EditorFileLoadResult
    data object Missing : EditorFileLoadResult
}

internal object EditorFileLoader {
    const val MAX_EDITOR_BYTES = 5L * 1024 * 1024

    fun snapshot(file: File): EditorFileSnapshot? {
        if (!file.isFile) return null
        return EditorFileSnapshot(file.length(), file.lastModified())
    }
    fun load(file: File, maxBytes: Long = MAX_EDITOR_BYTES): EditorFileLoadResult {
        require(maxBytes in 0..(Int.MAX_VALUE - 1).toLong()) { "Editor limit is out of range" }
        val before = snapshot(file) ?: return EditorFileLoadResult.Missing
        if (before.length > maxBytes) return EditorFileLoadResult.TooLarge(before.length)

        val bytes = ByteArray(maxBytes.toInt() + 1)
        var count = 0
        try {
            file.inputStream().use { input ->
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                }
            }
        } catch (_: java.io.FileNotFoundException) {
            return EditorFileLoadResult.Missing
        } catch (error: IOException) {
            throw error
        }
        if (count > maxBytes) return EditorFileLoadResult.TooLarge(count.toLong())

        var hasNul = false
        var controlBytes = 0
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xff
            if (value == 0) hasNul = true
            if (value < 0x20 && value != '\n'.code && value != '\r'.code && value != '\t'.code && value != '\u000C'.code) {
                controlBytes++
            }
        }
        if (hasNul) return EditorFileLoadResult.Binary
        if (controlBytes > 0 && controlBytes * 100 > count.coerceAtLeast(1)) {
            return EditorFileLoadResult.Binary
        }

        val content = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, count))
                .toString()
                .removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            return EditorFileLoadResult.InvalidUtf8
        }
        val after = snapshot(file) ?: return EditorFileLoadResult.Missing
        if (after != before) return EditorFileLoadResult.ChangedDuringRead
        return EditorFileLoadResult.Text(content, detectLineEnding(content), after)
    }

    fun normalizeLineEndings(content: String, lineEnding: EditorLineEnding): String {
        if (content.isEmpty()) return content
        return content.replace("\r\n", "\n").replace('\r', '\n').replace("\n", lineEnding.value)
    }

    private fun detectLineEnding(content: String): EditorLineEnding {
        var lf = 0
        var crlf = 0
        var cr = 0
        var index = 0
        while (index < content.length) {
            when (content[index]) {
                '\r' -> if (index + 1 < content.length && content[index + 1] == '\n') {
                    crlf++
                    index++
                } else {
                    cr++
                }
                '\n' -> lf++
            }
            index++
        }
        return when {
            crlf >= lf && crlf >= cr && crlf > 0 -> EditorLineEnding.CRLF
            cr >= lf && cr > 0 -> EditorLineEnding.CR
            else -> EditorLineEnding.LF
        }
    }
}
