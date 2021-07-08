package ru.stech.sip

import gov.nist.javax.sip.header.RequestLine
import gov.nist.javax.sip.header.SIPHeader
import ru.stech.util.LIBNAME
import javax.sip.header.ContentLengthHeader
import javax.sip.header.Header

class SipRequestBuilder(
    val requestLine: RequestLine,
    val headers: MutableMap<String, Header> = linkedMapOf(),
    var rtpHost: String? = null,
    var rtpPort: Int? = null,
    var rtpSessionId: String? = null
) {
    override fun toString(): String {
        val sdp = if (rtpHost != null && rtpPort != null) "v=0\r\n" +
                "o=- $rtpSessionId 0 IN IP4 $rtpHost\r\n" +
                "s=${LIBNAME}\r\n" +
                "t=0 0\r\n" +
                "m=audio $rtpPort RTP/AVP 8 120\r\n" +
                "c=IN IP4 ${rtpHost}\r\n" +
                "a=sendrecv\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:120 telephone-event/8000\r\n" +
                "a=fmtp:120 0-16" else ""
        val contentLength = sdp.toByteArray().size
        (headers[SIPHeader.CONTENT_LENGTH] as ContentLengthHeader).contentLength = contentLength
        val builder = StringBuilder()
        builder.append(requestLine.toString())
        for ((_, value) in headers) {
            builder.append(value.toString())
        }
        builder.append("\r\n")
        builder.append(sdp)
        return builder.toString()
    }
}