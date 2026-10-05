package ai.meteor.kcode.plugin.packages

/** Read defined types, not referenced types, so SDK imports are allowed but duplicate SDKs are not. */
internal fun dexDefinedClasses(bytes: ByteArray): Set<String> {
    require(bytes.size >= 112 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) { "Invalid DEX header" }
    fun u32(offset: Int): Int {
        require(offset >= 0 && offset <= bytes.size - 4) { "DEX offset outside artifact" }
        val value = (bytes[offset].toLong() and 255) or ((bytes[offset + 1].toLong() and 255) shl 8) or
            ((bytes[offset + 2].toLong() and 255) shl 16) or ((bytes[offset + 3].toLong() and 255) shl 24)
        require(value <= Int.MAX_VALUE) { "DEX index exceeds supported artifact size" }
        return value.toInt()
    }
    require(u32(32) == bytes.size && u32(36) == 112 && u32(40) == 0x12345678) { "Unsupported DEX layout" }
    val stringsCount = u32(56)
    val stringsOffset = u32(60)
    val typesCount = u32(64)
    val typesOffset = u32(68)
    val classesCount = u32(96)
    val classesOffset = u32(100)
    fun bounds(offset: Int, count: Int, width: Int) {
        require(offset <= bytes.size && count.toLong() * width <= bytes.size.toLong() - offset) { "DEX table exceeds artifact" }
    }
    bounds(stringsOffset, stringsCount, 4)
    bounds(typesOffset, typesCount, 4)
    bounds(classesOffset, classesCount, 32)
    return (0 until classesCount).map { index ->
        val typeIndex = u32(classesOffset + index * 32)
        require(typeIndex < typesCount) { "Invalid DEX class type" }
        val stringIndex = u32(typesOffset + typeIndex * 4)
        require(stringIndex < stringsCount) { "Invalid DEX type string" }
        var offset = u32(stringsOffset + stringIndex * 4)
        // Skip bounded ULEB128 UTF-16 length, then read the zero-terminated descriptor.
        var lengthBytes = 0
        do {
            require(offset < bytes.size && lengthBytes++ < 5) { "Invalid DEX string length" }
            val next = bytes[offset++].toInt() and 255
        } while (next and 128 != 0)
        val start = offset
        while (offset < bytes.size && bytes[offset] != 0.toByte()) offset++
        require(offset < bytes.size && offset - start <= 65536) { "Invalid DEX class descriptor" }
        val descriptor = bytes.copyOfRange(start, offset).decodeToString(throwOnInvalidSequence = true)
        require(descriptor.startsWith('L') && descriptor.endsWith(';')) { "Invalid DEX defined type" }
        descriptor.substring(1, descriptor.length - 1)
    }.toSet()
}
