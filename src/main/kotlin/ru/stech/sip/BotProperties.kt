package ru.stech.sip

data class BotProperties(
    val serverHost: String,
    val serverSipPort: Int,
    val clientHost: String,
    val clientSipPort: Int,
    val login: String,
    val password: String
)