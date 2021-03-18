package ru.stech.sip

import gov.nist.javax.sip.header.RequestLine
import gov.nist.javax.sip.header.SIPHeader
import javax.sip.header.ContentLengthHeader
import javax.sip.header.Header

class SipRequestBuilder(
    val requestLine: RequestLine,
    val headers: MutableMap<String, Header> = linkedMapOf(),
    var rtpHost: String? = null,
    var rtpPort: Int? = null
) {
    override fun toString(): String {
        val sdp = if (rtpHost != null && rtpPort != null) "v=0\r\n" +
                "o=Z 90869817855 1 IN IP4 ${rtpHost}\r\n" +
                "s=sip4k\r\n" +
                "c=IN IP4 ${rtpHost}\r\n" +
                "t=0 0\r\n" +
                "m=audio $rtpPort RTP/AVP 8 0 9 4 18\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=rtpmap:9 G722/8000\r\n" +
                "a=rtpmap:4 G723/8000\r\n" +
                "a=rtpmap:18 G729/8000\r\n" +
                "a=maxptime:150\r\n" +
                "a=ptime:20\r\n" +
                "a=sendrecv" else ""
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