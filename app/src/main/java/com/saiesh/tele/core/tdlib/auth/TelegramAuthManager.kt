package com.saiesh.tele.core.tdlib.auth

import android.content.Context
import android.os.Build
import android.util.Log
import com.saiesh.tele.BuildConfig
import com.saiesh.tele.core.tdlib.TdLibClient
import com.saiesh.tele.data.store.ApiCredentialsStore
import com.saiesh.tele.domain.model.AuthStep
import com.saiesh.tele.domain.model.AuthUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class TelegramAuthManager(
    private val context: Context,
) {
    private companion object {
        const val TAG = "TelegramAuthManager"
    }

    private val _uiState = MutableStateFlow(AuthUiState(isLoading = true))
    val uiState: StateFlow<AuthUiState> = _uiState

    private val client = TdLibClient.client
    private val credentialsStore = ApiCredentialsStore(context)
    private val parametersSent = AtomicBoolean(false)

    private var updateHandlerRef: ((TdApi.Object?) -> Unit)? = null
    private var errorHandlerRef: ((Throwable?) -> Unit)? = null

    init {
        val updateHandler: (TdApi.Object?) -> Unit = ::handleUpdate
        val errorHandler: (Throwable?) -> Unit = ::handleError
        updateHandlerRef = updateHandler
        errorHandlerRef = errorHandler
        TdLibClient.addUpdateHandler(updateHandler)
        TdLibClient.addErrorHandler(errorHandler)
        client.send(TdApi.GetAuthorizationState()) { result ->
            when (result) {
                is TdApi.AuthorizationState -> handleAuthState(result)
                else -> handleResult(result)
            }
        }
    }

    fun submitPhone(phone: String) = submitAction(TdApi.SetAuthenticationPhoneNumber(phone, null))

    fun submitCode(code: String) = submitAction(TdApi.CheckAuthenticationCode(code))

    fun submitPassword(password: String) = submitAction(TdApi.CheckAuthenticationPassword(password))

    /** Signs this TV out of Telegram; TDLib wipes its local data and reports [AuthStep.SignedOut] when done. */
    fun logOut() {
        _uiState.update { it.copy(isLoading = true, message = null) }
        client.send(TdApi.LogOut()) { result -> handleResult(result) }
    }

    private fun loadAccountName() {
        client.send(TdApi.GetMe()) { result ->
            if (result is TdApi.User) {
                val name = listOf(result.firstName, result.lastName).filter { it.isNotBlank() }.joinToString(" ")
                _uiState.update { it.copy(accountName = name.ifEmpty { null }) }
            }
        }
    }

    private fun requestQrCode() {
        _uiState.update { it.copy(isLoading = true, message = null) }
        client.send(TdApi.RequestQrCodeAuthentication(LongArray(0))) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "QR login error: ${result.code} ${result.message}")
                if (rejectKeysIfInvalid(result)) return@send
                _uiState.update {
                    it.copy(step = AuthStep.EnterPhone, message = result.message, isLoading = false)
                }
            }
        }
    }

    private fun submitAction(request: TdApi.Function<*>) {
        _uiState.update { it.copy(isLoading = true, message = null) }
        client.send(request) { result -> handleResult(result) }
    }

    fun submitApiKeys(
        apiId: String,
        apiHash: String,
    ) {
        credentialsStore.save(apiId, apiHash)
        _uiState.update { it.copy(apiId = apiId, apiHash = apiHash, isLoading = true, message = null) }
        client.send(TdApi.GetAuthorizationState()) { result ->
            when (result) {
                is TdApi.AuthorizationState -> handleAuthState(result)
                else -> handleResult(result)
            }
        }
    }

    private fun handleUpdate(update: TdApi.Object?) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(update.authorizationState)
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState?) {
        Log.d(TAG, "Auth state: ${state?.javaClass?.simpleName}")
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                setTdlibParameters()
            }

            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                requestQrCode()
            }

            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                // Telegram refreshes the link every ~30 s and sends this state again.
                _uiState.update { it.copy(step = AuthStep.ScanQr, qrLink = state.link, isLoading = false) }
            }

            is TdApi.AuthorizationStateWaitCode -> {
                _uiState.update { it.copy(step = AuthStep.EnterCode, isLoading = false) }
            }

            is TdApi.AuthorizationStateWaitPassword -> {
                _uiState.update { it.copy(step = AuthStep.EnterPassword, isLoading = false) }
            }

            is TdApi.AuthorizationStateReady -> {
                _uiState.update { it.copy(step = AuthStep.Authorized, isLoading = false) }
                loadAccountName()
            }

            is TdApi.AuthorizationStateLoggingOut -> {
                _uiState.update { it.copy(message = "Logging out…", isLoading = true) }
            }

            is TdApi.AuthorizationStateClosing -> {
                _uiState.update { it.copy(message = "Closing session…", isLoading = true) }
            }

            is TdApi.AuthorizationStateClosed -> {
                _uiState.update { it.copy(step = AuthStep.SignedOut, message = null, isLoading = false) }
            }

            else -> {
                Unit
            }
        }
    }

    private fun handleResult(result: TdApi.Object?) {
        if (result is TdApi.Error) {
            Log.e(TAG, "Auth error: ${result.code} ${result.message}")
            if (rejectKeysIfInvalid(result)) return
            _uiState.update { state ->
                state.copy(message = result.message, isLoading = false)
            }
        }
    }

    private fun handleError(t: Throwable?) {
        Log.e(TAG, "TDLib error", t)
        _uiState.update { state ->
            state.copy(message = t?.message ?: "TDLib error", isLoading = false)
        }
    }

    private fun setTdlibParameters() {
        val storedApiId = credentialsStore.getApiId()
        val storedApiHash = credentialsStore.getApiHash()
        val buildApiId = BuildConfig.TELEGRAM_API_ID.toIntOrNull() ?: 0
        val buildApiHash = BuildConfig.TELEGRAM_API_HASH
        val apiId =
            when {
                !storedApiId.isNullOrBlank() -> storedApiId.toIntOrNull() ?: 0
                else -> buildApiId
            }
        val apiHash = if (!storedApiHash.isNullOrBlank()) storedApiHash else buildApiHash
        if (apiId == 0 || apiHash.isBlank()) {
            _uiState.update {
                it.copy(
                    step = AuthStep.EnterApiKeys,
                    apiId = storedApiId.orEmpty(),
                    apiHash = storedApiHash.orEmpty(),
                    message = "Enter your Telegram API ID and Hash",
                    isLoading = false,
                )
            }
            return
        }
        // Both the initial state query and the state update arrive here; TDLib accepts parameters once.
        if (!parametersSent.compareAndSet(false, true)) return
        val databaseDir = File(context.filesDir, "tdlib").absolutePath
        val filesDir = File(context.filesDir, "tdlib_files").absolutePath

        val parameters =
            TdApi.SetTdlibParameters(
                false,
                databaseDir,
                filesDir,
                byteArrayOf(),
                true,
                true,
                true,
                false,
                apiId,
                apiHash,
                "en",
                "Android TV",
                Build.VERSION.RELEASE ?: "Android",
                BuildConfig.VERSION_NAME,
            )
        client.send(parameters) { result ->
            handleResult(result)
        }
    }

    /** Whether an API ID and hash are available, typed in on this TV or built into the app. */
    fun hasKeys(): Boolean =
        (!credentialsStore.getApiId().isNullOrBlank() && !credentialsStore.getApiHash().isNullOrBlank()) ||
            ((BuildConfig.TELEGRAM_API_ID.toIntOrNull() ?: 0) != 0 && BuildConfig.TELEGRAM_API_HASH.isNotBlank())

    /**
     * Telegram answers a wrong API ID or hash with API_ID_INVALID on the first request that reaches it. TDLib
     * accepts its parameters only once per process, so the stored keys are forgotten and the app is restarted
     * by whoever shows [AuthUiState.keysRejected].
     */
    private fun rejectKeysIfInvalid(error: TdApi.Error): Boolean {
        if (!error.message.contains("API_ID", ignoreCase = true)) return false
        credentialsStore.clear()
        _uiState.update { it.copy(step = AuthStep.EnterApiKeys, keysRejected = true, isLoading = false) }
        return true
    }

    fun cleanup() {
        updateHandlerRef?.let { TdLibClient.removeUpdateHandler(it) }
        errorHandlerRef?.let { TdLibClient.removeErrorHandler(it) }
        updateHandlerRef = null
        errorHandlerRef = null
    }
}
