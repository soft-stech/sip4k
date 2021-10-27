package ru.stech.sip

import gov.nist.javax.sip.address.SipUri
import gov.nist.javax.sip.header.SIPHeader
import gov.nist.javax.sip.header.StatusLine
import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse
import ru.stech.util.LIBNAME
import javax.sip.header.Header

class SipResponseBuilder(
    val statusCode: Int,
    val request: SIPRequest,
    val sipId: String,
    val sipListenPort: Int,
    val fromTag: String,
    val messageBody: String
) {
    override fun toString(): String {
        val headers: MutableMap<String, Header> = linkedMapOf()
        val requestLine = StatusLine()
        requestLine.statusCode = statusCode
        requestLine.reasonPhrase = SIPResponse.getReasonPhrase(statusCode)
        requestLine.sipVersion = "SIP/2.0"

        headers[SIPHeader.VIA] =
            request.viaHeaders.first()
        headers[SIPHeader.CONTACT] = Factories.headerFactory.createContactHeader(
            Factories.addressFactory.createAddress(
                Factories.addressFactory.createSipURI(
                    sipId,
                    "${(request.requestLine.uri as SipUri).host}:${sipListenPort}"
                )
            )
        )
        request.toHeader.tag = fromTag
        headers[SIPHeader.TO] = request.toHeader
        headers[SIPHeader.FROM] = request.fromHeader
        headers[SIPHeader.CALL_ID] = request.callIdHeader
        headers[SIPHeader.CSEQ] = request.cSeqHeader
        headers[SIPHeader.ALLOW] =
            Factories.headerFactory.createAllowHeader("INVITE, ACK, CANCEL, BYE, NOTIFY, REFER, MESSAGE, OPTIONS, INFO, SUBSCRIBE")
        headers[SIPHeader.REQUIRE] = Factories.headerFactory.createRequireHeader("timer")
        headers[SIPHeader.CONTENT_TYPE] = Factories.headerFactory.createContentTypeHeader("application", "sdp")
        headers[SIPHeader.USER_AGENT] = Factories.headerFactory.createUserAgentHeader(listOf(LIBNAME))

        headers[SIPHeader.CONTENT_LENGTH] =
            Factories.headerFactory.createContentLengthHeader(messageBody.toByteArray().size)
        val builder = StringBuilder()
        builder.append(requestLine.toString())
        for ((_, value) in headers) {
            builder.append(value.toString())
        }
        builder.append("\r\n")
        builder.append(messageBody)
        return builder.toString()
    }
}