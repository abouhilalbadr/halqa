package com.saiesh.tele.core.mac

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** A connection problem worded for the TV screen. */
class MacException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * The connection to the Mac's shared folder over SMB, the protocol macOS File Sharing speaks. One
 * connection is shared by the library scan, poster downloads and the player; it reconnects when the
 * Mac went to sleep or changed address. Every call blocks, so call it off the main thread.
 */
object MacShare {
    private lateinit var appContext: Context
    private val lock = Any()
    private var client: SMBClient? = null
    private var share: DiskShare? = null
    private var connectedTo: MacLogin? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Connects with [login] and checks the shared folder can be listed, without saving anything. */
    fun test(login: MacLogin) {
        val (testClient, testShare) = open(login)
        try {
            testShare.list("")
        } catch (e: SMBApiException) {
            // Signed in, but the computer can't read the folder. On a Mac, privacy rules keep File Sharing out of
            // external drives until it is given Full Disk Access; on Windows, the share's permissions say no.
            if (e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || e.status == NtStatus.STATUS_ACCESS_DENIED) {
                throw MacException(
                    "Signed in, but your computer won't open \"${login.share}\". On a Mac, turn on Full Disk Access in " +
                        "System Settings › Sharing › File Sharing ⓘ. On Windows, allow your account to read the folder " +
                        "in its Properties › Sharing › Advanced Sharing › Permissions.",
                    e,
                )
            }
            throw describe(e, login)
        } catch (e: Exception) {
            throw describe(e, login)
        } finally {
            runCatching { testClient.close() }
        }
    }

    /**
     * Runs [block] with the saved Mac's shared folder. A dropped connection (the Mac slept, or the
     * connection timed out) is reopened once; a failure to connect at all is reported straight away.
     */
    fun <T> use(block: (DiskShare) -> T): T {
        val share = current()
        try {
            return block(share)
        } catch (e: Exception) {
            if (e is SMBApiException && !isConnectionStatus(e.status)) throw describe(e, connectedTo)
            disconnect()
            return try {
                block(current())
            } catch (retry: Exception) {
                throw describe(retry, connectedTo)
            }
        }
    }

    fun openForReading(path: String): File =
        use { share ->
            share.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        }

    fun disconnect() {
        synchronized(lock) {
            runCatching { client?.close() }
            client = null
            share = null
            connectedTo = null
        }
    }

    private fun current(): DiskShare =
        synchronized(lock) {
            val login = MacSettings.login.value ?: throw MacException("No computer is set up")
            val existing = share
            if (existing != null && existing.isConnected && connectedTo == login) return existing
            disconnect()
            val (newClient, newShare) =
                try {
                    open(login)
                } catch (e: MacException) {
                    // The Mac may have a new address; look it up by its Bonjour name and try once more.
                    val host = MacFinder.findHost(appContext, login.name, FIND_TIMEOUT_MS)
                    if (host == null || host == login.host) throw e
                    MacSettings.updateHost(host)
                    open(login.copy(host = host))
                }
            client = newClient
            share = newShare
            connectedTo = MacSettings.login.value
            newShare
        }

    private fun open(login: MacLogin): Pair<SMBClient, DiskShare> {
        val newClient = SMBClient(config)
        try {
            val connection = newClient.connect(login.host)
            val session = connection.authenticate(AuthenticationContext(login.user, login.password.toCharArray(), null))
            val diskShare =
                session.connectShare(login.share) as? DiskShare
                    ?: throw MacException("\"${login.share}\" is not a shared folder")
            return newClient to diskShare
        } catch (e: Exception) {
            runCatching { newClient.close() }
            throw describe(e, login)
        }
    }

    private fun isConnectionStatus(status: NtStatus): Boolean =
        status == NtStatus.STATUS_NETWORK_NAME_DELETED ||
            status == NtStatus.STATUS_USER_SESSION_DELETED ||
            status == NtStatus.STATUS_NETWORK_SESSION_EXPIRED

    private fun describe(
        error: Throwable,
        login: MacLogin?,
    ): MacException {
        if (error is MacException) return error
        val shareName = login?.share.orEmpty()
        var cause: Throwable? = error
        while (cause != null) {
            when {
                cause is SMBApiException && cause.status == NtStatus.STATUS_LOGON_FAILURE ->
                    return MacException(
                        "Your computer turned down this login. Check the user name and password. On a Mac, also turn on " +
                            "File Sharing › Options › Windows File Sharing for your account. On Windows with a Microsoft " +
                            "account, sign in with its email address and password.",
                        error,
                    )
                cause is SMBApiException && cause.status == NtStatus.STATUS_BAD_NETWORK_NAME ->
                    return MacException("No shared folder called \"$shareName\" on this computer", error)
                cause is SMBApiException && cause.status == NtStatus.STATUS_ACCESS_DENIED ->
                    return MacException("This user can't open \"$shareName\"", error)
                cause is ConnectException || cause is NoRouteToHostException || cause is SocketTimeoutException ->
                    return MacException("Can't reach your computer. Is it awake with file sharing on?", error)
            }
            cause = cause.cause
        }
        return MacException(error.message ?: "Can't reach your computer", error)
    }

    private val config: SmbConfig =
        SmbConfig
            .builder()
            .withSecurityProvider(BCSecurityProvider())
            .withMultiProtocolNegotiate(true)
            .withDfsEnabled(false)
            // No socket idle limit: when one fires, smbj closes the connection and waits a full request timeout
            // for a logoff reply that never arrives, which stalled playback started in that window. A Mac that
            // went to sleep is caught by the request timeout instead, and use() reconnects.
            .withTimeout(15, TimeUnit.SECONDS)
            .withReadBufferSize(READ_BUFFER_SIZE)
            .build()

    const val READ_BUFFER_SIZE = 1 shl 20
    private const val FIND_TIMEOUT_MS = 4_000L
}
