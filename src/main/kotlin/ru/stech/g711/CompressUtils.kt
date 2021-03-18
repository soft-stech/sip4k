package ru.stech.g711

import ru.stech.g711.compress.ALawCompressor
import ru.stech.g711.compress.Compressor
import ru.stech.g711.compress.ULawCompressor

private val alawcompressor: Compressor = ALawCompressor()
private val ulawcompressor: Compressor = ULawCompressor()

fun compressToG711(inpb: ByteArray, useALaw: Boolean): ByteArray {
    val compressor = if (useALaw) alawcompressor else ulawcompressor
    var off = 0
    var sample: Int
    var i = 0
    val out = ByteArray(inpb.size shr 1)
    while (i < inpb.size) {
        sample = inpb[i++].toInt() and 0x00FF
        sample = sample or (inpb[i++].toInt() shl 8)
        out[off++] = compressor.compress(sample.toShort()).toByte()
    }
    return out
}

