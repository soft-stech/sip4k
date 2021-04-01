package ru.stech

import gov.nist.javax.sip.address.GenericURI
import gov.nist.javax.sip.header.RequestLine
import gov.nist.javax.sip.header.SIPHeader
import gov.nist.javax.sip.header.Via
import gov.nist.javax.sip.header.WWWAuthenticate
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import ru.stech.g711.compressToG711
import ru.stech.sip.SipRequestBuilder
import ru.stech.sip.UserSession
import ru.stech.sip.cache.SipSessionCacheImpl
import ru.stech.sip.client.SipClient
import ru.stech.sip.exceptions.SipClientNotAvailableException
import ru.stech.sip.exceptions.SipException
import ru.stech.sip.exceptions.SipTimeoutException
import ru.stech.util.EXPIRES
import ru.stech.util.MAX_FORWARDS
import ru.stech.util.TRANSPORT
import ru.stech.util.getResponseHash
import ru.stech.util.randomString
import java.util.*
import javax.sip.SipFactory

@ExperimentalCoroutinesApi
class BotClient(
    val botProperties: BotProperties,
    val sipNioThreads: Int = 1,
    val rtpNioThreads: Int = 1,
    val sipClientCoroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val rtpClientCoroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val botCoroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val sipTimeout: Long = 60000,
    val streamEventListener: (user: String, data: ByteArray, endOfPhrase: Boolean) -> Unit,
    val endMediaSessionEventListener: (user: String) -> Unit
) {
    companion object {
        val SIP_TIMEOUT = "Sip timeout"
        val NO_SUCH_SESSION = "No such sip session"
    }

    private val logger = KotlinLogging.logger {}

    private val REGISTER_DELAY = 20
    private var registered = false
    private val sipNioEventLoopGroup = NioEventLoopGroup(sipNioThreads)
    private val rtpNioEventLoopGroup = NioEventLoopGroup(rtpNioThreads)

    private val sipFactory = SipFactory.getInstance()
    private val messageFactory = sipFactory.createMessageFactory()
    private val headerFactory = sipFactory.createHeaderFactory()
    private val addressFactory = sipFactory.createAddressFactory()

    private lateinit var sipClient: SipClient

    private val sessionCache = SipSessionCacheImpl()
    val registerResponseChannel = Channel<SIPResponse>(0)

    private val botIsStarted = Channel<Boolean>(0)
    suspend fun startAwait(): Boolean {
        if (!botIsStarted.isClosedForSend) {
            sipClient = SipClient(
                serverHost = botProperties.serverHost,
                serverPort = botProperties.serverSipPort,
                sipListenPort = botProperties.clientSipPort,
                workerGroup = sipNioEventLoopGroup,
                dispatcher = sipClientCoroutineDispatcher,
                messageFactory = messageFactory,
                sessionCache = sessionCache,
                botClient = this
            )
            sipClient.start()
            CoroutineScope(botCoroutineDispatcher).launch {
                try {
                    startRegistrationByPeriod()
                } catch (e: Exception) {
                    handleInternalThrowable(e)
                } finally {
                    registerResponseChannel.close()
                }
            }
            return botIsStarted.receive()
        } else {
            throw SipException("Bot already registered")
        }
    }

    private suspend fun startRegistrationByPeriod() {
        val registerBranch = "z9hG4bK${UUID.randomUUID()}"
        val registerCallId = UUID.randomUUID().toString()
        val cnonce = UUID.randomUUID().toString()
        var registerSipRequestBuilder: SipRequestBuilder?
        registered = false
        do {
            registerSipRequestBuilder = SipRequestBuilder(RequestLine(
                GenericURI("sip:${botProperties.serverHost};transport=${TRANSPORT}"),
                SIPRequest.REGISTER
            ))
            registerSipRequestBuilder.headers[SIPHeader.VIA] = headerFactory.createViaHeader(
                botProperties.clientHost,
                botProperties.clientSipPort,
                TRANSPORT,
                registerBranch
            )
            registerSipRequestBuilder.headers[SIPHeader.MAX_FORWARDS] = headerFactory.createMaxForwardsHeader(MAX_FORWARDS)

            val contactSipURI = addressFactory.createSipURI(botProperties.login, botProperties.clientHost)
            contactSipURI.port = botProperties.clientSipPort
            contactSipURI.transportParam = TRANSPORT
            registerSipRequestBuilder.headers[SIPHeader.CONTACT] = headerFactory.createContactHeader(
                addressFactory.createAddress(contactSipURI)
            )

            val toSipURI = addressFactory.createSipURI(botProperties.login, botProperties.serverHost)
            toSipURI.transportParam = TRANSPORT
            registerSipRequestBuilder.headers[SIPHeader.TO] = headerFactory.createToHeader(addressFactory.createAddress(toSipURI), null)

            val fromTag = randomString(8)
            val fromSipURI = addressFactory.createSipURI(botProperties.login, botProperties.serverHost)
            fromSipURI.transportParam = TRANSPORT
            registerSipRequestBuilder.headers[SIPHeader.FROM] = headerFactory.createFromHeader(
                    addressFactory.createAddress(fromSipURI), fromTag)

            registerSipRequestBuilder.headers[SIPHeader.CALL_ID] = headerFactory.createCallIdHeader(registerCallId)
            registerSipRequestBuilder.headers[SIPHeader.CSEQ] = headerFactory.createCSeqHeader(1L, SIPRequest.REGISTER)
            registerSipRequestBuilder.headers[SIPHeader.EXPIRES] = headerFactory.createExpiresHeader(EXPIRES)
            registerSipRequestBuilder.headers[SIPHeader.USER_AGENT] = headerFactory.createUserAgentHeader(listOf("Sip4k"))
            registerSipRequestBuilder.headers[SIPHeader.CONTENT_LENGTH] = headerFactory.createContentLengthHeader(0)
            sipClient.send(registerSipRequestBuilder.toString().toByteArray())
            var registerResponse = withTimeoutOrNull(sipTimeout) {
                registerResponseChannel.receive()
            } ?: throw SipTimeoutException(SIP_TIMEOUT)

            if (registerResponse.statusLine.statusCode == 401) {
                val registerWWWAuthenticateResponse = registerResponse.getHeader("WWW-Authenticate") as WWWAuthenticate
                registerSipRequestBuilder.headers[SIPHeader.CSEQ] = headerFactory.createCSeqHeader(2L, "REGISTER")
                val authenticationHeader = headerFactory.createAuthorizationHeader("Digest")
                authenticationHeader.username = botProperties.login
                authenticationHeader.realm = registerWWWAuthenticateResponse.realm
                authenticationHeader.nonce = registerWWWAuthenticateResponse.nonce
                authenticationHeader.uri = addressFactory.createURI("sip:${botProperties.serverHost};transport=udp")
                authenticationHeader.response = getResponseHash(
                    user = botProperties.login,
                    realm = registerWWWAuthenticateResponse.realm,
                    password = botProperties.password,
                    method = SIPRequest.REGISTER,
                    serverIp = botProperties.serverHost,
                    nonce = registerWWWAuthenticateResponse.nonce,
                    nc = "00000001",
                    cnonce = cnonce,
                    qop = registerWWWAuthenticateResponse.qop
                )
                authenticationHeader.cNonce = cnonce
                authenticationHeader.nonceCount = 1
                authenticationHeader.qop = registerWWWAuthenticateResponse.qop
                if (registerWWWAuthenticateResponse.algorithm != null)
                    authenticationHeader.algorithm = registerWWWAuthenticateResponse.algorithm
                if (registerWWWAuthenticateResponse.opaque != null)
                    authenticationHeader.opaque = registerWWWAuthenticateResponse.opaque
                registerSipRequestBuilder.headers[SIPHeader.AUTHORIZATION] = authenticationHeader
                sipClient.send(registerSipRequestBuilder.toString().toByteArray())
                registerResponse = withTimeoutOrNull(sipTimeout) {
                    registerResponseChannel.receive()
                } ?: throw SipTimeoutException(SIP_TIMEOUT)
            }
            if (registerResponse.statusLine.statusCode == 200) {
                if (!botIsStarted.isClosedForSend) {
                    botIsStarted.send(true)
                }
                logger.trace { "Registration is ok" }
                registered = true
                botIsStarted.close()
            } else {
                if (!botIsStarted.isClosedForSend) {
                    botIsStarted.send(false)
                }
                logger.trace { "Registration is failed" }
                botIsStarted.close()
            }
            delay(REGISTER_DELAY * 1000L)
        } while (registered)
        //unregister bot client
        val expiresContactHeader = headerFactory.createContactHeader(
            addressFactory.createAddress(
                addressFactory.createSipURI(botProperties.login, botProperties.clientHost)
            )
        )?: throw SipTimeoutException(SIP_TIMEOUT)
        expiresContactHeader.expires = 0
        registerSipRequestBuilder!!.headers[SIPHeader.CSEQ] = headerFactory.createCSeqHeader(3L, SIPRequest.REGISTER)
        registerSipRequestBuilder.headers[SIPHeader.CONTACT] = expiresContactHeader
        sipClient.send(registerSipRequestBuilder.toString().toByteArray())
        var unregisterResponse = withTimeoutOrNull(sipTimeout) {
            registerResponseChannel.receive()
        } ?: throw SipTimeoutException(SIP_TIMEOUT)
        if (unregisterResponse.statusLine.statusCode == 401) {
            val unregisterWWWAuthenticateResponse = unregisterResponse.getHeader("WWW-Authenticate") as WWWAuthenticate
            val authenticationHeader = headerFactory.createAuthorizationHeader("Digest")
            authenticationHeader.username = botProperties.login
            authenticationHeader.realm = unregisterWWWAuthenticateResponse.realm
            authenticationHeader.nonce = unregisterWWWAuthenticateResponse.nonce
            authenticationHeader.uri = addressFactory.createURI("sip:${botProperties.serverHost};transport=udp")
            authenticationHeader.response = getResponseHash(
                user = botProperties.login,
                realm = unregisterWWWAuthenticateResponse.realm,
                password = botProperties.password,
                method = SIPRequest.REGISTER,
                serverIp = botProperties.serverHost,
                nonce = unregisterWWWAuthenticateResponse.nonce,
                nc = "00000002",
                cnonce = cnonce,
                qop = unregisterWWWAuthenticateResponse.qop
            )
            authenticationHeader.cNonce = cnonce
            authenticationHeader.nonceCount = 2
            authenticationHeader.qop = unregisterWWWAuthenticateResponse.qop
            if (unregisterWWWAuthenticateResponse.algorithm != null)
                authenticationHeader.algorithm = unregisterWWWAuthenticateResponse.algorithm
            if (unregisterWWWAuthenticateResponse.opaque != null)
                authenticationHeader.opaque = unregisterWWWAuthenticateResponse.opaque
            registerSipRequestBuilder.headers[SIPHeader.AUTHORIZATION] = authenticationHeader
            sipClient.send(registerSipRequestBuilder.toString().toByteArray())
            unregisterResponse = withTimeoutOrNull(sipTimeout) {
                registerResponseChannel.receive()
            } ?: throw SipTimeoutException(SIP_TIMEOUT)
        }
        if (unregisterResponse.statusLine.statusCode == 200) {
            logger.trace { "Unregistration is ok" }
        } else {
            logger.trace { "Unregistration is failed" }
        }
    }

    suspend fun unregister() {
        registered = false
    }

    suspend fun optionsRequestEvent(request: SIPRequest) {
        val response = request.createResponse(200)
        response.setHeader(headerFactory.createAllowHeader("PRACK, INVITE, ACK, BYE, CANCEL, UPDATE, INFO, SUBSCRIBE, NOTIFY, REFER, MESSAGE, OPTIONS"))
        response.setHeader(headerFactory.createSupportedHeader("replaces, norefersub, extended-refer, timer, outbound, path, X-cisco-serviceuri"))
        sipClient.send(response.toString().toByteArray())
    }

    suspend fun startSessionAwait(to: String): UserSession {
        val session = UserSession(
            to = to,
            botProperties = botProperties,
            addressFactory = addressFactory,
            headerFactory = headerFactory,
            sipClient = sipClient,
            botClient = this,
            sessionCache = sessionCache,
            rtpNioEventLoopGroup = rtpNioEventLoopGroup,
            botCoroutineDispatcher = botCoroutineDispatcher
        )
        sessionCache.put("${to}@${botProperties.serverHost}", session)
        if (session.startCall()) {
            return session
        } else {
            throw SipClientNotAvailableException("Abonent not available")
        }
    }

    suspend fun endSession(user: String) {
        val sessionId = "${user}@${botProperties.serverHost}"
        val session = sessionCache.get(sessionId)
            ?: throw SipClientNotAvailableException(NO_SUCH_SESSION)
        session.stopCall()
        sessionCache.remove(sessionId)
        endMediaSessionEventListener(user)
    }

    suspend fun sendAudioData(user: String, data: ByteArray) {
        val compressData = compressToG711(inpb = data, useALaw = true)
        val session = sessionCache.get("${user}@${botProperties.serverHost}")
            ?: throw SipClientNotAvailableException(NO_SUCH_SESSION)
        session.sendAudioData(compressData)
    }

    fun resetQuietAnalizer(user: String) {
        val session = sessionCache.get("${user}@${botProperties.serverHost}")
            ?: throw SipClientNotAvailableException(NO_SUCH_SESSION)
        session.resetQuietAnalizer()
    }

    suspend fun registerResponseEvent(response: SIPResponse) {
        registerResponseChannel.send(response)
    }

    private fun handleInternalThrowable(t: Throwable) {
        logger.error(t) { t.message }
    }
}