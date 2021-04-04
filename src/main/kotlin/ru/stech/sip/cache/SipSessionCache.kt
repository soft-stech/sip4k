package ru.stech.sip.cache

import ru.stech.sip.UserSession

interface SipSessionCache {
    fun get(key: String): UserSession?
    fun put(key: String, session: UserSession)
    fun remove(key: String): UserSession?
}