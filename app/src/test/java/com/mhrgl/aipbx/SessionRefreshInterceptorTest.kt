package com.mhrgl.aipbx

import com.mhrgl.aipbx.data.AuthSession
import com.mhrgl.aipbx.data.SessionRefreshInterceptor
import com.mhrgl.aipbx.data.SessionRefreshStore
import com.mhrgl.aipbx.model.LoginResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.io.IOException

class SessionRefreshInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private lateinit var store: MemoryStore
    private var rejected = 0

    private class MemoryStore(var session: AuthSession?) : SessionRefreshStore {
        @Synchronized override fun sessionSnapshot() = session
        @Synchronized override fun saveRefresh(expected: AuthSession, response: LoginResponse): Boolean {
            if (session != expected) return false
            session = expected.copy(token = response.token!!)
            return true
        }
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
        store = MemoryStore(AuthSession(server.url("/").toString().trimEnd('/'), "old-token", 1))
    }

    @After fun tearDown() {
        server.shutdown()
        other.shutdown()
    }

    private fun client(onResponse: (() -> Unit)? = null): OkHttpClient {
        val raw = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        return raw.newBuilder()
            .addInterceptor(SessionRefreshInterceptor(raw, { store }) { expected, _ ->
                if (store.sessionSnapshot() == expected) rejected++
            })
            .addInterceptor { chain ->
                chain.proceed(chain.request()).also { onResponse?.invoke() }
            }.build()
    }

    private fun request(token: String = "old-token") = Request.Builder().url(server.url("/resource"))
        .header("Authorization", "Bearer $token").build()

    private fun success(token: String = "refreshed-token") = MockResponse().setBody(
        """{"success":true,"token":"$token"}"""
    )

    @Test fun successfulRefreshRetriesWithTheNewToken() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        server.enqueue(success())
        server.enqueue(MockResponse().setBody("ok"))
        client().newCall(request()).execute().use { assertEquals("ok", it.body!!.string()) }
        assertEquals("Bearer old-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("/api/mobile/refresh.php", server.takeRequest().path)
        assertEquals("Bearer refreshed-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun changedServerNeverReceivesOrLeaksTheNewSessionToken() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        val client = client {
            store.session = AuthSession(other.url("/").toString().trimEnd('/'), "other-token", 2)
        }
        client.newCall(request()).execute().use { assertEquals(401, it.code) }
        assertEquals(1, server.requestCount)
        assertEquals(0, other.requestCount)
        assertEquals("other-token", store.session!!.token)
    }

    @Test fun logoutDuringTheRequestDoesNotReviveTheSession() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        client { store.session = null }.newCall(request()).execute().use {
            assertEquals("expired", it.body!!.string())
        }
        assertEquals(1, server.requestCount)
        assertNull(store.session)
    }

    @Test fun anotherLoginOnTheSameServerDoesNotReplaceOrRejectThatLogin() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        client { store.session = store.session!!.copy(token = "other-account", generation = 2) }
            .newCall(request()).execute().close()
        assertEquals(1, server.requestCount)
        assertEquals(0, rejected)
    }

    @Test fun failedRefreshLeavesTheOriginalResponseReadable() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("original error"))
        server.enqueue(MockResponse().setResponseCode(503))
        client().newCall(request()).execute().use { assertEquals("original error", it.body!!.string()) }
        assertEquals("old-token", store.session!!.token)
        assertEquals(0, rejected)
    }

    @Test fun invalidRefreshJsonLeavesTheOriginalResponseReadable() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("original error"))
        server.enqueue(MockResponse().setBody("not JSON"))
        client().newCall(request()).execute().use { assertEquals("original error", it.body!!.string()) }
    }

    @Test fun permanentRejectionIsReportedForTheOriginalSession() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        server.enqueue(MockResponse().setResponseCode(403))
        client().newCall(request()).execute().use { assertEquals("expired", it.body!!.string()) }
        assertEquals(1, rejected)
    }

    @Test fun foreignOriginCannotReceiveTheSessionToken() {
        store.session = AuthSession(other.url("/").toString().trimEnd('/'), "old-token", 1)
        assertThrows(IOException::class.java) { client().newCall(request()).execute() }
        assertEquals(0, server.requestCount)
        assertEquals(0, other.requestCount)
    }

    @Test fun requestCreatedBeforeLogoutCannotBeSentAfterLogout() {
        store.session = null
        assertThrows(IOException::class.java) { client().newCall(request()).execute() }
        assertEquals(0, server.requestCount)
    }

    @Test fun requestWithAnotherAccountsTokenCannotBeSent() {
        assertThrows(IOException::class.java) { client().newCall(request("another-account")).execute() }
        assertEquals(0, server.requestCount)
    }

    @Test fun refreshCompletedByAnotherRequestIsReusedWithinTheSameLogin() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("ok"))
        client { store.session = store.session!!.copy(token = "already-refreshed") }
            .newCall(request()).execute().use { assertEquals("ok", it.body!!.string()) }
        server.takeRequest()
        assertEquals("Bearer already-refreshed", server.takeRequest().getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    @Test fun loginChangedWhileRefreshWasInFlightCannotBeOverwritten() {
        val newer = store.session!!.copy(token = "new-login", generation = 2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/resource") return MockResponse().setResponseCode(401).setBody("expired")
                store.session = newer
                return success()
            }
        }
        client().newCall(request()).execute().use { assertEquals("expired", it.body!!.string()) }
        assertEquals(newer, store.session)
        assertEquals(2, server.requestCount)
    }

    @Test fun rejectionAfterAccountChangeDoesNotSignOutTheNewLogin() {
        val newer = store.session!!.copy(token = "new-login", generation = 2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/resource") return MockResponse().setResponseCode(401)
                store.session = newer
                return MockResponse().setResponseCode(403)
            }
        }
        client().newCall(request()).execute().close()
        assertEquals(newer, store.session)
        assertEquals(0, rejected)
    }

    @Test fun capturedLogoutCleanupNeverBorrowsTheCurrentSessionsCredentials() {
        store.session = AuthSession(other.url("/").toString().trimEnd('/'), "new-login", 2)
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        val raw = OkHttpClient()
        val cleanup = raw.newBuilder().addInterceptor(SessionRefreshInterceptor(raw, { null }) { _, _ ->
            fail("Cleanup must not refresh or reject the current session")
        }).build()
        cleanup.newCall(request()).execute().use { assertEquals("expired", it.body!!.string()) }
        assertEquals("Bearer old-token", server.takeRequest().getHeader("Authorization"))
        assertEquals(0, other.requestCount)
        assertEquals("new-login", store.session!!.token)
    }
}
