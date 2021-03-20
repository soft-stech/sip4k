package ru.stech.sip.exceptions

import java.lang.RuntimeException

class SipClientNotAvailableException(message: String): RuntimeException(message) {
}