package ru.stech.sip.cache

class Cache {
    companion object {
        val sipControlConnection: SipControlConnection = SipControlConnection()
        val instance: SipConnectionCache = SipConnectionCacheImpl()
    }
}