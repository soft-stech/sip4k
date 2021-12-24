package ru.stech.sip.client

import gov.nist.javax.sip.address.GenericURI
import gov.nist.javax.sip.header.RequestLine
import gov.nist.javax.sip.header.SIPHeader
import gov.nist.javax.sip.header.WWWAuthenticate
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.util.internal.SocketUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import ru.stech.rtp.RtpPortsCache
import ru.stech.sip.Factories
import ru.stech.sip.SipRequestBuilder
import ru.stech.sip.cache.SipConnectionCacheImpl
import ru.stech.sip.exceptions.SipException
import ru.stech.util.EXPIRES
import ru.stech.util.LIBNAME
import ru.stech.util.LOCALHOST
import ru.stech.util.MAX_FORWARDS
import ru.stech.util.TIMEOUT_MESSAGE
import ru.stech.util.TRANSPORT
import ru.stech.util.getResponseHash
import ru.stech.util.randomString
import ru.stech.util.sipNioEventLoop
import java.time.LocalDateTime
import java.util.*
import io.netty.channel.Channel as NettyChannel

class SipClient(
    val serverHost: String,
    val serverPort: Int,
    val sipListenPort: Int,
    val sipId: String,
    val password: String,
    val rtpStreamEvent: suspend (user: String, data: ByteArray) -> Unit,
    val rtpDisconnectEvent: (user: String, byAbonent: Boolean) -> Unit,
    private val portsRange: Pair<Int, Int>,
    val incomingCallEvent: (user: String) -> Unit,
    private val sipTimeoutMillis: Long
) {
    companion object {
        private const val REGISTER_DELAY = 10L
        private const val ERROR_IN_UNREGISTER_RESPONSE = "Error in unregister response"
        private const val ERROR_IN_REGISTER_RESPONSE = "Error in register response"
    }

    private var isWorking = true
    private val sipClientIsStarted = Channel<Unit>(1)
    private lateinit var senderChannel: NettyChannel
    private val connectionCache = SipConnectionCacheImpl()
    private val rtpPortsCache = RtpPortsCache(portsRange)
    private val registerResponseChannel: Channel<SIPResponse> = Channel(0)

    private val log = LoggerFactory.getLogger(SipClient::class.java)

    suspend fun start() {
        //create netty channel
        val bootstrap = Bootstrap()
            .group(sipNioEventLoop)
            .channel(NioDatagramChannel::class.java)
            .handler(object : ChannelInitializer<NioDatagramChannel>() {
                override fun initChannel(ch: NioDatagramChannel) {
                    val pipeline = ch.pipeline()
                    pipeline.addLast(
                        SipClientInboundHandler(
                            sipClient = this@SipClient,
                            sipConnectionCache = connectionCache,
                        )
                    )
                }
            })
        senderChannel = bootstrap.bind(sipListenPort).syncUninterruptibly().channel()
        //sip client initialization and registration
        CoroutineScope(Dispatchers.Default).launch {
            var cSeq = 1L
            val registerBranch = "z9hG4bK${UUID.randomUUID()}"
            val registerCallId = UUID.randomUUID().toString()
            val cnonce = UUID.randomUUID().toString()
            var registerSipRequestBuilder: SipRequestBuilder
            var registred: Boolean = false
            do {
                if(log.isTraceEnabled)  log.trace("try to register ${LocalDateTime.now()}")
                registerSipRequestBuilder = SipRequestBuilder(
                    RequestLine(
                        GenericURI("sip:${serverPort};transport=$TRANSPORT"),
                        SIPRequest.REGISTER
                    )
                )
                registerSipRequestBuilder.headers[SIPHeader.VIA] = Factories.headerFactory.createViaHeader(
                    LOCALHOST,
                    sipListenPort,
                    TRANSPORT,
                    registerBranch
                )
                registerSipRequestBuilder.headers[SIPHeader.MAX_FORWARDS] =
                    Factories.headerFactory.createMaxForwardsHeader(
                        MAX_FORWARDS
                    )

                val contactSipURI = Factories.addressFactory.createSipURI(sipId, LOCALHOST)
                contactSipURI.port = sipListenPort
                contactSipURI.transportParam = TRANSPORT
                registerSipRequestBuilder.headers[SIPHeader.CONTACT] = Factories.headerFactory.createContactHeader(
                    Factories.addressFactory.createAddress(contactSipURI)
                )

                val toSipURI = Factories.addressFactory.createSipURI(sipId, serverHost)
                toSipURI.transportParam = TRANSPORT
                registerSipRequestBuilder.headers[SIPHeader.TO] =
                    Factories.headerFactory.createToHeader(Factories.addressFactory.createAddress(toSipURI), null)

                val fromTag = randomString(8)
                val fromSipURI = Factories.addressFactory.createSipURI(sipId, serverHost)
                fromSipURI.transportParam = TRANSPORT
                registerSipRequestBuilder.headers[SIPHeader.FROM] = Factories.headerFactory.createFromHeader(
                    Factories.addressFactory.createAddress(fromSipURI), fromTag
                )

                registerSipRequestBuilder.headers[SIPHeader.CALL_ID] =
                    Factories.headerFactory.createCallIdHeader(registerCallId)
                registerSipRequestBuilder.headers[SIPHeader.CSEQ] =
                    Factories.headerFactory.createCSeqHeader(cSeq++, SIPRequest.REGISTER)
                registerSipRequestBuilder.headers[SIPHeader.EXPIRES] =
                    Factories.headerFactory.createExpiresHeader(EXPIRES)
                registerSipRequestBuilder.headers[SIPHeader.USER_AGENT] =
                    Factories.headerFactory.createUserAgentHeader(listOf(LIBNAME))
                registerSipRequestBuilder.headers[SIPHeader.CONTENT_LENGTH] =
                    Factories.headerFactory.createContentLengthHeader(0)
                send(registerSipRequestBuilder.toString().toByteArray())
                var registerResponse = withTimeoutOrNull(sipTimeoutMillis) {
                    registerResponseChannel.receive()
                }


                if(registerResponse != null){
                    if(log.isTraceEnabled) log.trace("registerResponse ${registerResponse} ${LocalDateTime.now()}")
                    if (registerResponse!!.statusLine.statusCode == 401) {
                        val registerWWWAuthenticateResponse =
                            registerResponse.getHeader("WWW-Authenticate") as WWWAuthenticate
                        registerSipRequestBuilder.headers[SIPHeader.CSEQ] =
                            Factories.headerFactory.createCSeqHeader(cSeq++, "REGISTER")
                        val authenticationHeader = Factories.headerFactory.createAuthorizationHeader("Digest")
                        authenticationHeader.username = sipId
                        authenticationHeader.realm = registerWWWAuthenticateResponse.realm
                        authenticationHeader.nonce = registerWWWAuthenticateResponse.nonce
                        authenticationHeader.uri = Factories.addressFactory.createURI("sip:${serverHost};transport=udp")
                        authenticationHeader.response = getResponseHash(
                            user = sipId,
                            realm = registerWWWAuthenticateResponse.realm,
                            password = password,
                            method = SIPRequest.REGISTER,
                            serverIp = serverHost,
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
                        send(registerSipRequestBuilder.toString().toByteArray())
                        registerResponse = withTimeoutOrNull(sipTimeoutMillis) {
                            registerResponseChannel.receive()
                        }
                    }

                    if(registerResponse != null){
                        if (registerResponse!!.statusLine!!.statusCode == 200) {
                            if(log.isTraceEnabled) log.trace("get register response: ${registerResponse!!.statusLine.statusCode}")
                            if (!registred) {
                                if(log.isTraceEnabled) log.trace("registred changed to true")
                                registred = true
                                sipClientIsStarted.send(Unit)
                            }else{
                                if(log.isTraceEnabled) log.trace("updated registration")
                            }
                        }else{
                            if(log.isTraceEnabled) log.trace("not expected registerResponse statusCode - ${registerResponse!!.statusLine!!.statusCode}")
                        }
                    }else
                        if(log.isTraceEnabled) log.trace("not registered: ${LocalDateTime.now()}")
                }else
                    if(log.isTraceEnabled) log.trace("not registered: ${LocalDateTime.now()}")
                delay(REGISTER_DELAY * 1000L)
            } while (isWorking)
            if(log.isTraceEnabled) log.trace("registered ${isWorking} ${LocalDateTime.now()}")
            //unregister bot client
            val expiresContactHeader = Factories.headerFactory.createContactHeader(
                Factories.addressFactory.createAddress(
                    Factories.addressFactory.createSipURI(sipId, LOCALHOST)
                )
            ) ?: throw SipException(TIMEOUT_MESSAGE)
            expiresContactHeader.expires = 0
            registerSipRequestBuilder.headers[SIPHeader.CSEQ] =
                Factories.headerFactory.createCSeqHeader(cSeq++, SIPRequest.REGISTER)
            registerSipRequestBuilder.headers[SIPHeader.CONTACT] = expiresContactHeader
            send(registerSipRequestBuilder.toString().toByteArray())
            var unregisterResponse = withTimeoutOrNull(sipTimeoutMillis) {
                registerResponseChannel.receive()
            } ?: throw SipException(TIMEOUT_MESSAGE)
            if (unregisterResponse.statusLine.statusCode == 401) {
                val unregisterWWWAuthenticateResponse =
                    unregisterResponse.getHeader("WWW-Authenticate") as WWWAuthenticate
                val authenticationHeader = Factories.headerFactory.createAuthorizationHeader("Digest")
                authenticationHeader.username = sipId
                authenticationHeader.realm = unregisterWWWAuthenticateResponse.realm
                authenticationHeader.nonce = unregisterWWWAuthenticateResponse.nonce
                authenticationHeader.uri = Factories.addressFactory.createURI("sip:${serverHost};transport=udp")
                authenticationHeader.response = getResponseHash(
                    user = sipId,
                    realm = unregisterWWWAuthenticateResponse.realm,
                    password = password,
                    method = SIPRequest.REGISTER,
                    serverIp = serverHost,
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
                send(registerSipRequestBuilder.toString().toByteArray())
                unregisterResponse = withTimeoutOrNull(sipTimeoutMillis) {
                    registerResponseChannel.receive()
                } ?: throw SipException(TIMEOUT_MESSAGE)
                if (unregisterResponse.statusLine.statusCode != 200) {
                    throw SipException(ERROR_IN_UNREGISTER_RESPONSE)
                }
            }
            senderChannel.close()
            senderChannel.closeFuture().syncUninterruptibly()
        }
        sipClientIsStarted.receive()
    }

    fun stop() {
        isWorking = false
    }

    fun send(data: ByteArray) {
        if (log.isTraceEnabled) log.trace("SIP body=${String(data)}")
        senderChannel.writeAndFlush(
            DatagramPacket(
                Unpooled.copiedBuffer(data),
                SocketUtils.socketAddress(serverHost, serverPort)
            )
        ).syncUninterruptibly()
    }

    fun sendAudioData(to: String, data: ByteArray) {
        connectionCache["${to}@${serverHost}"].sendAudioData(data)
    }

    suspend fun optionsRequestEvent(request: SIPRequest) {
        val response = request.createResponse(200)
        response.setHeader(Factories.headerFactory.createAllowHeader("PRACK, INVITE, ACK, BYE, CANCEL, UPDATE, INFO, SUBSCRIBE, NOTIFY, REFER, MESSAGE, OPTIONS"))
        response.setHeader(Factories.headerFactory.createSupportedHeader("replaces, norefersub, extended-refer, timer, outbound, path, X-cisco-serviceuri"))
        send(response.toString().toByteArray())
    }

    suspend fun registerResponseEvent(response: SIPResponse) {
        registerResponseChannel.send(response)
    }

    suspend fun initIncomingCallConnection(to: String): SipConnection {
        if(log.isTraceEnabled) log.trace("init incoming call connection to: ${to}")
        val sipConnection = SipConnection(
            to = to,
            sipClient = this,
            sipConnectionCache = connectionCache,
            rtpPortsCache = rtpPortsCache,
            sipTimeoutMillis = sipTimeoutMillis,
            rtpStreamEvent = rtpStreamEvent,
            rtpDisconnectEvent = rtpDisconnectEvent
        )
        connectionCache.put("${to}@${serverHost}", sipConnection)
        return sipConnection
    }

    suspend fun startCall(to: String) {
        if(log.isTraceEnabled) log.trace("starting call to: ${to}")
        val sipConnection = SipConnection(
            to = to,
            sipClient = this,
            sipConnectionCache = connectionCache,
            rtpPortsCache = rtpPortsCache,
            sipTimeoutMillis = sipTimeoutMillis,
            rtpStreamEvent = rtpStreamEvent,
            rtpDisconnectEvent = rtpDisconnectEvent
        )
        connectionCache.put("${to}@${serverHost}", sipConnection)
        sipConnection.startCall()
    }

    suspend fun stopCall(to: String) {
        if(log.isTraceEnabled) log.trace("stop call to: ${to}")

        connectionCache["${to}@${serverHost}"].stopCall()
    }

    fun isUserActive(to: String): Boolean {
        if(log.isTraceEnabled) log.trace("checking user is active: ${to}")
        return connectionCache.isExist("${to}@${serverHost}")
    }
}
