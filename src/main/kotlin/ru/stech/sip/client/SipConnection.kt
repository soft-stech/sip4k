package ru.stech.sip.client

import gov.nist.javax.sip.address.AddressImpl
import gov.nist.javax.sip.address.GenericURI
import gov.nist.javax.sip.header.Contact
import gov.nist.javax.sip.header.RequestLine
import gov.nist.javax.sip.header.SIPHeader
import gov.nist.javax.sip.header.WWWAuthenticate
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import ru.stech.rtp.RtpPortsCache
import ru.stech.sip.Factories
import ru.stech.sip.SipRequestBuilder
import ru.stech.sip.cache.SipConnectionCache
import ru.stech.sip.exceptions.SipException
import ru.stech.util.LIBNAME
import ru.stech.util.LOCALHOST
import ru.stech.util.MAX_FORWARDS
import ru.stech.util.TIMEOUT_MESSAGE
import ru.stech.util.TRANSPORT
import ru.stech.util.getResponseHash
import ru.stech.util.randomString
import java.util.*
import javax.sip.header.CSeqHeader
import javax.sip.header.ViaHeader

class SipConnection(
    private val to: String,
    private val sipClient: SipClient,
    private val sipConnectionCache: SipConnectionCache,
    private val rtpPortsCache: RtpPortsCache,
    private val sipTimeoutMillis: Long = 60000
) {
    companion object {
        private const val BYE_RESPONSE_ERROR = "Error in BYE response"
    }
    private val callId = UUID.randomUUID().toString()
    private val fromTag = randomString(8)
    private lateinit var toTag: String
    private lateinit var rtpPort: Int

    private val byeResponseChannel = Channel<SIPResponse>(0)
    private val inviteResponseChannel = Channel<SIPResponse>(0)

    fun byeRequestEvent(request: SIPRequest) {
        try {
            stopRtpStreaming()
            sipClient.send(request.createResponse(200).toString().toByteArray())
        } catch (e: Exception) {
            throw e
        } finally {
            removeFromCache()
        }
    }

    fun inviteRequestEvent(request: SIPRequest) {
        sipClient.send(request.createResponse(200).toString().toByteArray())
    }

    suspend fun inviteResponseEvent(response: SIPResponse) {
        inviteResponseChannel.send(response)
    }

    suspend fun byeResponseEvent(response: SIPResponse) {
        byeResponseChannel.send(response)
    }

    suspend fun startCall() {
        rtpPort = rtpPortsCache.getFreePort()
        var inviteBranch = "z9hG4bK${UUID.randomUUID()}"
        //rtpSession.start()
        val inviteSipRequestBuilder = SipRequestBuilder(
            RequestLine(
                GenericURI("sip:${to}@${sipClient.serverHost};transport=${TRANSPORT}"),
                SIPRequest.INVITE),
        )
        inviteSipRequestBuilder.headers[SIPHeader.VIA] = Factories.headerFactory.createViaHeader(
            LOCALHOST,
            sipClient.sipListenPort,
            TRANSPORT,
            inviteBranch)

        inviteSipRequestBuilder.headers[SIPHeader.MAX_FORWARDS] = Factories.headerFactory.createMaxForwardsHeader(MAX_FORWARDS)


        val toSipURI = Factories.addressFactory.createSipURI(to, sipClient.serverHost)
        toSipURI.transportParam = TRANSPORT

        val fromSipURI = Factories.addressFactory.createSipURI(sipClient.sipId, sipClient.serverHost)
        fromSipURI.transportParam = TRANSPORT

        inviteSipRequestBuilder.headers[SIPHeader.FROM] = Factories.headerFactory.createFromHeader(
            Factories.addressFactory.createAddress(fromSipURI), fromTag)

        inviteSipRequestBuilder.headers[SIPHeader.TO] =
            Factories.headerFactory.createToHeader(Factories.addressFactory.createAddress(toSipURI), null)

        inviteSipRequestBuilder.headers[SIPHeader.CONTACT] = Factories.headerFactory.createContactHeader(
            Factories.addressFactory.createAddress(
                Factories.addressFactory.createSipURI(sipClient.sipId,"${LOCALHOST}:${sipClient.sipListenPort}")
            )
        )

        inviteSipRequestBuilder.headers[SIPHeader.CALL_ID] = Factories.headerFactory.createCallIdHeader(callId)
        inviteSipRequestBuilder.headers[SIPHeader.CSEQ] = Factories.headerFactory.createCSeqHeader(1L, SIPRequest.INVITE)


        inviteSipRequestBuilder.headers[SIPHeader.ALLOW] =
            Factories.headerFactory.createAllowHeader("PRACK, INVITE, ACK, BYE, CANCEL, UPDATE, INFO, SUBSCRIBE, NOTIFY, REFER, MESSAGE, OPTIONS")
        inviteSipRequestBuilder.headers[SIPHeader.SUPPORTED] = Factories.headerFactory.createSupportedHeader("replaces, 100rel, norefersub")

        inviteSipRequestBuilder.headers[SIPHeader.USER_AGENT] = Factories.headerFactory.createUserAgentHeader(listOf("Sip4k"))

        inviteSipRequestBuilder.headers[SIPHeader.CONTENT_TYPE] = Factories.headerFactory.createContentTypeHeader("application", "sdp")

        inviteSipRequestBuilder.headers[SIPHeader.CONTENT_LENGTH] = Factories.headerFactory.createContentLengthHeader(0)

        inviteSipRequestBuilder.rtpHost = LOCALHOST
        inviteSipRequestBuilder.rtpPort = rtpPort

        sipClient.send(inviteSipRequestBuilder.toString().toByteArray())
        var inviteResponse = withTimeoutOrNull(sipTimeoutMillis) {
            receiveFinalInvite()
        } ?: throw SipException(TIMEOUT_MESSAGE)
        toTag = inviteResponse.toTag
        ack(inviteBranch, "${to}@${sipClient.serverHost}", 1L)
        if (inviteResponse.statusLine.statusCode == 401) {
            inviteBranch = "z9hG4bK${UUID.randomUUID()}"
            val cnonce = UUID.randomUUID().toString()
            val inviteWWWAuthenticateResponse = inviteResponse.getHeader("WWW-Authenticate") as WWWAuthenticate
            val viaHeader = inviteSipRequestBuilder.headers[SIPHeader.VIA] as ViaHeader
            viaHeader.branch = inviteBranch
            val cSeqHeader = inviteSipRequestBuilder.headers[SIPHeader.CSEQ] as CSeqHeader
            cSeqHeader.seqNumber = 2

            val authenticationHeader = Factories.headerFactory.createAuthorizationHeader("Digest")
            authenticationHeader.username = sipClient.sipId
            authenticationHeader.realm = inviteWWWAuthenticateResponse.realm
            authenticationHeader.nonce = inviteWWWAuthenticateResponse.nonce
            authenticationHeader.uri = Factories.addressFactory.createURI("sip:${sipClient.serverHost};transport=udp")
            authenticationHeader.response = getResponseHash(
                user = sipClient.sipId,
                realm = inviteWWWAuthenticateResponse.realm,
                password = sipClient.password,
                method = SIPRequest.INVITE,
                serverIp = sipClient.serverHost,
                nonce = inviteWWWAuthenticateResponse.nonce,
                nc = "00000001",
                cnonce = cnonce,
                qop = inviteWWWAuthenticateResponse.qop
            )
            authenticationHeader.cNonce = cnonce
            authenticationHeader.nonceCount = 1
            authenticationHeader.qop = inviteWWWAuthenticateResponse.qop
            if (inviteWWWAuthenticateResponse.algorithm != null)
                authenticationHeader.algorithm = inviteWWWAuthenticateResponse.algorithm
            if (inviteWWWAuthenticateResponse.opaque != null)
                authenticationHeader.opaque = inviteWWWAuthenticateResponse.opaque
            inviteSipRequestBuilder.headers[SIPHeader.AUTHORIZATION] = authenticationHeader

            sipClient.send(inviteSipRequestBuilder.toString().toByteArray())
            inviteResponse = withTimeoutOrNull(sipTimeoutMillis) {
                receiveFinalInvite()
            } ?: throw SipException(TIMEOUT_MESSAGE)
            toTag = inviteResponse.toTag

            if(inviteResponse.statusLine.statusCode == 200){
                val hostPort = ((inviteResponse.getHeader("contact") as Contact).address as AddressImpl).hostPort.toString()
                ack(inviteBranch, hostPort, 2L)
            }

        }
        return if (inviteResponse.statusLine.statusCode == 200) {
            val sdp = inviteResponse.messageContent.parseToSdpBody()
            rtpSession.remotePort = sdp.remoteRdpPort
            rtpSession.remoteHost = sdp.remoteRdpHost
            true
        } else {
            false
        }
    }

    suspend fun stopCall() {
        try {
            stopRtpStreaming()
            interruptConnection()
        } catch (e: Exception) {
            throw e
        } finally {
            removeFromCache()
        }
    }

    private fun stopRtpStreaming() {

    }

    private fun removeFromCache() {
        sipConnectionCache.remove(to)
    }

    private suspend fun interruptConnection() {
        val byeBranch = "z9hG4bK${UUID.randomUUID()}"
        val byeSipRequestBuilder = SipRequestBuilder(
            RequestLine(
                GenericURI("sip:${to}@${sipClient.serverHost};transport=$TRANSPORT"),
                SIPRequest.BYE)
        )
        byeSipRequestBuilder.headers[SIPHeader.VIA] =
            Factories.headerFactory.createViaHeader(LOCALHOST, sipClient.sipListenPort, TRANSPORT, byeBranch)

        byeSipRequestBuilder.headers[SIPHeader.CONTACT] = Factories.headerFactory.createContactHeader(
            Factories.addressFactory.createAddress(
                Factories.addressFactory.createSipURI(sipClient.sipId, LOCALHOST)
            )
        )

        val toSipURI = Factories.addressFactory.createSipURI(to, sipClient.serverHost)
        toSipURI.transportParam = TRANSPORT
        byeSipRequestBuilder.headers[SIPHeader.TO] =
            Factories.headerFactory.createToHeader(Factories.addressFactory.createAddress(toSipURI), toTag)

        val fromSipURI = Factories.addressFactory.createSipURI(sipClient.sipId, sipClient.serverHost)
        fromSipURI.transportParam = TRANSPORT
        byeSipRequestBuilder.headers[SIPHeader.FROM] = Factories.headerFactory.createFromHeader(
            Factories.addressFactory.createAddress(fromSipURI), fromTag)

        byeSipRequestBuilder.headers[SIPHeader.CSEQ] = Factories.headerFactory.createCSeqHeader(3L, SIPRequest.BYE)
        byeSipRequestBuilder.headers[SIPHeader.MAX_FORWARDS] = Factories.headerFactory.createMaxForwardsHeader(MAX_FORWARDS)
        byeSipRequestBuilder.headers[SIPHeader.CALL_ID] = Factories.headerFactory.createCallIdHeader(callId)
        byeSipRequestBuilder.headers[SIPHeader.USER_AGENT] = Factories.headerFactory.createUserAgentHeader(listOf(LIBNAME))
        byeSipRequestBuilder.headers[SIPHeader.CONTENT_LENGTH] = Factories.headerFactory.createContentLengthHeader(0)
        sipClient.send(byeSipRequestBuilder.toString().toByteArray())
        val byeResponse = withTimeoutOrNull(sipTimeoutMillis) {
            byeResponseChannel.receive()
        } ?: throw SipException(TIMEOUT_MESSAGE)
        if (byeResponse.statusLine.statusCode != 200) {
            throw SipException(BYE_RESPONSE_ERROR)
        }
    }

    private suspend fun receiveFinalInvite(): SIPResponse {
        var inviteResponse = inviteResponseChannel.receive()
        while (!inviteResponse.isFinalResponse) {
            inviteResponse = inviteResponseChannel.receive()
        }
        return inviteResponse
    }

    private fun ack(branch: String, address: String, cseq: Long) {
        val ackSipRequestBuilder = SipRequestBuilder(
            RequestLine(
                GenericURI("sip:${address};transport=${TRANSPORT}"),
                SIPRequest.ACK)
        )
        ackSipRequestBuilder.headers[SIPHeader.VIA] =
            Factories.headerFactory.createViaHeader(LOCALHOST, sipClient.sipListenPort, TRANSPORT, branch)

        val toSipURI = Factories.addressFactory.createSipURI(to,"${LOCALHOST}:${sipClient.serverHost}")
        ackSipRequestBuilder.headers[SIPHeader.TO] = Factories.headerFactory.createToHeader(
            Factories.addressFactory.createAddress(toSipURI), toTag
        )

        val fromSipURI = Factories.addressFactory.createSipURI(sipClient.sipId, sipClient.serverHost)
        ackSipRequestBuilder.headers[SIPHeader.FROM] = Factories.headerFactory.createFromHeader(
            Factories.addressFactory.createAddress(fromSipURI), fromTag
        )

        ackSipRequestBuilder.headers[SIPHeader.CALL_ID] = Factories.headerFactory.createCallIdHeader(callId)

        ackSipRequestBuilder.headers[SIPHeader.CSEQ] = Factories.headerFactory.createCSeqHeader(cseq, SIPRequest.ACK)

        ackSipRequestBuilder.headers[SIPHeader.MAX_FORWARDS] = Factories.headerFactory.createMaxForwardsHeader(MAX_FORWARDS)

        ackSipRequestBuilder.headers[SIPHeader.CONTENT_LENGTH] = Factories.headerFactory.createContentLengthHeader(0)

        sipClient.send(ackSipRequestBuilder.toString().toByteArray())
    }
}