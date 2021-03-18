package ru.stech.sip.client

import gov.nist.javax.sip.address.AddressImpl
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.stech.sip.BotClient
import ru.stech.sip.cache.SipSessionCache
import ru.stech.sip.cache.SipSessionCacheImpl
import javax.sip.message.MessageFactory

class SipClientHandler(private val sessionCache: SipSessionCache = SipSessionCacheImpl(),
                       private val dispatcher: CoroutineDispatcher,
                       private val messageFactory: MessageFactory,
                       private val botClient: BotClient): ChannelInboundHandlerAdapter() {

    @Throws(Exception::class)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        CoroutineScope(dispatcher).launch {
            val inBuffer = msg as DatagramPacket
            val buf = inBuffer.content()
            val bytes = ByteArray(buf.readableBytes())
            buf.readBytes(bytes)
            val body = String(bytes)
            if (isResponse(body)) {
                processResponse(messageFactory.createResponse(body) as SIPResponse)
            } else {
                processRequest(messageFactory.createRequest(body) as SIPRequest)
            }
        }
    }

    private fun isResponse(body: String): Boolean {
        return body.startsWith("SIP/2.0")
    }

    private suspend fun processRequest(request: SIPRequest) {
        when (request.requestLine.method) {
            SIPRequest.OPTIONS -> {
                botClient.optionsEvent(request)
            }
            SIPRequest.BYE -> {
                val session = sessionCache.get((request.fromHeader.address as AddressImpl).userAtHostPort)
                session?.byeEvent(request)
            }
            SIPRequest.INVITE ->{
                botClient.sendInviteResponse(request);
            }
            else -> throw IllegalArgumentException()
        }
    }

    private suspend fun processResponse(response: SIPResponse) {
        when (response.cSeqHeader.method) {
            SIPRequest.REGISTER -> {
                botClient.registerResponseChannel.send(response)
            }
            SIPRequest.INVITE -> {
                val session = sessionCache.get((response.toHeader.address as AddressImpl).userAtHostPort)
                session?.inviteResponseChannel?.send(response)
            }
            else -> throw IllegalArgumentException()
        }
    }

}