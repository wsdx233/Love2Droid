package top.wsdx233.love2droid

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

internal object AndroidBinaryXmlEditor {
    fun customizeManifest(source: ByteArray, properties: AndroidProjectProperties): ByteArray {
        val value = properties.validated()
        require(readU16(source, 0) == RES_XML_TYPE) { "Invalid binary Android manifest" }
        val declaredSize = readI32(source, 4)
        require(declaredSize in 8..source.size) { "Invalid Android manifest size" }

        val pool = StringPool.parse(source, XML_HEADER_SIZE)
        val strings = pool.strings.toMutableList()
        val stringIndexes = strings.withIndex().associateTo(linkedMapOf()) { it.value to it.index }
        fun stringIndex(text: String): Int = stringIndexes.getOrPut(text) {
            strings.add(text)
            strings.lastIndex
        }

        val permissions = value.permissions.sorted()
        require(permissions.size <= AndroidProjectProperties.MAX_PERMISSIONS) { "Too many Android permissions" }
        val chunks = ArrayList<ByteArray>()
        var offset = XML_HEADER_SIZE + pool.chunkSize
        var permissionSlot = 0
        var skippedDepth = 0
        var manifestPatched = false
        var applicationPatched = false
        var gameActivityPatched = false

        while (offset < declaredSize) {
            require(offset + CHUNK_HEADER_SIZE <= declaredSize) { "Truncated Android manifest chunk" }
            val type = readU16(source, offset)
            val size = readI32(source, offset + 4)
            require(size >= CHUNK_HEADER_SIZE && offset + size <= declaredSize) { "Invalid Android manifest chunk" }
            val chunk = source.copyOfRange(offset, offset + size)
            if (skippedDepth > 0) {
                when (type) {
                    RES_XML_START_ELEMENT_TYPE -> skippedDepth += 1
                    RES_XML_END_ELEMENT_TYPE -> skippedDepth -= 1
                }
                offset += size
                continue
            }
            if (type == RES_XML_START_ELEMENT_TYPE) {
                val elementName = strings.getOrNull(readI32(chunk, NODE_NAME_OFFSET))
                    ?: throw IllegalArgumentException("Invalid Android manifest element")
                if (elementName == ELEMENT_USES_PERMISSION) {
                    if (permissionSlot >= permissions.size) {
                        skippedDepth = 1
                    } else {
                        patchStringAttribute(chunk, strings, ATTRIBUTE_NAME, stringIndex(permissions[permissionSlot]))
                        chunks += chunk
                    }
                    permissionSlot += 1
                    offset += size
                    continue
                }
                when (elementName) {
                    ELEMENT_MANIFEST -> {
                        patchStringAttribute(chunk, strings, ATTRIBUTE_PACKAGE, stringIndex(value.applicationId))
                        patchIntegerAttribute(
                            chunk,
                            strings,
                            ATTRIBUTE_VERSION_CODE,
                            value.versionCode,
                            stringIndex(value.versionCode.toString()),
                        )
                        patchStringAttribute(chunk, strings, ATTRIBUTE_VERSION_NAME, stringIndex(value.versionName))
                        manifestPatched = true
                    }
                    ELEMENT_APPLICATION -> {
                        patchStringAttribute(chunk, strings, ATTRIBUTE_LABEL, stringIndex(value.appName))
                        applicationPatched = true
                    }
                    ELEMENT_ACTIVITY -> {
                        val activityName = readStringAttribute(chunk, strings, ATTRIBUTE_NAME)
                        if (activityName == GAME_ACTIVITY_CLASS) {
                            val orientationValue = when (value.orientation) {
                                GameScreenOrientation.UNSPECIFIED -> -1
                                GameScreenOrientation.LANDSCAPE -> 0
                                GameScreenOrientation.PORTRAIT -> 1
                                GameScreenOrientation.SENSOR_LANDSCAPE -> 6
                                GameScreenOrientation.SENSOR_PORTRAIT -> 7
                            }
                            patchIntegerAttribute(
                                chunk,
                                strings,
                                ATTRIBUTE_SCREEN_ORIENTATION,
                                orientationValue,
                                stringIndex(value.orientation.manifestValue),
                            )
                            gameActivityPatched = true
                        }
                    }
                }
            }
            chunks += chunk
            offset += size
        }

        require(permissionSlot >= permissions.size) { "Android manifest has too few permission slots" }
        require(manifestPatched && applicationPatched && gameActivityPatched) { "Incomplete Android manifest template" }

        val output = ByteArrayOutputStream(declaredSize + 512)
        output.write(source, 0, XML_HEADER_SIZE)
        output.write(pool.encode(strings))
        chunks.forEach(output::write)
        return output.toByteArray().also {
            writeI32(it, 4, it.size)
            verifyManifest(it, value)
        }
    }

    private fun verifyManifest(source: ByteArray, expected: AndroidProjectProperties) {
        val pool = StringPool.parse(source, XML_HEADER_SIZE)
        val strings = pool.strings
        val permissions = mutableListOf<String>()
        var applicationId: String? = null
        var versionName: String? = null
        var versionCode: Int? = null
        var appName: String? = null
        var orientation: Int? = null
        var offset = XML_HEADER_SIZE + pool.chunkSize
        while (offset < source.size) {
            val type = readU16(source, offset)
            val size = readI32(source, offset + 4)
            require(size >= CHUNK_HEADER_SIZE && offset + size <= source.size) { "Invalid customized manifest chunk" }
            if (type == RES_XML_START_ELEMENT_TYPE) {
                val chunk = source.copyOfRange(offset, offset + size)
                when (strings.getOrNull(readI32(chunk, NODE_NAME_OFFSET))) {
                    ELEMENT_MANIFEST -> {
                        applicationId = readStringAttribute(chunk, strings, ATTRIBUTE_PACKAGE)
                        versionName = readStringAttribute(chunk, strings, ATTRIBUTE_VERSION_NAME)
                        versionCode = readIntegerAttribute(chunk, strings, ATTRIBUTE_VERSION_CODE)
                    }
                    ELEMENT_USES_PERMISSION -> {
                        readStringAttribute(chunk, strings, ATTRIBUTE_NAME)?.let(permissions::add)
                    }
                    ELEMENT_APPLICATION -> appName = readStringAttribute(chunk, strings, ATTRIBUTE_LABEL)
                    ELEMENT_ACTIVITY -> if (readStringAttribute(chunk, strings, ATTRIBUTE_NAME) == GAME_ACTIVITY_CLASS) {
                        orientation = readIntegerAttribute(chunk, strings, ATTRIBUTE_SCREEN_ORIENTATION)
                    }
                }
            }
            offset += size
        }
        val expectedOrientation = when (expected.orientation) {
            GameScreenOrientation.UNSPECIFIED -> -1
            GameScreenOrientation.LANDSCAPE -> 0
            GameScreenOrientation.PORTRAIT -> 1
            GameScreenOrientation.SENSOR_LANDSCAPE -> 6
            GameScreenOrientation.SENSOR_PORTRAIT -> 7
        }
        require(applicationId == expected.applicationId) { "Android application ID customization failed" }
        require(versionName == expected.versionName && versionCode == expected.versionCode) {
            "Android version customization failed"
        }
        require(appName == expected.appName) { "Android application name customization failed" }
        require(orientation == expectedOrientation) { "Android orientation customization failed" }
        require(permissions == expected.permissions.sorted()) { "Android permission customization failed" }
    }

    private fun patchStringAttribute(chunk: ByteArray, strings: List<String>, name: String, valueIndex: Int) {
        val attribute = findAttribute(chunk, strings, name)
        writeI32(chunk, attribute + ATTRIBUTE_RAW_VALUE_OFFSET, valueIndex)
        chunk[attribute + ATTRIBUTE_TYPE_OFFSET] = TYPE_STRING.toByte()
        writeI32(chunk, attribute + ATTRIBUTE_DATA_OFFSET, valueIndex)
    }

    private fun patchIntegerAttribute(
        chunk: ByteArray,
        strings: List<String>,
        name: String,
        value: Int,
        rawValueIndex: Int,
    ) {
        val attribute = findAttribute(chunk, strings, name)
        writeI32(chunk, attribute + ATTRIBUTE_RAW_VALUE_OFFSET, rawValueIndex)
        chunk[attribute + ATTRIBUTE_TYPE_OFFSET] = TYPE_INT_DEC.toByte()
        writeI32(chunk, attribute + ATTRIBUTE_DATA_OFFSET, value)
    }

    private fun readStringAttribute(chunk: ByteArray, strings: List<String>, name: String): String? {
        val attribute = findAttribute(chunk, strings, name)
        val rawValue = readI32(chunk, attribute + ATTRIBUTE_RAW_VALUE_OFFSET)
        val data = readI32(chunk, attribute + ATTRIBUTE_DATA_OFFSET)
        return strings.getOrNull(if (rawValue >= 0) rawValue else data)
    }

    private fun readIntegerAttribute(chunk: ByteArray, strings: List<String>, name: String): Int {
        val attribute = findAttribute(chunk, strings, name)
        require(chunk[attribute + ATTRIBUTE_TYPE_OFFSET].toInt() and 0xff in TYPE_INT_DEC..TYPE_INT_HEX) {
            "Android manifest attribute is not an integer: $name"
        }
        return readI32(chunk, attribute + ATTRIBUTE_DATA_OFFSET)
    }

    private fun findAttribute(chunk: ByteArray, strings: List<String>, name: String): Int {
        val start = XML_NODE_HEADER_SIZE + readU16(chunk, ATTRIBUTE_START_OFFSET)
        val size = readU16(chunk, ATTRIBUTE_SIZE_OFFSET)
        val count = readU16(chunk, ATTRIBUTE_COUNT_OFFSET)
        require(size >= ATTRIBUTE_MIN_SIZE && start + size * count <= chunk.size) {
            "Invalid Android manifest attributes"
        }
        repeat(count) { index ->
            val offset = start + index * size
            if (strings.getOrNull(readI32(chunk, offset + ATTRIBUTE_NAME_OFFSET)) == name) return offset
        }
        throw IllegalArgumentException("Android manifest attribute is missing: $name")
    }

    private data class StringPool(
        val strings: List<String>,
        val utf8: Boolean,
        val chunkSize: Int,
    ) {
        fun encode(values: List<String>): ByteArray {
            val encoded = values.map { encodeString(it, utf8) }
            val stringsByteCount = encoded.sumOf(ByteArray::size)
            val stringsStart = STRING_POOL_HEADER_SIZE + values.size * 4
            val paddedStringSize = align4(stringsByteCount)
            val result = ByteArray(stringsStart + paddedStringSize)
            writeU16(result, 0, RES_STRING_POOL_TYPE)
            writeU16(result, 2, STRING_POOL_HEADER_SIZE)
            writeI32(result, 4, result.size)
            writeI32(result, 8, values.size)
            writeI32(result, 12, 0)
            writeI32(result, 16, if (utf8) UTF8_FLAG else 0)
            writeI32(result, 20, stringsStart)
            writeI32(result, 24, 0)
            var dataOffset = 0
            encoded.forEachIndexed { index, bytes ->
                writeI32(result, STRING_POOL_HEADER_SIZE + index * 4, dataOffset)
                bytes.copyInto(result, stringsStart + dataOffset)
                dataOffset += bytes.size
            }
            return result
        }

        companion object {
            fun parse(source: ByteArray, offset: Int): StringPool {
                require(readU16(source, offset) == RES_STRING_POOL_TYPE) { "Android manifest has no string pool" }
                val headerSize = readU16(source, offset + 2)
                val chunkSize = readI32(source, offset + 4)
                val count = readI32(source, offset + 8)
                val styleCount = readI32(source, offset + 12)
                val flags = readI32(source, offset + 16)
                val stringsStart = readI32(source, offset + 20)
                require(headerSize >= STRING_POOL_HEADER_SIZE && styleCount == 0) { "Unsupported Android manifest string pool" }
                require(count >= 0 && offset + chunkSize <= source.size) { "Invalid Android manifest string pool" }
                val utf8 = flags and UTF8_FLAG != 0
                val values = ArrayList<String>(count)
                repeat(count) { index ->
                    val relative = readI32(source, offset + headerSize + index * 4)
                    val stringOffset = offset + stringsStart + relative
                    values += if (utf8) decodeUtf8(source, stringOffset) else decodeUtf16(source, stringOffset)
                }
                return StringPool(values, utf8, chunkSize)
            }
        }
    }

    private fun decodeUtf8(source: ByteArray, offset: Int): String {
        val (_, afterUtf16Length) = readLength8(source, offset)
        val (byteLength, dataOffset) = readLength8(source, afterUtf16Length)
        require(dataOffset + byteLength < source.size) { "Invalid UTF-8 manifest string" }
        return String(source, dataOffset, byteLength, Charsets.UTF_8)
    }

    private fun decodeUtf16(source: ByteArray, offset: Int): String {
        val (length, dataOffset) = readLength16(source, offset)
        require(dataOffset + length * 2 + 1 < source.size) { "Invalid UTF-16 manifest string" }
        return String(source, dataOffset, length * 2, UTF16_LE)
    }

    private fun readLength8(source: ByteArray, offset: Int): Pair<Int, Int> {
        val first = source[offset].toInt() and 0xff
        return if (first and 0x80 == 0) {
            first to offset + 1
        } else {
            (((first and 0x7f) shl 8) or (source[offset + 1].toInt() and 0xff)) to offset + 2
        }
    }

    private fun readLength16(source: ByteArray, offset: Int): Pair<Int, Int> {
        val first = readU16(source, offset)
        return if (first and 0x8000 == 0) {
            first to offset + 2
        } else {
            (((first and 0x7fff) shl 16) or readU16(source, offset + 2)) to offset + 4
        }
    }

    private fun encodeString(value: String, utf8: Boolean): ByteArray {
        if (!utf8) {
            val output = ByteArrayOutputStream(value.length * 2 + 6)
            writeLength16(output, value.length)
            value.forEach { character ->
                output.write(character.code and 0xff)
                output.write(character.code ushr 8)
            }
            output.write(0)
            output.write(0)
            return output.toByteArray()
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream(bytes.size + 5)
        writeLength8(output, value.length)
        writeLength8(output, bytes.size)
        output.write(bytes)
        output.write(0)
        return output.toByteArray()
    }

    private fun writeLength8(output: ByteArrayOutputStream, value: Int) {
        require(value <= 0x7fff) { "Android manifest string is too long" }
        if (value <= 0x7f) {
            output.write(value)
        } else {
            output.write((value ushr 8) or 0x80)
            output.write(value and 0xff)
        }
    }

    private fun writeLength16(output: ByteArrayOutputStream, value: Int) {
        if (value <= 0x7fff) {
            output.write(value and 0xff)
            output.write(value ushr 8)
        } else {
            val first = (value ushr 16) or 0x8000
            output.write(first and 0xff)
            output.write(first ushr 8)
            output.write(value and 0xff)
            output.write(value ushr 8)
        }
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readI32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeI32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun align4(value: Int): Int = (value + 3) and -4

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val UTF8_FLAG = 0x00000100
    private const val XML_HEADER_SIZE = 8
    private const val CHUNK_HEADER_SIZE = 8
    private const val STRING_POOL_HEADER_SIZE = 28
    private const val XML_NODE_HEADER_SIZE = 16
    private const val NODE_NAME_OFFSET = 20
    private const val ATTRIBUTE_START_OFFSET = 24
    private const val ATTRIBUTE_SIZE_OFFSET = 26
    private const val ATTRIBUTE_COUNT_OFFSET = 28
    private const val ATTRIBUTE_MIN_SIZE = 20
    private const val ATTRIBUTE_NAME_OFFSET = 4
    private const val ATTRIBUTE_RAW_VALUE_OFFSET = 8
    private const val ATTRIBUTE_TYPE_OFFSET = 15
    private const val ATTRIBUTE_DATA_OFFSET = 16
    private const val ELEMENT_MANIFEST = "manifest"
    private const val ELEMENT_USES_PERMISSION = "uses-permission"
    private const val ELEMENT_APPLICATION = "application"
    private const val ELEMENT_ACTIVITY = "activity"
    private const val ATTRIBUTE_PACKAGE = "package"
    private const val ATTRIBUTE_VERSION_CODE = "versionCode"
    private const val ATTRIBUTE_VERSION_NAME = "versionName"
    private const val ATTRIBUTE_NAME = "name"
    private const val ATTRIBUTE_LABEL = "label"
    private const val ATTRIBUTE_SCREEN_ORIENTATION = "screenOrientation"
    private const val GAME_ACTIVITY_CLASS = "top.wsdx233.love2droid.runtime.PackagedLoveGameActivity"
    private val UTF16_LE = Charset.forName("UTF-16LE")
}
