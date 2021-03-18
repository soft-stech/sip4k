package ru.stech.rtp

interface RtpListener {
    fun dataReceived(user: String, data: ByteArray)
}