package ru.stech

data class BotProperties(
    val serverHost: String,
    val serverSipPort: Int,
    val clientHost: String,
    val clientSipPort: Int,
    val login: String,
    val password: String,
    val diapason: Pair<Int, Int> = Pair(40000, 65000)
)