package com.mhrgl.aipbx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keep unbound browser callbacks behind the same confirmation as invitation links. */
class AuthDeepLinkTest {
    @Test fun browserCallbacksRequireConfirmationWithoutTimestampTrust() {
        val root = File(System.getProperty("user.dir")!!).let { if (it.name == "app") it.parentFile else it }
        val source = File(root, "app/src/main/java/com/mhrgl/aipbx/ui/LoginActivity.kt").readText()
        val callback = source.substringAfter("\"auth\" -> {").substringBefore("private fun confirmServerThen")
        assertTrue(callback.contains("confirmServerThen(serverUrl)"))
        assertFalse(callback.contains("browserSignInStartedAt"))
        assertFalse(callback.contains("performQrLogin(serverUrl, code)"))
        assertFalse(callback.contains("getQueryParameter(\"error\")"))
    }
}
