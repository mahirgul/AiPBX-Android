package com.mhrgl.aipbx

import com.mhrgl.aipbx.util.MediaUrls
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUrlsTest {

    private val base = "https://pbx.example.com"

    @Test
    fun relativePathsGoToTheServer() {
        assertEquals("https://pbx.example.com/chat/media/images/a.jpg", MediaUrls.resolve(base, "/chat/media/images/a.jpg"))
        assertEquals("https://pbx.example.com/chat/media/images/a.jpg", MediaUrls.resolve("$base/", "chat/media/images/a.jpg"))
    }

    @Test
    fun anAbsoluteAddressNeverLeavesTheServer() {
        // The session token travels with the request: another host must never get it.
        assertEquals("https://pbx.example.com/chat/media/files/x.pdf?v=2", MediaUrls.resolve(base, "https://elsewhere.example.net/chat/media/files/x.pdf?v=2"))
        assertEquals("https://pbx.example.com/chat/media/images/a.jpg", MediaUrls.resolve(base, "https://pbx.example.com/chat/media/images/a.jpg"))
    }
}
