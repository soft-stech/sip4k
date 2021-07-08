package ru.stech.sip

import javax.sip.SipFactory

class Factories {
    companion object {
        private val sipFactory = SipFactory.getInstance()
        val messageFactory = sipFactory.createMessageFactory()
        val headerFactory = sipFactory.createHeaderFactory()
        val addressFactory = sipFactory.createAddressFactory()
    }
}