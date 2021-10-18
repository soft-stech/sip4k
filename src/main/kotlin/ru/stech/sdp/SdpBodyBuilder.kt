package ru.stech.sdp

import ru.stech.util.LIBNAME
import ru.stech.util.LOCALHOST

class SdpBodyBuilder(val remoteRdpHost: String, val rtpHost: String, val rtpLocalPort: Int) {

    override fun toString(): String {
        val sdp = if (LOCALHOST != null && rtpLocalPort != null) "v=0\r\n" +
                "o=- Z 0 IN IP4 ${rtpHost}\r\n" +
                "s=$LIBNAME\r\n" +
                "t=0 0\r\n" +
                "m=audio 8000 RTP/AVP 8 120\r\n" +
                "c=IN IP4 ${rtpHost}\r\n" +
                "a=sendrecv\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:120 telephone-event/8000\r\n" +
                "a=fmtp:120 0-16" else ""
        return sdp
    }

}