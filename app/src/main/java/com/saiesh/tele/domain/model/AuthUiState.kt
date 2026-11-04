package com.saiesh.tele.domain.model

data class AuthUiState(
    val apiId: String = "",
    val apiHash: String = "",
    val phone: String = "",
    val code: String = "",
    val password: String = "",
    val qrLink: String? = null,
    val step: AuthStep = AuthStep.Loading,
    val message: String? = null,
    val isLoading: Boolean = false,
    val accountName: String? = null,
)
