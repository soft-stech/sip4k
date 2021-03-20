package ru.stech.sip.cache

import ru.stech.sip.UserSession
import java.util.concurrent.ConcurrentHashMap

class SipSessionCacheImpl: SipSessionCache {
    private val sessions = ConcurrentHashMap<String, UserSession>()
    var rtpPortSequence = 40000

    override fun get(key: String): UserSession? {
        return sessions[key]
    }

    override fun put(key: String, session: UserSession) {
        sessions[key] = session
    }

    override fun remove(key: String): UserSession? {
        return sessions.remove(key)
    }

    override fun newRtpPort(): Int {
        rtpPortSequence += 2
        return rtpPortSequence
    }

}