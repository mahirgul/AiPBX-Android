package com.mhrgl.aipbx

import com.google.gson.Gson
import com.mhrgl.aipbx.model.ChatCallResponse
import com.mhrgl.aipbx.model.FeaturesResponse
import com.mhrgl.aipbx.model.parseChatCallMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatCallModelTest {

    private val gson = Gson()

    @Test
    fun featuresChatCalls_parsesTrueFalseAndDefault() {
        val enabledJson = """
            {
                "success": true,
                "features": {
                    "extension": "1001",
                    "dnd_enabled": false,
                    "chat_calls": true
                }
            }
        """.trimIndent()
        val enabled = gson.fromJson(enabledJson, FeaturesResponse::class.java)
        assertTrue(enabled.features!!.chatCalls)

        val disabledJson = """
            {
                "success": true,
                "features": {
                    "extension": "1001",
                    "dnd_enabled": false,
                    "chat_calls": false
                }
            }
        """.trimIndent()
        val disabled = gson.fromJson(disabledJson, FeaturesResponse::class.java)
        assertFalse(disabled.features!!.chatCalls)

        val omittedJson = """
            {
                "success": true,
                "features": {
                    "extension": "1001",
                    "dnd_enabled": false
                }
            }
        """.trimIndent()
        val omitted = gson.fromJson(omittedJson, FeaturesResponse::class.java)
        assertFalse(omitted.features!!.chatCalls)
    }

    @Test
    fun chatCallResponse_parsesSuccessAndError() {
        val createdJson = """
            {
                "success": true,
                "created": true,
                "call": {
                    "id": 34,
                    "conversation_id": 12,
                    "video": true,
                    "started_by": "1001",
                    "dial": "cc123456789012"
                }
            }
        """.trimIndent()
        val created = gson.fromJson(createdJson, ChatCallResponse::class.java)
        assertTrue(created.success)
        assertTrue(created.created)
        assertNull(created.error)
        assertNotNull(created.call)
        assertEquals(34L, created.call!!.id)
        assertEquals(12, created.call!!.conversationId)
        assertTrue(created.call!!.video)
        assertEquals("1001", created.call!!.startedBy)
        assertEquals("cc123456789012", created.call!!.dial)

        val goneJson = """
            {
                "success": false,
                "error": "This call has ended."
            }
        """.trimIndent()
        val gone = gson.fromJson(goneJson, ChatCallResponse::class.java)
        assertFalse(gone.success)
        assertFalse(gone.created)
        assertNull(gone.call)
        assertEquals("This call has ended.", gone.error)
    }

    @Test
    fun parseChatCallMeta_decodesStartedAndEnded() {
        val startedVideo = parseChatCallMeta("""{"call_id":"34","video":"1","actor":"1001"}""")
        assertNotNull(startedVideo)
        assertEquals(34L, startedVideo!!.callId)
        assertTrue(startedVideo.video)
        assertEquals("1001", startedVideo.actor)
        assertNull(startedVideo.duration)
        assertNull(startedVideo.parties)

        val startedVoice = parseChatCallMeta("""{"call_id":"35","video":"0","actor":"1002"}""")
        assertNotNull(startedVoice)
        assertEquals(35L, startedVoice!!.callId)
        assertFalse(startedVoice.video)
        assertEquals("1002", startedVoice.actor)

        val ended = parseChatCallMeta("""{"call_id":"34","video":"1","actor":"1001","duration":"142","parties":"3"}""")
        assertNotNull(ended)
        assertEquals(34L, ended!!.callId)
        assertTrue(ended.video)
        assertEquals("1001", ended.actor)
        assertEquals(142, ended.duration)
        assertEquals(3, ended.parties)

        assertNull(parseChatCallMeta(null))
        assertNull(parseChatCallMeta(""))
        assertNull(parseChatCallMeta("not-json"))
        assertNull(parseChatCallMeta("""{"video":"1","actor":"1001"}"""))
    }

    @Test
    fun phoneEngineMakeCall_preservesChatCallRoomTarget() {
        var dir = File(System.getProperty("user.dir") ?: ".")
        if (dir.name == "app") dir = dir.parentFile ?: dir
        val phoneEngine = File(dir, "app/src/main/assets/phone_engine.html")
        assertTrue("phone_engine.html must exist at ${phoneEngine.absolutePath}", phoneEngine.exists())

        val html = phoneEngine.readText()
        assertTrue(
            "makeCall must allow /^cc[0-9]+$/ so cc123456789012 is not stripped",
            html.contains("/^cc[0-9]+\$/.test(rawTarget)")
        )
    }
}
