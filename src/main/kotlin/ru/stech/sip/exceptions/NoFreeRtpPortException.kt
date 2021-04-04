package ru.stech.sip.exceptions

import java.lang.RuntimeException

class NoFreeRtpPortException(message: String): RuntimeException(message)
