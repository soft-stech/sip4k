package ru.stech.sip.cache

import org.slf4j.LoggerFactory
import ru.stech.sip.client.SipClientInboundHandler
import ru.stech.sip.client.SipConnection
import ru.stech.sip.exceptions.SipException
import java.util.concurrent.ConcurrentHashMap

class SipConnectionCacheImpl : SipConnectionCache {

    private val log = LoggerFactory.getLogger(SipConnectionCacheImpl::class.java)

    companion object {
        private const val CONNECTION_NOT_FOUND = "Sip connection not found"
    }

    private val connections = ConcurrentHashMap<String, SipConnection>()

    override fun get(key: String): SipConnection {
        if(log.isTraceEnabled) log.trace("get SipConnectionCache ${key}")
        return connections[key] ?: throw SipException(CONNECTION_NOT_FOUND)
    }

    override fun isExist(key: String): Boolean {
        if(log.isTraceEnabled) log.trace("checking isExist SipConnectionCache ${key}")
        return connections.containsKey(key)
    }

    override fun put(key: String, connection: SipConnection) {
        if(log.isTraceEnabled) log.trace("put SipConnectionCache ${key}")
        connections[key] = connection
    }

    override fun remove(key: String): SipConnection {
        if(log.isTraceEnabled) log.trace("remove SipConnectionCache ${key}")
        log.trace("remove SipConnectionCache ${key}")
        return connections.remove(key) ?: throw SipException(CONNECTION_NOT_FOUND)
    }

}