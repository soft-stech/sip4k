package ru.stech.sip

import gov.nist.javax.sip.address.GenericURI
import gov.nist.javax.sip.header.RequestLine
import gov.nist.javax.sip.header.SIPHeader
import gov.nist.javax.sip.header.WWWAuthenticate
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import ru.stech.BotClient
import ru.stech.BotProperties
import ru.stech.rtp.RtpSession
import ru.stech.sdp.parseToSdpBody
import ru.stech.sip.cache.SipSessionCache
import ru.stech.sip.client.SipClient
import ru.stech.sip.exceptions.SipTimeoutException
import ru.stech.util.MAX_FORWARDS
import ru.stech.util.TRANSPORT
import ru.stech.util.getResponseHash
import ru.stech.util.randomString
import java.util.*
import javax.sip.address.AddressFactory
import javax.sip.header.CSeqHeader
import javax.sip.header.HeaderFactory
import javax.sip.header.ViaHeader

@ExperimentalCoroutinesApi
class UserSession(private val to: String,
                  private val botProperties: BotProperties,
                  private val addressFactory: AddressFactory,
                  private val headerFactory: HeaderFactory,
                  private val sipClient: SipClient,
                  private val botClient: BotClient,
                  private val sessionCache: SipSessionCache,
                  private val rtpNioEventLoopGroup: NioEventLoopGroup,
                  private val botCoroutineDispatcher: CoroutineDispatcher,
                  private val sipTimeout: Long = 60000
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        val SIP_TIMEOUT = "Sip timeout"
    }

    private val callId = UUID.randomUUID().toString()
    private val fromTag = randomString(8)
    private lateinit var toTag: String
    private val inviteResponseChannel = Channel<SIPResponse>(0)
    private val outputAudioChannel = Channel<ByteArray>(3000)
    private val localRtpPort = sessionCache.newRtpPort()
    private val rtpSession = RtpSession(
        user = to,
        listenPort = localRtpPort,
        botClient = botClient,
        rtpNioEventLoopGroup = rtpNioEventLoopGroup,
        dispatcher = botCoroutineDispatcher
    )
    //jobs in this session
    private val sendRtpToAbonentJob = CoroutineScope(botCoroutineDispatcher).launch {
        for (data in outputAudioChannel) {
            rtpSession.sendRtpData(data)
            delay(20)
        }
    }

    fun resetQuietAnalizer() {
        rtpSession.resetQuietAnalizer()
    }

    suspend fun startCall(): Boolean {
        rtpSession.start()
        var inviteBranch = "z9hG4bK${UUID.randomUUID()}"
        val inviteSipRequestBuilder = SipRequestBuilder(
            RequestLine(
                GenericURI("sip:${to}@${botProperties.serverHost};transport=${TRANSPORT}"),
                SIPRequest.INVITE)
        )
        inviteSipRequestBuilder.headers[SIPHeader.VIA] = headerFactory.createViaHeader(
                botProperties.serverHost,
                botProperties.serverSipPort,
                TRANSPORT,
                inviteBranch)
        inviteSipRequestBuilder.headers[SIPHeader.CONTACT] = headerFactory.createContactHeader(
            addressFactory.createAddress(
                addressFactory.createSipURI(botProperties.login, botProperties.clientHost)
            )
        )

        val toSipURI = addressFactory.createSipURI(to, botProperties.serverHost)
        toSipURI.transportParam = TRANSPORT
        inviteSipRequestBuilder.headers[SIPHeader.TO] =
            headerFactory.createToHeader(addressFactory.createAddress(toSipURI), null)

        val fromSipURI = addressFactory.createSipURI(botProperties.login, botProperties.serverHost)
        toSipURI.transportParam = TRANSPORT
        inviteSipRequestBuilder.headers[SIPHeader.FROM] = headerFactory.createFromHeader(
            addressFactory.createAddress(fromSipURI), fromTag)

        inviteSipRequestBuilder.headers[SIPHeader.CSEQ] = headerFactory.createCSeqHeader(1L, SIPRequest.INVITE)
        inviteSipRequestBuilder.headers[SIPHeader.MAX_FORWARDS] = headerFactory.createMaxForwardsHeader(MAX_FORWARDS)
        inviteSipRequestBuilder.headers[SIPHeader.CALL_ID] = headerFactory.createCallIdHeader(callId)
        inviteSipRequestBuilder.headers[SIPHeader.ALLOW] =
            headerFactory.createAllowHeader("INVITE, ACK, CANCEL, BYE, NOTIFY, REFER, MESSAGE, OPTIONS, INFO, SUBSCRIBE")
        inviteSipRequestBuilder.headers[SIPHeader.CONTENT_TYPE] = headerFactory.createContentTypeHeader("application", "sdp")
        inviteSipRequestBuilder.headers[SIPHeader.USER_AGENT] = headerFactory.createUserAgentHeader(listOf("Sip4k"))
        inviteSipRequestBuilder.headers[SIPHeader.ALLOW_EVENTS] = headerFactory.createAllowEventsHeader("presence, kpml, talk")

        inviteSipRequestBuilder.headers[SIPHeader.CONTENT_LENGTH] = headerFactory.createContentLengthHeader(0)

        inviteSipRequestBuilder.rtpHost = botProperties.clientHost
        inviteSipRequestBuilder.rtpPort = localRtpPort

        sipClient.send(inviteSipRequestBuilder.toString().toByteArray())
        var inviteResponse = withTimeoutOrNull(sipTimeout) {
            receiveFinalInvite()
        } ?: throw SipTimeoutException(SIP_TIMEOUT)
        ack(inviteBranch)
        if (inviteResponse.statusLine.statusCode == 401) {
            inviteBranch = "z9hG4bK${UUID.randomUUID()}"
            val cnonce = UUID.randomUUID().toString()
            val inviteWWWAuthenticateResponse = inviteResponse.getHeader("WWW-Authenticate") as WWWAuthenticate
            val viaHeader = inviteSipRequestBuilder.headers[SIPHeader.VIA] as ViaHeader
            viaHeader.branch = inviteBranch
            val cSeqHeader = inviteSipRequestBuilder.headers[SIPHeader.CSEQ] as CSeqHeader
            cSeqHeader.seqNumber = 2

            val authenticationHeader = headerFactory.createAuthorizationHeader("Digest")
            authenticationHeader.username = botProperties.login
            authenticationHeader.realm = inviteWWWAuthenticateResponse.realm
            authenticationHeader.nonce = inviteWWWAuthenticateResponse.nonce
            authenticationHeader.uri = addressFactory.createURI("sip:${botProperties.serverHost};transport=udp")
            authenticationHeader.response = getResponseHash(
                user = botProperties.login,
                realm = inviteWWWAuthenticateResponse.realm,
                password = botProperties.password,
                method = SIPRequest.INVITE,
                serverIp = botProperties.serverHost,
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
            inviteResponse = withTimeoutOrNull(sipTimeout) {
                receiveFinalInvite()
            } ?: throw SipTimeoutException(SIP_TIMEOUT)
            ack(inviteBranch)
        }
        toTag = inviteResponse.toTag
        return if (inviteResponse.statusLine.statusCode == 200) {
            logger.trace { "Session is active" }
            val sdp = inviteResponse.messageContent.parseToSdpBody()
            rtpSession.remotePort = sdp?.remoteRdpPort
            rtpSession.remoteHost = sdp?.remoteRdpHost
            true
        } else {
            logger.trace { "Session is not active" }
            false
        }
    }

    private suspend fun receiveFinalInvite(): SIPResponse {
        var inviteResponse = inviteResponseChannel.receive()
        while (!inviteResponse.isFinalResponse) {
            inviteResponse = inviteResponseChannel.receive()
        }
        return inviteResponse
    }

    private fun ack(branch: String) {
        val ackSipRequestBuilder = SipRequestBuilder(
            RequestLine(
                GenericURI("sip:${to}@${botProperties.serverHost};transport=${TRANSPORT}"),
                SIPRequest.ACK)
        )
        ackSipRequestBuilder.headers[SIPHeader.VIA] =
            headerFactory.createViaHeader(botProperties.clientHost, botProperties.clientSipPort, TRANSPORT, branch)
        ackSipRequestBuilder.headers[SIPHeader.MAX_FORWARDS] = headerFactory.createMaxForwardsHeader(MAX_FORWARDS)

        val toSipURI = addressFactory.createSipURI(botProperties.login, botProperties.serverHost)
        ackSipRequestBuilder.headers[SIPHeader.TO] = headerFactory.createToHeader(
            addressFactory.createAddress(toSipURI), toTag
        )

        val fromSipURI = addressFactory.createSipURI(botProperties.login, botProperties.serverHost)
        ackSipRequestBuilder.headers[SIPHeader.FROM] = headerFactory.createFromHeader(
            addressFactory.createAddress(fromSipURI), fromTag
        )

        ackSipRequestBuilder.headers[SIPHeader.CALL_ID] = headerFactory.createCallIdHeader(callId)

        ackSipRequestBuilder.headers[SIPHeader.CSEQ] = headerFactory.createCSeqHeader(1L, SIPRequest.ACK)

        ackSipRequestBuilder.headers[SIPHeader.CONTENT_LENGTH] = headerFactory.createContentLengthHeader(0)

        sipClient.send(ackSipRequestBuilder.toString().toByteArray())
    }

    suspend fun inviteResponseEvent(request: SIPResponse) {
        inviteResponseChannel.send(request)
    }

    suspend fun inviteRequestEvent(request: SIPRequest) {
        sipClient.send(request.createResponse(200).toString().toByteArray())
    }

    private var byeRequestIsAlreadyReceived = false
    suspend fun byeRequestEvent(request: SIPRequest) {
        sipClient.send(request.createResponse(200).toString().toByteArray())
        botClient.endSession(to)
        byeRequestIsAlreadyReceived = true
    }


    private val byeResponseChannel = Channel<SIPResponse>(0)
    suspend fun byeResponseEvent(response: SIPResponse) {
        byeResponseChannel.send(response)
    }

    suspend fun stopCall() {
        if (!byeRequestIsAlreadyReceived) {
            val byeBranch = "z9hG4bK${UUID.randomUUID()}"
            val byeSipRequestBuilder = SipRequestBuilder(
                RequestLine(
                    GenericURI("sip:${to}@${botProperties.serverHost};transport=${TRANSPORT}"),
                    SIPRequest.BYE)
            )
            byeSipRequestBuilder.headers[SIPHeader.VIA] =
                headerFactory.createViaHeader(botProperties.clientHost, botProperties.clientSipPort, TRANSPORT, byeBranch)

            byeSipRequestBuilder.headers[SIPHeader.CONTACT] = headerFactory.createContactHeader(
                addressFactory.createAddress(
                    addressFactory.createSipURI(botProperties.login, botProperties.clientHost)
                )
            )

            val toSipURI = addressFactory.createSipURI(to, botProperties.serverHost)
            toSipURI.transportParam = TRANSPORT
            byeSipRequestBuilder.headers[SIPHeader.TO] =
                headerFactory.createToHeader(addressFactory.createAddress(toSipURI), toTag)

            val fromSipURI = addressFactory.createSipURI(botProperties.login, botProperties.serverHost)
            toSipURI.transportParam = TRANSPORT
            byeSipRequestBuilder.headers[SIPHeader.FROM] = headerFactory.createFromHeader(
                addressFactory.createAddress(fromSipURI), fromTag)

            byeSipRequestBuilder.headers[SIPHeader.CSEQ] = headerFactory.createCSeqHeader(1L, SIPRequest.BYE)
            byeSipRequestBuilder.headers[SIPHeader.MAX_FORWARDS] = headerFactory.createMaxForwardsHeader(MAX_FORWARDS)
            byeSipRequestBuilder.headers[SIPHeader.CALL_ID] = headerFactory.createCallIdHeader(callId)
            byeSipRequestBuilder.headers[SIPHeader.USER_AGENT] = headerFactory.createUserAgentHeader(listOf("Sip4k"))
            byeSipRequestBuilder.headers[SIPHeader.CONTENT_LENGTH] = headerFactory.createContentLengthHeader(0)
            sipClient.send(byeSipRequestBuilder.toString().toByteArray())
            val byeResponse = withTimeoutOrNull(sipTimeout) {
                byeResponseChannel.receive()
            } ?: throw SipTimeoutException(SIP_TIMEOUT)
        }
        inviteResponseChannel.close()
        outputAudioChannel.close()
        byeResponseChannel.close()
        rtpSession.stop()
    }

    suspend fun sendAudioData(data: ByteArray) {
        outputAudioChannel.send(data)
    }

}