package ru.stech.g711.compress

class ALawCompressor : Compressor {
    override fun compress(sample: Short): Int {
        var value = sample
        val exponent: Int
        val mantissa: Int
        var compressedByte: Int
        val sign: Int = value.toInt().inv() shr 8 and 0x80
        if (sign == 0) {
            value = (value * -1).toShort()
        }
        if (value > cClip) {
            value = cClip.toShort()
        }
        if (value >= 256) {
            exponent = ALawCompressTable[value.toInt() shr 8 and 0x007F]
            mantissa = value.toInt() shr exponent + 3 and 0x0F
            compressedByte = 0x007F and (exponent shl 4 or mantissa)
        } else {
            compressedByte = 0x007F and (value.toInt() shr 4)
        }
        compressedByte = compressedByte xor (sign xor 0x55)
        return compressedByte
    }

    companion object {
        const val cClip = 32635
        val ALawCompressTable = intArrayOf(
            1, 1, 2, 2, 3, 3, 3, 3,
            4, 4, 4, 4, 4, 4, 4, 4,
            5, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 5, 5, 5, 5, 5, 5,
            6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7
        )
    }
}