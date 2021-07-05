package ru.stech.sip.client

import gov.nist.javax.sip.message.SIPRequest
import gov.nist.javax.sip.message.SIPResponse

class SipConnection(
    private val sipId: String,
    private val sipPassword: String,
    private val sipRemoteHost: String,
    private val sipRemotePort: Int
) {

    fun optionsRequestEvent(request: SIPRequest) {

    }

    fun byeRequestEvent(request: SIPRequest) {

    }

    fun inviteRequestEvent(request: SIPRequest) {

    }

    fun registerResponseEvent(response: SIPResponse) {

    }

    fun inviteResponseEvent(response: SIPResponse) {

    }

    fun byeResponseEvent(response: SIPResponse) {

    }
}