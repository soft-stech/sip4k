package ru.stech

import ru.stech.g711.compressToG711
import ru.stech.sip.client.SipClient

class Client(
    private val sipProperties: SipProperties,
    private val rtpStreamEvent: (user: String, data: ByteArray) -> Unit,
    private val rtpDisconnectEvent: (user: String, byAbonent: Boolean) -> Unit,
    private val diapason: Pair<Int, Int> = Pair(40000, 65000)
) {
    private val sipClient = SipClient(
        sipId = sipProperties.login,
        password = sipProperties.password,
        serverHost = sipProperties.serverHost,
        serverPort = sipProperties.serverSipPort,
        sipListenPort = sipProperties.clientSipPort,
        rtpStreamEvent = rtpStreamEvent,
        rtpDisconnectEvent = rtpDisconnectEvent,
        sipTimeoutMillis = sipProperties.sipTimeoutMillis,
        diapason = diapason
    )

    suspend fun sendAudioData(to: String, data: ByteArray) {
        sipClient.sendAudioData(to, compressToG711(data, true))
    }

    suspend fun start() {
        sipClient.start()
    }

    suspend fun stop() {
        sipClient.stop()
    }

    suspend fun startCall(to: String) {
        sipClient.startCall(to)
    }

    suspend fun stopCall(to: String) {
        sipClient.stopCall(to)
    }
}