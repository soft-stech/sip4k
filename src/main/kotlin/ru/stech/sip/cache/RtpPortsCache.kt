package ru.stech.sip.cache

import java.util.concurrent.ConcurrentLinkedQueue

class RtpPortsCache(private val diapason: Pair<Int, Int>) {
    private val portsQueue = ConcurrentLinkedQueue<Int>()
    init {
        for (p in diapason.first .. diapason.second step 2) {
            returnPort(p)
        }
    }

    fun getFreePort(): Int {
        return portsQueue.poll() ?: throw NoFreeRtpPortException("No free rtp port")
    }

    fun returnPort(port: Int) {
        portsQueue.add(port)
    }
}