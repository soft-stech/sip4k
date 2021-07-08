package ru.stech

data class SipProperties(
    val serverHost: String,
    val serverSipPort: Int,
    val clientSipPort: Int,
    val login: String,
    val password: String,
    val diapason: Pair<Int, Int> = Pair(40000, 65000),
    val sipTimeoutMillis: Long = 60000
)