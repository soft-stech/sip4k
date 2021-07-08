package ru.stech.sip.client

import gov.nist.javax.sip.address.AddressImpl
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.stech.sip.Factories
import ru.stech.sip.cache.SipConnectionCache
import javax.sip.SipException

class SipClientInboundHandler(
    private val sipClient: SipClient,
    private val sipConnectionCache: SipConnectionCache
) : ChannelInboundHandlerAdapter() {
    companion object {
        private const val UNKNOWN_SIP_METHOD = "Unknown sip method name"
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        CoroutineScope(Dispatchers.Default).launch {
            val inBuffer = msg as DatagramPacket
            try {
                val buf = inBuffer.content()
                val bytes = ByteArray(buf.readableBytes())
                buf.readBytes(bytes)
                val body = String(bytes)
                if (isResponse(body)) {
                    processResponse(Factories.messageFactory.createResponse(body) as SIPResponse)
                } else {
                    processRequest(Factories.messageFactory.createRequest(body) as SIPRequest)
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
        val sipId = (request.fromHeader.address as AddressImpl).userAtHostPort
        when (request.requestLine.method) {
            SIPRequest.OPTIONS -> {
                sipClient.optionsRequestEvent(request)
            }
            SIPRequest.BYE -> {
                val sipConnection = sipConnectionCache[sipId]
                sipConnection.byeRequestEvent(request)
            }
            SIPRequest.INVITE -> {
                val sipConnection = sipConnectionCache[sipId]
                sipConnection.inviteRequestEvent(request)
            }
            else -> throw SipException(UNKNOWN_SIP_METHOD)
        }
    }

    private suspend fun processResponse(response: SIPResponse) {
        val sipId = (response.toHeader.address as AddressImpl).userAtHostPort
        when (response.cSeqHeader.method) {
            SIPRequest.REGISTER -> {
                sipClient.registerResponseEvent(response)
            }
            SIPRequest.INVITE -> {
                val sipConnection = sipConnectionCache[sipId]
                sipConnection.inviteResponseEvent(response)
            }
            SIPRequest.BYE -> {
                val sipConnection = sipConnectionCache[sipId]
                sipConnection.byeResponseEvent(response)
            }
            else -> throw SipException(UNKNOWN_SIP_METHOD)
        }
    }

}