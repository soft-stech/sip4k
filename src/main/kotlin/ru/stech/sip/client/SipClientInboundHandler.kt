package ru.stech.sip.client

import gov.nist.javax.sip.address.AddressImpl
import gov.nist.javax.sip.header.From
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
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

    private val log = LoggerFactory.getLogger(SipClientInboundHandler::class.java)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        CoroutineScope(Dispatchers.Default).launch {
            val inBuffer = msg as DatagramPacket
            try {
                val buf = inBuffer.content()
                val bytes = ByteArray(buf.readableBytes())
                buf.readBytes(bytes)
                val body = String(bytes)
                if (log.isTraceEnabled) log.trace("SIP body=${body}")
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

                if (log.isTraceEnabled) log.trace("processing OPTIONS request")
                sipClient.optionsRequestEvent(request)
            }
            SIPRequest.BYE -> {
                if (log.isTraceEnabled) log.trace("processing BYE request")
                if (sipConnectionCache.isExist(sipId)) {
                    if (log.isTraceEnabled) log.trace("processing BYE request session exist")
                    val sipConnection = sipConnectionCache[sipId]
                    sipConnection.byeRequestEvent(request)
                } else {
                    if (log.isTraceEnabled) log.trace("processing BYE request session doesn't exist")
                    sipClient.send(request.createResponse(200).toString().toByteArray())
                }
            }
            SIPRequest.INVITE -> {
                if (!sipConnectionCache.isExist(sipId)) {
                    if (log.isTraceEnabled) log.trace("processing INVITE request session exist")
                    val fromSipId = ((request.fromHeader as From).address as AddressImpl).displayName!!.toString()
                    val sipConnection = sipClient.initIncomingCallConnection(fromSipId)

                    sipConnection.incomingCallRequestEvent(request)
                    sipClient.incomingCallEvent(fromSipId)
                } else {

                    if (log.isTraceEnabled) log.trace("processing INVITE request session doesn't exist")
                    val sipConnection = sipConnectionCache[sipId]
                    sipConnection.inviteRequestEvent(request)
                }
            }
            SIPRequest.ACK -> {
                if (!sipConnectionCache.isExist(sipId)) {
                    if (log.isTraceEnabled) log.trace("processing ACK request session exist")
                    val sipConnection = sipConnectionCache[sipId]
                    sipConnection.ackRequestEvent(request)
                } else {
                    if (log.isTraceEnabled) log.trace("processing ACK request session exist")
                    sipClient.send(request.createResponse(200).toString().toByteArray())
                }
            }
            SIPRequest.CANCEL -> {
                if (log.isTraceEnabled) log.trace("processing CANCEL request")
                sipClient.send(request.createResponse(200).toString().toByteArray())
            }
            else -> {
                if (log.isTraceEnabled) log.trace("processing request ERROR UNKNOWN_SIP_METHOD ${request.requestLine.method}")
                throw SipException(UNKNOWN_SIP_METHOD)
            }
        }
    }

    private suspend fun processResponse(response: SIPResponse) {
        val sipId = (response.toHeader.address as AddressImpl).userAtHostPort
        when (response.cSeqHeader.method) {
            SIPRequest.REGISTER -> {

                if (log.isTraceEnabled) log.trace("processing REGISTER response")
                sipClient.registerResponseEvent(response)
            }
            SIPRequest.INVITE -> {
                if (log.isTraceEnabled) log.trace("processing INVITE response")
                val sipConnection = sipConnectionCache[sipId]
                sipConnection.inviteResponseEvent(response)
            }
            SIPRequest.BYE -> {
                if (log.isTraceEnabled) log.trace("processing BYE response")
                val sipConnection = sipConnectionCache[sipId]
                sipConnection.byeResponseEvent(response)
            }
            else -> {
                if (log.isTraceEnabled) log.trace("processing response ERROR UNKNOWN_SIP_METHOD ${response.cSeqHeader.method}")
                throw SipException(UNKNOWN_SIP_METHOD)
            }
        }
    }

}