package com.comii.app.data

/** Process-wide bearer token used by the REST client and Coil image requests. */
private fun String.cleanToken(): String = filter { it.code in 0x21..0x7E }.trim()

object AuthTokenProvider {
    @Volatile
    var token: String = ""
    @Volatile
    var accessToken: String = ""
        set(value) { field = value.cleanToken() }
}
