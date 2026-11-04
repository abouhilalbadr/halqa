package com.saiesh.tele.domain.model

enum class AuthStep {
    Loading,
    EnterApiKeys,
    ScanQr,
    EnterPhone,
    EnterCode,
    EnterPassword,
    Authorized,
    SignedOut,
}
