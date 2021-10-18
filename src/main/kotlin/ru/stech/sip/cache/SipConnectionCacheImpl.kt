package ru.stech.sip.cache

import ru.stech.sip.client.SipConnection
import ru.stech.sip.exceptions.SipException
import java.util.concurrent.ConcurrentHashMap

class SipConnectionCacheImpl : SipConnectionCache {
    companion object {
        private const val CONNECTION_NOT_FOUND = "Sip connection not found"
    }

    private val connections = ConcurrentHashMap<String, SipConnection>()

    override fun get(key: String): SipConnection {
        return connections[key] ?: throw SipException(CONNECTION_NOT_FOUND)
    }

    override fun isExist(key: String): Boolean {
        return connections.containsKey(key)
    }

    override fun put(key: String, connection: SipConnection) {
        connections[key] = connection
    }

    override fun remove(key: String): SipConnection {
        return connections.remove(key) ?: throw SipException(CONNECTION_NOT_FOUND)
    }

}