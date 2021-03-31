package ru.stech.sip.client

import gov.nist.javax.sip.address.AddressImpl
import gov.nist.javax.sip.header.Via
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import ru.stech.BotClient
import ru.stech.sip.cache.SipSessionCache
import ru.stech.sip.cache.SipSessionCacheImpl
import javax.sip.message.MessageFactory
import kotlin.jvm.Throws

@ExperimentalCoroutinesApi
class SipClientInboundHandler(private val sessionCache: SipSessionCache = SipSessionCacheImpl(),
                              private val coroutineDispatcher: CoroutineDispatcher,
                              private val messageFactory: MessageFactory,
                              private val botClient: BotClient
): ChannelInboundHandlerAdapter() {

    @Throws(Exception::class)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        CoroutineScope(coroutineDispatcher).launch {
            val inBuffer = msg as DatagramPacket
            try {
                val buf = inBuffer.content()
                val bytes = ByteArray(buf.readableBytes())
                buf.readBytes(bytes)
                val body = String(bytes)
                if (isResponse(body)) {
                    processResponse(messageFactory.createResponse(body) as SIPResponse)
                } else {
                    processRequest(messageFactory.createRequest(body) as SIPRequest)
                }
            } catch (e: Exception) {
                throw e
            } finally {
                inBuffer.release()
            }
        }
    }

    private fun isResponse(body: String): Boolean {
        return body.startsWith("SIP/2.0")
    }

    private suspend fun processRequest(request: SIPRequest) {
        when (request.requestLine.method) {
            SIPRequest.OPTIONS -> {
                botClient.optionsRequestEvent(request)
            }
            SIPRequest.BYE -> {
                val session = sessionCache.get((request.fromHeader.address as AddressImpl).userAtHostPort)
                session?.byeRequestEvent(request)
            }
            SIPRequest.INVITE ->{
                val session = sessionCache.get((request.fromHeader.address as AddressImpl).userAtHostPort)
                session?.inviteRequestEvent(request)
            }
            else -> throw IllegalArgumentException()
        }
    }

    private suspend fun processResponse(response: SIPResponse) {
        when (response.cSeqHeader.method) {
            SIPRequest.REGISTER -> {
                botClient.registerResponseEvent(response)
            }
            SIPRequest.INVITE -> {
                val session = sessionCache.get((response.toHeader.address as AddressImpl).userAtHostPort)
                session?.inviteResponseEvent(response)
            }
            SIPRequest.BYE -> {
                val session = sessionCache.get((response.toHeader.address as AddressImpl).userAtHostPort)
                session?.byeResponseEvent(response)
            }
            else -> throw IllegalArgumentException()
        }
    }

}