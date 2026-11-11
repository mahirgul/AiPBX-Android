package com.mhrgl.aipbx.model

import com.google.gson.annotations.SerializedName

data class ServerInfo(
    @SerializedName("success") val success: Boolean,
    @SerializedName("service") val service: String?,
    @SerializedName("version") val version: String?,
    @SerializedName("site_title") val siteTitle: String?,
    @SerializedName("brand_title") val brandTitle: String?,
    @SerializedName("brand_sub") val brandSub: String?,
    @SerializedName("server_time") val serverTime: Long?
)

data class PushConfig(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("provider") val provider: String = "none",
    @SerializedName("fcm_project_id") val fcmProjectId: String? = null,
    @SerializedName("fcm_app_id") val fcmAppId: String? = null,
    @SerializedName("fcm_api_key") val fcmApiKey: String? = null,
    @SerializedName("fcm_sender_id") val fcmSenderId: String? = null
)

data class LoginResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("error") val error: String?,
    @SerializedName("token") val token: String?,
    @SerializedName("user") val user: UserProfile?,
    @SerializedName("sip") val sip: SipCredentials?,
    @SerializedName("push_config") val pushConfig: PushConfig? = null,
    /** Two-step verification is on for the account: a 6-digit code is needed with the password. */
    @SerializedName("otp_required") val otpRequired: Boolean = false
)

/** The server accepted the password but asks for the two-step verification code (or the code is wrong). */
class OtpRequiredException(message: String) : Exception(message)

/** The server rejected the session for good (401/403: expired, password changed, account inactive). */
class SessionExpiredException(message: String) : Exception(message)

data class UserProfile(
    @SerializedName("id") val id: Int,
    @SerializedName("username") val username: String,
    @SerializedName("full_name") val fullName: String?,
    @SerializedName("extension") val extension: String,
    @SerializedName("role") val role: String?
)

data class SipCredentials(
    @SerializedName("extension") val extension: String,
    @SerializedName("sip_username") val sipUsername: String,
    @SerializedName("sip_password") val sipPassword: String,
    @SerializedName("domain") val domain: String,
    @SerializedName("ws_url") val wsUrl: String,
    @SerializedName("turn") val turn: TurnConfig?
)

data class TurnConfig(
    @SerializedName("username") val username: String?,
    @SerializedName("credential") val credential: String?,
    @SerializedName("urls") val urls: List<String>?
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

enum class CallStatus {
    IDLE,
    CONNECTING,
    RINGING_OUTGOING,
    RINGING_INCOMING,
    ACTIVE,
    ON_HOLD,
    ENDED
}

/** One voicemail message of the user's own mailbox (api/mobile/voicemail.php). */
data class VoicemailMessage(
    @SerializedName("id") val id: String,
    @SerializedName("new") val isNew: Boolean,
    @SerializedName("caller_name") val callerName: String?,
    @SerializedName("caller_number") val callerNumber: String?,
    @SerializedName("time") val time: String?,
    @SerializedName("duration") val duration: Int,
    @SerializedName("has_audio") val hasAudio: Boolean,
    // Voicemail as text (server 1.24+), empty without.
    @SerializedName("summary") val summary: String? = null,
    @SerializedName("transcript") val transcript: String? = null
)

data class VoicemailResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("messages") val messages: List<VoicemailMessage>?,
    @SerializedName("new_count") val newCount: Int,
    @SerializedName("id") val id: String?,
    @SerializedName("error") val error: String?
)

/** One fax, received or sent (api/mobile/fax.php). Unused side's fields are null. */
data class FaxMessage(
    @SerializedName("id") val id: String,
    @SerializedName("did_extension") val didExtension: String? = null,
    @SerializedName("caller_id") val callerId: String? = null,
    @SerializedName("sender_extension") val senderExtension: String? = null,
    @SerializedName("destination_number") val destinationNumber: String? = null,
    @SerializedName("pages") val pages: Int = 0,
    @SerializedName("file_size") val fileSize: Long = 0,
    @SerializedName("status") val status: String? = null,
    @SerializedName("error_message") val errorMessage: String? = null,
    @SerializedName("is_read") val isRead: Boolean = false,
    @SerializedName("received_at") val receivedAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("completed_at") val completedAt: String? = null,
)

data class FaxListResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("faxes") val faxes: List<FaxMessage>? = null,
    @SerializedName("total_count") val totalCount: Int = 0,
    @SerializedName("total_pages") val totalPages: Int = 0,
    @SerializedName("error") val error: String? = null,
)

/** A saved cover-page template from the fax config. */
data class FaxTemplate(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("company") val company: String? = null,
    @SerializedName("notice") val notice: String? = null,
    @SerializedName("logo") val logo: String? = null,
)

data class FaxConfigResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("sendable_numbers") val sendableNumbers: List<String>? = null,
    @SerializedName("cover_mode") val coverMode: String? = null,
    @SerializedName("cover_company") val coverCompany: String? = null,
    @SerializedName("cover_notice") val coverNotice: String? = null,
    @SerializedName("cover_logo") val coverLogo: String? = null,
    @SerializedName("templates") val templates: List<FaxTemplate>? = null,
    @SerializedName("error") val error: String? = null,
)

/** Result of sending a fax (or of resending one): the new fax id and its page count. */
data class FaxSendResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("fax_id") val faxId: String? = null,
    @SerializedName("pages") val pages: Int? = null,
    @SerializedName("error") val error: String? = null,
)

/** Result of a mark_read / delete fax action. */
data class FaxActionResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("fax_id") val faxId: String? = null,
    @SerializedName("error") val error: String? = null,
)

/** One of the user's SMS numbers (api/mobile/sms.php config). */
data class SmsNumber(
    @SerializedName("id") val id: Int,
    @SerializedName("number") val number: String? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("provider") val provider: String? = null,
)

data class SmsConfigResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("numbers") val numbers: List<SmsNumber>? = null,
    /** True when the server has a GIPHY key: the GIF button searches; otherwise it picks a GIF file. */
    @SerializedName("gif_search") val gifSearch: Boolean = false,
    @SerializedName("error") val error: String? = null,
)

/** One SMS conversation: one of our numbers with one peer (api/mobile/sms.php threads). */
data class SmsThread(
    @SerializedName("number_id") val numberId: Int,
    @SerializedName("number") val number: String? = null,
    @SerializedName("peer") val peer: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("direction") val direction: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("unread") val unread: Int = 0,
)

data class SmsThreadsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("threads") val threads: List<SmsThread>? = null,
    @SerializedName("error") val error: String? = null,
)

/** One MMS picture or file on a message (api/mobile/sms.php thread). */
data class SmsMedia(
    @SerializedName("id") val id: Int,
    @SerializedName("content_type") val contentType: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("url") val url: String? = null,
)

/** One SMS/MMS message in a conversation (api/mobile/sms.php thread). */
data class SmsMessage(
    @SerializedName("id") val id: Int,
    @SerializedName("peer") val peer: String? = null,
    @SerializedName("direction") val direction: String? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("error") val error: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("is_read") val isRead: Boolean = false,
    @SerializedName("media") val media: List<SmsMedia>? = null,
    /** The message this one answers (a reply), or null. */
    @SerializedName("reply_to") val replyTo: SmsReplyRef? = null,
)

/** The quoted message of a reply; [deleted] when it was deleted since. */
data class SmsReplyRef(
    @SerializedName("id") val id: Int,
    @SerializedName("body") val body: String? = null,
    @SerializedName("direction") val direction: String? = null,
    @SerializedName("media") val media: Int = 0,
    @SerializedName("deleted") val deleted: Boolean = false,
)

/** One GIF from the server's GIPHY search: [preview] to show, [url] to send. */
data class SmsGif(
    @SerializedName("id") val id: String? = null,
    @SerializedName("preview") val preview: String? = null,
    @SerializedName("url") val url: String? = null,
)

data class SmsGifsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("gifs") val gifs: List<SmsGif>? = null,
    @SerializedName("error") val error: String? = null,
)

data class SmsThreadResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("messages") val messages: List<SmsMessage>? = null,
    @SerializedName("error") val error: String? = null,
)

/** Result of sending an SMS/MMS: the stored message id. */
data class SmsSendResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message_id") val messageId: Int? = null,
    @SerializedName("error") val error: String? = null,
)

/** Result of the poll action: the newest message id of the user's numbers. */
data class SmsPollResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("last") val last: Int = 0,
    @SerializedName("error") val error: String? = null,
)

data class CallHistoryResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("extension") val extension: String?,
    @SerializedName("filter") val filter: String?,
    @SerializedName("total_returned") val totalReturned: Int,
    @SerializedName("stats") val stats: CallStats?,
    @SerializedName("calls") val calls: List<CallRecord>?
)

data class CallStats(
    @SerializedName("total_calls") val totalCalls: Int,
    @SerializedName("incoming_calls") val incomingCalls: Int,
    @SerializedName("outgoing_calls") val outgoingCalls: Int,
    @SerializedName("missed_calls") val missedCalls: Int,
    @SerializedName("total_billsec") val totalBillsec: Int
)

data class CallRecord(
    @SerializedName("id") val id: Long,
    /** Key for hiding the call from the own history (server 1.5.3+); null on older servers. */
    @SerializedName("call_key") val callKey: String? = null,
    @SerializedName("calldate") val calldate: String,
    @SerializedName("direction") val direction: String, // "in", "out", "missed"
    @SerializedName("party") val party: String,
    @SerializedName("party_name") val partyName: String?,
    @SerializedName("duration") val duration: Int,
    @SerializedName("billsec") val billsec: Int,
    @SerializedName("disposition") val disposition: String?,
    @SerializedName("channel") val channel: String?
)

data class ContactsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("total") val total: Int,
    @SerializedName("contacts") val contacts: List<ContactItem>?
)

data class ContactItem(
    @SerializedName("extension") val extension: String,
    @SerializedName("name") val name: String,
    @SerializedName("role") val role: String?,
    /** Role in the app's language (built-in roles) or as named by the admin. */
    @SerializedName("role_name") val roleName: String? = null,
    @SerializedName("status") val status: String, // "online", "busy", "offline"
    @SerializedName("sip_status") val sipStatus: String?,
    @SerializedName("webrtc_status") val webrtcStatus: String?,
    @SerializedName("mob_webrtc_status") val mobWebrtcStatus: String? = null
)

data class FeaturesResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("features") val features: FeatureSettings?
)

data class FeatureSettings(
    @SerializedName("extension") val extension: String,
    @SerializedName("dnd_enabled") val dndEnabled: Boolean,
    @SerializedName("call_forward_number") val callForwardNumber: String?,
    @SerializedName("cf_busy_number") val cfBusyNumber: String? = null,
    @SerializedName("cf_noanswer_number") val cfNoAnswerNumber: String? = null,
    @SerializedName("cf_noanswer_timeout") val cfNoAnswerTimeout: Int? = 20,
    @SerializedName("allowed_phone_mode") val allowedPhoneMode: String? = "both",
    // Server 1.22+: video calls on for the PBX and allowed for this user.
    @SerializedName("video") val video: VideoSettings? = null
)

data class VideoSettings(
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("resolution") val resolution: String? = "1280x720",
    @SerializedName("fps") val fps: Int? = 24
)

data class FcmTokenResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("device_id") val deviceId: String?,
    @SerializedName("id") val id: Int?
)

// --- CHAT & MEDIA MODELS ---

data class ChatConversationsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("conversations") val conversations: List<ChatConversation>?
)

data class ChatConversation(
    @SerializedName("id") val id: Int,
    @SerializedName("type") val type: String,
    @SerializedName("direct_key") val directKey: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("avatar_url") val avatarUrl: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("created_by") val createdBy: String,
    @SerializedName("last_message_text") val lastMessageText: String? = null,
    @SerializedName("last_message_at") val lastMessageAt: String? = null,
    @SerializedName("unread_count") val unreadCount: Int = 0,
    @SerializedName("target_ext") val targetExt: String? = null,
    @SerializedName("target_name") val targetName: String? = null,
    @SerializedName("target_online") val targetOnline: Boolean = false,
    @SerializedName("member_count") val memberCount: Int = 0,
    @SerializedName("online_count") val onlineCount: Int = 0,
    @SerializedName("my_role") val myRole: String? = null,
    @SerializedName("participants") val participants: List<ChatParticipant>? = null
)

data class ChatParticipant(
    @SerializedName("conversation_id") val conversationId: Int = 0,
    @SerializedName("extension") val extension: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("role") val role: String = "member",
    @SerializedName("joined_at") val joinedAt: String? = null,
    @SerializedName("is_online") var isOnline: Boolean = false
)

data class ChatMessagesResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("messages") val messages: List<ChatMessage>?
)

data class ChatMessage(
    @SerializedName("id") val id: Long,
    @SerializedName("conversation_id") val conversationId: Int,
    @SerializedName("sender_ext") val senderExt: String,
    @SerializedName("sender_name") val senderName: String,
    @SerializedName("msg_type") val msgType: String,
    @SerializedName("message") val message: String? = null,
    @SerializedName("attachment_url") val attachmentUrl: String? = null,
    @SerializedName("file_name") val fileName: String? = null,
    @SerializedName("file_size") val fileSize: Long = 0,
    @SerializedName("mime_type") val mimeType: String? = null,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("is_me") var isMe: Boolean = false,
    @SerializedName("system_event") val systemEvent: String? = null,
    @SerializedName("system_meta") val systemMeta: String? = null,
    /** Own messages: "sent", "delivered" or "read". */
    @SerializedName("status") var status: String? = null,
    /** Deleted by its sender: text and attachment are cleared on the server. */
    @SerializedName("is_deleted") val isDeleted: Boolean = false
)

data class ChatUploadResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("msg_type") val msgType: String? = null,
    @SerializedName("attachment_url") val attachmentUrl: String? = null,
    @SerializedName("thumb_url") val thumbUrl: String? = null,
    @SerializedName("file_name") val fileName: String? = null,
    @SerializedName("file_size") val fileSize: Long = 0,
    @SerializedName("mime_type") val mimeType: String? = null,
    @SerializedName("error") val error: String? = null
)

data class DirectChatResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("conversation") val conversation: ChatConversation?
)

data class GroupChatResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("conversation") val conversation: ChatConversation?,
    @SerializedName("error") val error: String? = null
)

data class GroupMembersAddedResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("added") val added: List<String>? = null,
    @SerializedName("error") val error: String? = null
)

data class GenericChatActionResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("error") val error: String? = null
)

