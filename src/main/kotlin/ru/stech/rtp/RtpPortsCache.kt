package ru.stech.rtp

import java.util.concurrent.ConcurrentLinkedQueue
import javax.sip.SipException

class RtpPortsCache(private val diapason: Pair<Int, Int>) {
    companion object {
        private const val NO_FREE_RTP_PORT = "No free rtp port"
    }
    private val portsQueue = ConcurrentLinkedQueue<Int>()
    init {
        for (p in diapason.first .. diapason.second step 2) {
            returnPort(p)
        }
    }

    fun getFreePort(): Int {
        return portsQueue.poll() ?: throw SipException(NO_FREE_RTP_PORT)
    }

    fun returnPort(port: Int) {
        portsQueue.add(port)
    }
}