package ru.stech.sip.client

class SipConnectionFactory(
    private val sipClient: SipClient
) {

    fun newConnection(): SipConnection {
        return SipConnection(
            sipId = sipClient.sipId,
            sipRemoteHost = sipClient.serverHost,
            sipPassword = sipClient.password,
            sipRemotePort = sipClient.serverPort
        )
    }

}