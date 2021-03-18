package ru.stech.sip

import gov.nist.javax.sip.address.GenericURI
import gov.nist.javax.sip.header.RequestLine
import gov.nist.javax.sip.header.SIPHeader
import gov.nist.javax.sip.header.WWWAuthenticate
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import ru.stech.g711.compressToG711
import ru.stech.sip.cache.SipSessionCacheImpl
import ru.stech.sip.client.SipClient
import ru.stech.util.EXPIRES
import ru.stech.util.MAX_FORWARDS
import ru.stech.util.TRANSPORT
import ru.stech.util.getResponseHash
import ru.stech.util.randomString
import java.lang.IllegalArgumentException
import java.util.*
import javax.sip.SipFactory

@ExperimentalCoroutinesApi
class BotClient(
    val botProperties: BotProperties,
    val nthreads: Int,
    val cthreads: Int,
    val streamEventListener: (user: String, data: ByteArray, endOfPhrase: Boolean) -> Unit,
) {
    private val REGISTER_DELAY = 20
    private var registered = false
    private val eventLoopGroup = NioEventLoopGroup(nthreads)
    private val dispatcher = newFixedThreadPoolContext(cthreads, "cd")
    private val sipFactory = SipFactory.getInstance()
    private val messageFactory = sipFactory.createMessageFactory()
    private val headerFactory = sipFactory.createHeaderFactory()
    private val addressFactory = sipFactory.createAddressFactory()

    private lateinit var sipClient: SipClient

    val sessionCache = SipSessionCacheImpl()
    val registerResponseChannel = Channel<SIPResponse>(0)
    private val botIsStarted = Channel<Boolean>(0)

    suspend fun startAwait(): Boolean {
        if (!botIsStarted.isClosedForSend) {
            sipClient = SipClient(
                serverHost = botProperties.serverHost,
                serverPort = botProperties.serverSipPort,
                sipListenPort = botProperties.clientSipPort,
                workerGroup = eventLoopGroup,
                dispatcher = dispatcher,
                messageFactory = messageFactory,
                botClient = this
            )
            sipClient.start()
            CoroutineScope(dispatcher).launch {
                startRegistrationByPeriod()
            }
            return botIsStarted.receive()
        } else {
            throw IllegalArgumentException("Bot already runned")
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
            registerSipRequestBuilder.headers[SIPHeader.ALLOW] = headerFactory.createAllowHeader("INVITE, ACK, CANCEL, BYE, NOTIFY, REFER, MESSAGE, OPTIONS, INFO, SUBSCRIBE")
            registerSipRequestBuilder.headers[SIPHeader.USER_AGENT] = headerFactory.createUserAgentHeader(listOf("Sip4k"))
            registerSipRequestBuilder.headers[SIPHeader.ALLOW_EVENTS] = headerFactory.createAllowEventsHeader("presence, kpml, talk")
            registerSipRequestBuilder.headers[SIPHeader.CONTENT_LENGTH] = headerFactory.createContentLengthHeader(0)

            sipClient.send(registerSipRequestBuilder.toString().toByteArray())
            var registerResponse = registerResponseChannel.receive()
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
                authenticationHeader.algorithm = registerWWWAuthenticateResponse.algorithm
                authenticationHeader.opaque = registerWWWAuthenticateResponse.opaque
                registerSipRequestBuilder.headers[SIPHeader.AUTHORIZATION] = authenticationHeader
                sipClient.send(registerSipRequestBuilder.toString().toByteArray())
                registerResponse = registerResponseChannel.receive()
            }
            if (registerResponse.statusLine.statusCode == 200) {
                if (!botIsStarted.isClosedForSend) {
                    botIsStarted.send(true)
                }
                print("Registration ok")
                registered = true
                botIsStarted.close()
            } else {
                if (!botIsStarted.isClosedForSend) {
                    botIsStarted.send(false)
                }
                print("Registration failed")
                botIsStarted.close()
            }
            delay(REGISTER_DELAY * 1000L)
        } while (registered)
        //unregister bot client
        val expiresContactHeader = headerFactory.createContactHeader(
            addressFactory.createAddress(
                addressFactory.createSipURI(botProperties.login, botProperties.clientHost)
            )
        )
        expiresContactHeader.expires = 0
        registerSipRequestBuilder!!.headers[SIPHeader.CSEQ] = headerFactory.createCSeqHeader(3L, SIPRequest.REGISTER)
        registerSipRequestBuilder.headers[SIPHeader.CONTACT] = expiresContactHeader
        sipClient.send(registerSipRequestBuilder.toString().toByteArray())
        var unregisterResponse = registerResponseChannel.receive()
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
            authenticationHeader.algorithm = unregisterWWWAuthenticateResponse.algorithm
            authenticationHeader.opaque = unregisterWWWAuthenticateResponse.opaque
            registerSipRequestBuilder.headers[SIPHeader.AUTHORIZATION] = authenticationHeader
            sipClient.send(registerSipRequestBuilder.toString().toByteArray())
            unregisterResponse = registerResponseChannel.receive()
        }
        if (unregisterResponse.statusLine.statusCode == 200) {
            print("unregistration is ok\n")
        } else {
            print("unregistration is not ok\n")
        }
    }

    suspend fun unregister() {
        registered = false
    }

    suspend fun optionsEvent(request: SIPRequest) {
        val response = request.createResponse(200);
        response.setHeader(headerFactory.createAllowHeader("INVITE, ACK, CANCEL, BYE, NOTIFY, REFER, MESSAGE, OPTIONS, INFO, SUBSCRIBE"))
        response.setHeader(headerFactory.createSupportedHeader("replaces, norefersub, extended-refer, timer, outbound, path, X-cisco-serviceuri"))
        sipClient.send(response.toString().toByteArray())
    }

    suspend fun startSessionAwait(to: String): Boolean {
        val botClient = this
        val session = UserSession(
            to = to,
            botProperties = botProperties,
            addressFactory = addressFactory,
            headerFactory = headerFactory,
            sipClient = sipClient,
            botClient = botClient,
            sessionCache = sessionCache,
            dispatcher = dispatcher
        )
        sessionCache.put("${to}@${botProperties.serverHost}", session)
        return session.startCall()
    }

    suspend fun sendAudioData(user: String, data: ByteArray) {
        val compressData = compressToG711(inpb = data, useALaw = true)
        val session = sessionCache.get("${user}@${botProperties.serverHost}")
        session?.sendAudioData(compressData)
    }

    fun sendInviteResponse(request: SIPRequest) {
        sipClient.send(request.createResponse(200).toString().toByteArray())
    }
}