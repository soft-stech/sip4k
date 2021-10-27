package ru.stech.util

import io.netty.channel.nio.NioEventLoopGroup
import java.math.BigInteger
import java.security.MessageDigest
import java.util.*

const val LIBNAME = "Sip4k"
const val TRANSPORT = "udp"
const val MAX_FORWARDS = 70
const val EXPIRES = 60
const val LOCALHOST = "127.0.0.1"
const val TIMEOUT_MESSAGE = "Sip timeout exception"
val sipNioEventLoop = NioEventLoopGroup(1)
val rtpNioEventLoop = NioEventLoopGroup(1)

fun md5(input: String): String {
    val md = MessageDigest.getInstance("MD5")
    return BigInteger(1, md.digest(input.toByteArray())).toString(16).padStart(32, '0')
}

fun getResponseHash(
    user: String,
    realm: String,
    password: String,
    method: String,
    serverIp: String,
    nonce: String,
    nc: String,
    cnonce: String,
    qop: String
): String {
    val ha1 = md5("${user}:${realm}:${password}")
    val ha2 = md5("${method}:sip:${serverIp};transport=${TRANSPORT}")
    return md5("${ha1}:${nonce}:${nc}:${cnonce}:${qop}:${ha2}")
}

fun randomString(targetStringLength: Int, rightLimit: Int = 122): String {
    val leftLimit = 48 // numeral '0'
    val random = Random()
    return random.ints(leftLimit, rightLimit + 1)
        .filter { i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97) }
        .limit(targetStringLength.toLong())
        .collect({ StringBuilder() }, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString()
}
