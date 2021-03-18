package ru.stech

data class SipClientProperties(
    val user: String,
    val password: String,
    val serverIp: String,
    val serverPort: Int,
    val clientIp: String,
    val clientPort: Int,
    val rtpPort: Int
)