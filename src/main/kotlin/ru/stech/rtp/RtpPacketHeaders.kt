package ru.stech.rtp

class RtpPacketHeaders {
    var version: Byte = 0
    var padding: Byte = 0
    var extension: Byte = 0
    var marker: Byte = 0
    var payloadType: Byte = 0
}