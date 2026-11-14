package com.comii.app.data

import kotlinx.serialization.Serializable

@Serializable
data class AuthUser(
    val id: Int = 0,
    val username: String = "",
    val role: String = "viewer",
    val enabled: Boolean = true,
)

@Serializable
data class AuthGrant(
    val path_prefix: String = "",
    val view: Boolean = false,
    val download: Boolean = false,
    val upload: Boolean = false,
    val create: Boolean = false,
    val delete: Boolean = false,
    val move: Boolean = false,
    val manage: Boolean = false,
)

@Serializable
data class AuthResponse(
    val token: String = "",
    val expires_at: Double = 0.0,
    val user: AuthUser = AuthUser(),
    val grants: List<AuthGrant> = emptyList(),
    val permissions: List<String> = emptyList(),
)

@Serializable
data class AuthMe(
    val user: AuthUser = AuthUser(),
    val grants: List<AuthGrant> = emptyList(),
    val permissions: List<String> = emptyList(),
)
