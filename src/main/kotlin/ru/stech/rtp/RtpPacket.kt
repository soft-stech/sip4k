package ru.stech.rtp

import java.util.*

class RtpPacket {

    var rawData: ByteArray

    constructor() : this(ByteArray(172))

    constructor(rawData: ByteArray) {
        this.rawData = rawData
    }

    constructor(headers: RtpPacketHeaders) : this(ByteArray(172)) {
        version = headers.version
        padding = headers.padding
        extension = headers.extension
        marker = headers.marker
        payloadType = headers.payloadType
    }

    var version: Byte
        get() {
            return (rawData[0].toInt() shr 6 and 0x3).toByte()
        }
        set(value) {
            if (value < 0 && value > 3) throw IllegalArgumentException("value of version, must be from 0 to 3")
            rawData[0] = ((rawData[0].toInt() and 0x3F) or (value.toInt() shl 6)).toByte()
        }
    var padding: Byte
        get() = (rawData[0].toInt() shr 5 and 0x1).toByte()
        set(value) {
            rawData[0] = ((rawData[0].toInt() and 0xDF) or (value.toInt() shl 5)).toByte()
        }
    var extension: Byte
        get() = (rawData[0].toInt() shr 4 and 0x1).toByte()
        set(value) {
            rawData[0] = ((rawData[0].toInt() and 0xEF) or (value.toInt() shl 4)).toByte()
        }
    var CSRCCount: Byte
        get() = (rawData[0].toInt() and 0x0F).toByte()
        set(value) {
            rawData[0] = ((rawData[0].toInt() and 0xF0) or (value.toInt() and 0x0F)).toByte()
        }

    var marker: Byte
        get() = (rawData[1].toInt() shr 7 and 0x1).toByte()
        set(value) {
            if (value < 0 && value > 3) throw IllegalArgumentException("value of version, must be from 0 to 3")
            rawData[1] = ((rawData[1].toInt() and 0x7F) or (value.toInt() shl 7)).toByte()
        }

    var payloadType: Byte
        get() = (rawData[1].toInt() and 0x7F).toByte()
        set(value) {
            rawData[1] = ((rawData[1].toInt() and 0x80) or (value.toInt() and 0x7F)).toByte()
        }

    var sequenceNumber: Short
        get() = ((rawData[2].toInt() shl 0x8) or (rawData[3].toInt() and 0xFF)).toShort()
        set(value) {
            rawData[2] = (value.toInt() shr 0x8).toByte()
            rawData[3] = (value.toInt() and 0xFF).toByte()
        }

    var timeStamp: Int
        get() = getIntFromBytes(4)
        set(value) = setIntToBytes(value, 4)

    var SSRC: Int
        get() = getIntFromBytes(8)
        set(value) = setIntToBytes(value, 8)

    var payload
        get() =  Arrays.copyOfRange(rawData, PAYLOAD_OFFSET, rawData.size)
        set(value) {
            for (i in 0 until value.size) {
                rawData[i + PAYLOAD_OFFSET] = value[i]
            }
        }

    private fun getIntFromBytes(start: Int): Int {
        return (rawData[start].toInt() shl 24 and -0x1) or
                (rawData[start + 1].toInt() shl 16 and 0xFFFFFF) or
                (rawData[start + 2].toInt() shl 8 and 0xFFFF) or
                (rawData[start + 3].toInt() and 0xFF)
    }

    //end SSRC
    private val PAYLOAD_OFFSET = 12

    fun setPayloadByIndex(index: Int, value: Byte) {
        rawData[index + PAYLOAD_OFFSET] = value
    }

    private fun setIntToBytes(value: Int, start: Int) {
        rawData[start] = (value shr 24 and 0xFF).toByte()
        rawData[start + 1] = (value shr 16 and 0xFF).toByte()
        rawData[start + 2] = (value shr 8 and 0xFF).toByte()
        rawData[start + 3] = (value and 0xFF).toByte()
    }
}