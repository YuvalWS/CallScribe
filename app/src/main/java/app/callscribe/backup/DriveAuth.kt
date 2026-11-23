package app.callscribe.backup

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google authorization for the `drive.file` scope via Play services' AuthorizationClient.
 * No client ID in the app: Google matches the Android OAuth client registered for this package + signing SHA-1
 * (see README → Google Drive backup).
 */
class DriveAuth(context: Context) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    }

    private val client = Identity.getAuthorizationClient(context.applicationContext)
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
    private val lock = Mutex()
    private var cached: String? = null

    /** May need a UI step: check [AuthorizationResult.hasResolution] and launch its pendingIntent. */
    suspend fun authorize(): AuthorizationResult = try {
        client.authorize(request).await()
    } catch (e: ApiException) {
        throw Exception(explain(e), e)
    }

    fun resultFromIntent(data: Intent?): AuthorizationResult = try {
        client.getAuthorizationResultFromIntent(data)
    } catch (e: ApiException) {
        throw Exception(explain(e), e)
    }

    fun remember(result: AuthorizationResult) {
        cached = result.accessToken
    }

    /** For background work: never shows UI; throws [DriveAuthRequiredException] when the user is needed. */
    suspend fun token(forceRefresh: Boolean): String = lock.withLock {
        if (forceRefresh) cached?.let { clear(it) }
        cached?.takeIf { !forceRefresh }?.let { return it }
        val r = authorize()
        if (r.hasResolution()) throw DriveAuthRequiredException()
        r.accessToken?.also { cached = it } ?: throw DriveAuthRequiredException()
    }

    /** Forgets the token on this phone. Access itself is revoked by the user at myaccount.google.com → Security. */
    suspend fun disconnect() = lock.withLock {
        cached?.let { clear(it) }
        cached = null
    }

    private suspend fun clear(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
        cached = null
    }

    private fun explain(e: ApiException) = when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Google rejected this app build: no Android OAuth client for package + signing SHA-1 (see README → Google Drive backup)"
        CommonStatusCodes.NETWORK_ERROR -> "No network"
        CommonStatusCodes.CANCELED -> "Cancelled"
        else -> "Google sign-in failed (${CommonStatusCodes.getStatusCodeString(e.statusCode)})"
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}
