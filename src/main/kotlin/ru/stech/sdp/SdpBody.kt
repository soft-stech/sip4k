package ru.stech.sdp

private val remoteRdpHostRegex = Regex("c=IN IP4 (.*?)[\r\n]")
private val remoteRdpPortRegex = Regex("m=audio ([0-9]+)")

data class SdpBody(
    val remoteRdpHost: String,
    val remoteRdpPort: Int
)

fun String.parseToSdpBody(): SdpBody? {
    val remoteRdpPortResult = remoteRdpPortRegex.find(this)
    return if (remoteRdpPortResult?.groupValues?.size ?: 0 > 1) {
        val remoteRdpPort = remoteRdpPortResult!!.groupValues[1].toInt()
        val remoteRdpHostResult = remoteRdpHostRegex.find(this)
        if (remoteRdpHostResult?.groupValues?.size ?: 0 > 1) {
            val remoteRdpHost = remoteRdpHostResult!!.groupValues[1]
            SdpBody(
                remoteRdpHost = remoteRdpHost,
                remoteRdpPort = remoteRdpPort
            )
        } else {
            null
        }
    } else {
        null
    }
}