package com.mhrgl.aipbx

import com.mhrgl.aipbx.ui.isOwnBrowserSignIn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthDeepLinkTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test fun signInThisAppStartedIsTrusted() {
        assertTrue(isOwnBrowserSignIn(now - 2 * minute, now))
    }

    @Test fun linkWithoutAStartedSignInIsNotTrusted() {
        assertFalse(isOwnBrowserSignIn(0L, now))
    }

    @Test fun anOldStartSaysNothingAboutThisLink() {
        assertFalse(isOwnBrowserSignIn(now - 11 * minute, now))
    }

    @Test fun aStartInTheFutureIsNotTrusted() {
        assertFalse(isOwnBrowserSignIn(now + minute, now))
    }
}
