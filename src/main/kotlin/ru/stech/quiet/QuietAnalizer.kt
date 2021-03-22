package ru.stech.quiet

import java.util.ArrayList
import kotlin.math.ln

class QuietAnalizer {
    private val startAvr = 20800.0
    private val mulConst = 0.9995
    private var avr = 0.0
    private fun getShort(vararg vals: Byte): Int {
        val b1: Int = vals[0].toInt() and 0xFF
        val b2: Int = vals[1].toInt() and 0xFF
        return b1 shl 8 or b2
    }

    fun reset() {
        avr = 0.0
    }

    fun isQuietAtSegment(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            var b = getShort(bytes[i], bytes[i + 1]).toDouble()
            b = if (b == 0.0) 0.0 else ln(b)
            avr = (avr + b) * mulConst
            if (avr > startAvr) {
                return true
            }
            i += 2
        }
        return false
    }

    fun getBytePosOfStartOfQuiet(bytes: ByteArray): List<Int> {
        val result = ArrayList<Int>()
        var isUnnderQuet = false
        var i = 0
        while (i < bytes.size) {
            var b = getShort(bytes[i], bytes[i + 1]).toDouble()
            b = if (b == 0.0) 0.0 else ln(b)
            avr = (avr + b) * mulConst
            if (avr > startAvr) {
                if (!isUnnderQuet) {
                    isUnnderQuet = true
                    result.add(i)
                }
            } else {
                isUnnderQuet = false
            }
            i += 2
        }
        return result
    }
}