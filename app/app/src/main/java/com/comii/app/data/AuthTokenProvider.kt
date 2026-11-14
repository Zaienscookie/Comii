package com.comii.app.data

/** Process-wide bearer token used by the REST client and Coil image requests. */
object AuthTokenProvider {
    @Volatile
    var token: String = ""
}
