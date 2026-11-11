package com.mhrgl.aipbx.data

import com.google.gson.Gson
import com.mhrgl.aipbx.model.LoginResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

data class AuthSession(val serverUrl: String, val token: String, val generation: Long)

interface SessionRefreshStore {
    fun sessionSnapshot(): AuthSession?
    fun saveRefresh(expected: AuthSession, response: LoginResponse): Boolean
}

/** Refresh only the session that authorized this request, including across account changes. */
class SessionRefreshInterceptor(
    private val rawClient: OkHttpClient,
    private val storeProvider: () -> SessionRefreshStore?,
    private val onRejected: (AuthSession, String) -> Unit
) : Interceptor {
    companion object {
        private val refreshLock = Any()
    }

    private val gson = Gson()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val store = storeProvider()
        val started = store?.sessionSnapshot()
        if (store != null && request.header("Authorization") != null
            && (started == null || !belongsTo(request, started))) {
            throw IOException("Session changed before request")
        }
        val response = chain.proceed(request)
        if (response.code != 401 || store == null || started == null || !belongsTo(request, started)
            || request.url.encodedPath.substringAfterLast('/') in setOf("login.php", "refresh.php", "ping.php")) {
            return response
        }

        val retryToken = synchronized(refreshLock) {
            val current = store.sessionSnapshot()
            if (current == null || !sameLogin(started, current)) {
                null
            } else if (current.token != started.token) {
                current.token
            } else {
                refresh(store, current)
            }
        }
        if (retryToken == null) return response
        response.close()
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $retryToken").build())
    }

    private fun refresh(store: SessionRefreshStore, session: AuthSession): String? {
        val url = session.serverUrl.trimEnd('/') + "/api/mobile/refresh.php"
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${session.token}").build()
        try {
            rawClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    onRejected(session, "HTTP ${response.code} from refresh.php")
                    return null
                }
                if (!response.isSuccessful) return null
                val login = gson.fromJson(response.body?.string(), LoginResponse::class.java)
                if (login == null || !login.success || login.token.isNullOrEmpty()) return null
                if (store.saveRefresh(session, login)) return login.token
                val current = store.sessionSnapshot()
                return current?.token?.takeIf { sameLogin(session, current) && it != session.token }
            }
        } catch (_: Exception) {
            // Keep the original readable 401 if refresh fails or returns malformed JSON.
            return null
        }
    }

    private fun sameLogin(first: AuthSession, second: AuthSession): Boolean =
        first.generation == second.generation && first.serverUrl == second.serverUrl

    private fun belongsTo(request: Request, session: AuthSession): Boolean {
        val server = session.serverUrl.toHttpUrlOrNull() ?: return false
        val url = request.url
        return request.header("Authorization") == "Bearer ${session.token}"
            && url.scheme == server.scheme && url.host == server.host && url.port == server.port
            && url.encodedPath.startsWith(server.encodedPath.trimEnd('/') + "/")
    }
}
