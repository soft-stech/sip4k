package ru.stech.sip.cache

import ru.stech.sip.client.SipConnection

interface SipConnectionCache {
    operator fun get(key: String): SipConnection
    fun put(key: String, connection: SipConnection)
    fun remove(key: String): SipConnection
}