package com.mhrgl.aipbx.data

import com.mhrgl.aipbx.R
import com.mhrgl.aipbx.util.L10n
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import com.mhrgl.aipbx.model.*
import java.io.File
import java.util.concurrent.TimeUnit

class ApiClient(private val prefsProvider: (() -> AppPreferences?)? = null) {

    companion object {
        /** Called when the server rejects the saved session even after a refresh; set by AiPbxApp. */
        @Volatile
        var onSessionRejected: ((String?) -> Unit)? = null
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val refreshLock = Any()

    // Certificate validation is left to the PLATFORM — there is no bypass here.
    private val rawClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // The server answers (errors, role names) in the language picked in the app.
            .addInterceptor(Interceptor { chain ->
                val lang = try { L10n.current() } catch (e: Exception) { L10n.EN }
                chain.proceed(chain.request().newBuilder().header("X-App-Language", lang).build())
            })
            .build()
    }

    private val client: OkHttpClient by lazy {
        rawClient.newBuilder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val originalRequest = chain.request()
                    val response = chain.proceed(originalRequest)

                    if (response.code == 401 &&
                        !originalRequest.url.encodedPath.endsWith("login.php") &&
                        !originalRequest.url.encodedPath.endsWith("refresh.php") &&
                        !originalRequest.url.encodedPath.endsWith("ping.php")
                    ) {
                        val prefs = prefsProvider?.invoke()
                        val oldToken = prefs?.token
                        val baseUrl = prefs?.serverUrl
                        if (!oldToken.isNullOrEmpty() && !baseUrl.isNullOrEmpty()) {
                            response.close()
                            var retriedRequest: Request? = null
                            synchronized(refreshLock) {
                                val currentToken = prefs.token
                                if (!currentToken.isNullOrEmpty() && currentToken != oldToken) {
                                    retriedRequest = originalRequest.newBuilder()
                                        .header("Authorization", "Bearer $currentToken")
                                        .build()
                                } else {
                                    val cleanUrl = baseUrl.trim().trimEnd('/')
                                    val refreshRequest = Request.Builder()
                                        .url("$cleanUrl/api/mobile/refresh.php")
                                        .header("Authorization", "Bearer $oldToken")
                                        .get()
                                        .build()

                                    try {
                                        rawClient.newCall(refreshRequest).execute().use { refreshResp ->
                                            if (refreshResp.isSuccessful) {
                                                val body = refreshResp.body?.string() ?: ""
                                                val loginResult = gson.fromJson(body, LoginResponse::class.java)
                                                if (loginResult != null && loginResult.success && !loginResult.token.isNullOrEmpty()) {
                                                    prefs.saveLogin(loginResult)
                                                    Log.i("ApiClient", "Token successfully refreshed reactively after 401")
                                                    retriedRequest = originalRequest.newBuilder()
                                                        .header("Authorization", "Bearer ${loginResult.token}")
                                                        .build()
                                                }
                                            } else {
                                                Log.w("ApiClient", "Reactive refresh failed with HTTP ${refreshResp.code}")
                                                // The server no longer accepts this session: sign out instead
                                                // of showing empty screens while looking signed in (#17).
                                                if (refreshResp.code == 401 || refreshResp.code == 403) {
                                                    onSessionRejected?.invoke("HTTP ${refreshResp.code} from refresh.php")
                                                }
                                            }
                                            Unit
                                        }
                                    } catch (e: Exception) {
                                        Log.e("ApiClient", "Exception during reactive token refresh", e)
                                    }
                                }
                            }
                            if (retriedRequest != null) {
                                return chain.proceed(retriedRequest!!)
                            }
                        }
                    }
                    return response
                }
            })
            .build()
    }

    // Sends (SMS/MMS) go through the provider (VoIP.ms) on the server side, which
    // can take well past the 15 s read/write timeouts — especially MMS media
    // uploads. The send completes server-side but the app sees a timeout, so the
    // user is told it failed when the message actually went out. Sends get their
    // own client with 60 s timeouts (same 401-refresh interceptor); every other
    // call keeps the global 15 s.
    private val sendClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun ping(baseUrl: String): Result<ServerInfo> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/api/mobile/ping.php"
            val request = Request.Builder()
                .url(endpoint)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception(L10n.str(R.string.api_no_response, response.code)))
                }
                val info = gson.fromJson(body, ServerInfo::class.java)
                if (info.success) {
                    Result.success(info)
                } else {
                    Result.failure(Exception(L10n.str(R.string.api_invalid_response)))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(baseUrl: String, userOrExt: String, pass: String, otp: String? = null, deviceId: String? = null): Result<LoginResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/api/mobile/login.php"

                val jsonBody = JSONObject().apply {
                    put("username", userOrExt.trim())
                    put("password", pass.trim())
                    put("device_name", "Android-${android.os.Build.MODEL}")
                    if (!otp.isNullOrBlank()) put("otp", otp.trim())
                    if (!deviceId.isNullOrBlank()) {
                        put("device_id", deviceId.trim())
                        put("platform", "android")
                        put("app_version", com.mhrgl.aipbx.BuildConfig.VERSION_NAME)
                    }
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val result: LoginResponse? = gson.fromJson(body, LoginResponse::class.java)
                    if (response.isSuccessful && result != null && result.success) {
                        Result.success(result)
                    } else if (result?.otpRequired == true) {
                        Result.failure(com.mhrgl.aipbx.model.OtpRequiredException(result.error ?: L10n.str(R.string.api_otp_required)))
                    } else {
                        val errMsg = result?.error ?: L10n.str(R.string.api_login_failed, response.code)
                        Result.failure(Exception(errMsg))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun googleLogin(baseUrl: String, idToken: String, deviceId: String? = null): Result<LoginResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/api/mobile/google_login.php"

                val jsonBody = JSONObject().apply {
                    put("id_token", idToken.trim())
                    put("device_name", "Android-${android.os.Build.MODEL}")
                    if (!deviceId.isNullOrBlank()) {
                        put("device_id", deviceId.trim())
                        put("platform", "android")
                        put("app_version", com.mhrgl.aipbx.BuildConfig.VERSION_NAME)
                    }
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val result = gson.fromJson(body, LoginResponse::class.java)
                    if (response.isSuccessful && result != null && result.success) {
                        Result.success(result)
                    } else {
                        val errMsg = result?.error ?: L10n.str(R.string.api_google_failed, response.code)
                        Result.failure(Exception(errMsg))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun qrLogin(baseUrl: String, qrToken: String, deviceId: String? = null): Result<LoginResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/api/mobile/qr_login.php"

                val jsonBody = JSONObject().apply {
                    put("qr_token", qrToken.trim())
                    put("device_name", "Android-${android.os.Build.MODEL}")
                    if (!deviceId.isNullOrBlank()) {
                        put("device_id", deviceId.trim())
                        put("platform", "android")
                        put("app_version", com.mhrgl.aipbx.BuildConfig.VERSION_NAME)
                    }
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val result = gson.fromJson(body, LoginResponse::class.java)
                    if (response.isSuccessful && result != null && result.success) {
                        Result.success(result)
                    } else {
                        val errMsg = result?.error ?: L10n.str(R.string.api_qr_failed, response.code)
                        Result.failure(Exception(errMsg))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }



    /** Messages of the user's own mailbox, newest first. */
    suspend fun getVoicemail(baseUrl: String, token: String): Result<VoicemailResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl.trim().trimEnd('/')}/api/mobile/voicemail.php")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val result = gson.fromJson(response.body?.string() ?: "", VoicemailResponse::class.java)
                if (response.isSuccessful && result != null && result.success) Result.success(result)
                else Result.failure(Exception(result?.error ?: L10n.str(R.string.vm_load_failed)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** action "heard" (returns the message's new id) or "delete". */
    suspend fun voicemailAction(baseUrl: String, token: String, action: String, id: String): Result<VoicemailResponse> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("action", action).put("id", id).toString()
                val request = Request.Builder()
                    .url("${baseUrl.trim().trimEnd('/')}/api/mobile/voicemail.php")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    val result = gson.fromJson(response.body?.string() ?: "", VoicemailResponse::class.java)
                    if (response.isSuccessful && result != null && result.success) Result.success(result)
                    else Result.failure(Exception(result?.error ?: "HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Downloads a message's recording into [target] with this client (session
     * header and the app's TLS settings, which the system media player would not use).
     */
    suspend fun downloadVoicemail(baseUrl: String, token: String, id: String, target: File): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${baseUrl.trim().trimEnd('/')}/api/mobile/voicemail.php?action=play&id=" +
                    java.net.URLEncoder.encode(id, "UTF-8")
                val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").get().build()
                client.newCall(request).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                    target.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                Result.success(target)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // --- FAX API METHODS (api/mobile/fax.php) ---

    /** Received ("received") or sent ("sent") faxes, newest first. */
    suspend fun getFaxList(
        baseUrl: String,
        token: String,
        type: String,
        page: Int = 1,
        limit: Int = 50,
        search: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
    ): Result<FaxListResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
                var url =
                    "$cleanUrl/api/mobile/fax.php?action=list&type=${enc(type)}&page=$page&limit=$limit"
                if (!search.isNullOrBlank()) url += "&search=${enc(search)}"
                if (!dateFrom.isNullOrBlank()) url += "&date_from=${enc(dateFrom)}"
                if (!dateTo.isNullOrBlank()) url += "&date_to=${enc(dateTo)}"
                val request =
                    Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", FaxListResponse::class.java)
                    if (response.isSuccessful && result != null && result.success)
                        Result.success(result)
                    else
                        Result.failure(
                            Exception(result?.error ?: L10n.str(R.string.fax_load_failed))
                        )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Sendable DIDs, cover defaults and saved cover templates for the send screen. */
    suspend fun getFaxConfig(baseUrl: String, token: String): Result<FaxConfigResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val request =
                    Request.Builder()
                        .url("$cleanUrl/api/mobile/fax.php?action=config")
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", FaxConfigResponse::class.java)
                    if (response.isSuccessful && result != null && result.success)
                        Result.success(result)
                    else
                        Result.failure(
                            Exception(result?.error ?: L10n.str(R.string.fax_load_failed))
                        )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Downloads a fax PDF into [target] with this client (session header and the app's TLS
     * settings). [kind] is "received" or "sent".
     */
    suspend fun downloadFax(
        baseUrl: String,
        token: String,
        id: String,
        kind: String,
        target: File,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val url =
                    "${baseUrl.trim().trimEnd('/')}/api/mobile/fax.php?action=download&id=" +
                        java.net.URLEncoder.encode(id, "UTF-8") +
                        "&kind=" +
                        java.net.URLEncoder.encode(kind, "UTF-8")
                val request =
                    Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null)
                        return@withContext Result.failure(Exception("HTTP ${response.code}"))
                    target.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                Result.success(target)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Sends a fax. PDF mode attaches [pdfFile] as pdf_file; text mode sends [faxText] as
     * fax_text_content. Only the mode's own part is sent.
     */
    suspend fun sendFax(
        baseUrl: String,
        token: String,
        destNumber: String,
        composeMode: String,
        senderDid: String,
        coverEnabled: Boolean,
        coverTo: String,
        coverSubject: String,
        coverMessage: String,
        templateId: String?,
        pdfFile: File?,
        faxText: String?,
    ): Result<FaxSendResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val builder =
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("action", "send")
                        .addFormDataPart("dest_number", destNumber.trim())
                        .addFormDataPart("compose_mode", composeMode)
                        .addFormDataPart("sender_did", senderDid.trim())
                        .addFormDataPart("cover_enabled", if (coverEnabled) "1" else "0")
                        .addFormDataPart("cover_to", coverTo)
                        .addFormDataPart("cover_subject", coverSubject)
                        .addFormDataPart("cover_message", coverMessage)
                if (!templateId.isNullOrBlank()) builder.addFormDataPart("template_id", templateId)
                if (composeMode == "pdf" && pdfFile != null) {
                    builder.addFormDataPart(
                        "pdf_file",
                        pdfFile.name,
                        pdfFile.asRequestBody("application/pdf".toMediaType()),
                    )
                } else if (composeMode == "text") {
                    builder.addFormDataPart("fax_text_content", faxText.orEmpty())
                }
                val request =
                    Request.Builder()
                        .url("$cleanUrl/api/mobile/fax.php?action=send")
                        .addHeader("Authorization", "Bearer $token")
                        .post(builder.build())
                        .build()
                client.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", FaxSendResponse::class.java)
                    if (response.isSuccessful && result != null && result.success)
                        Result.success(result)
                    else
                        Result.failure(
                            Exception(
                                result?.error ?: L10n.str(R.string.fax_send_failed, response.code)
                            )
                        )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** action "mark_read" or "resend" for a fax; "delete" also needs [kind]. */
    suspend fun faxAction(
        baseUrl: String,
        token: String,
        action: String,
        id: String,
        kind: String? = null,
    ): Result<FaxActionResponse> =
        withContext(Dispatchers.IO) {
            try {
                val body =
                    JSONObject()
                        .apply {
                            put("action", action)
                            put("id", id)
                            if (kind != null) put("kind", kind)
                        }
                        .toString()
                val request =
                    Request.Builder()
                        .url("${baseUrl.trim().trimEnd('/')}/api/mobile/fax.php")
                        .addHeader("Authorization", "Bearer $token")
                        .post(body.toRequestBody(jsonMediaType))
                        .build()
                client.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", FaxActionResponse::class.java)
                    if (response.isSuccessful && result != null && result.success)
                        Result.success(result)
                    else Result.failure(Exception(result?.error ?: "HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // --- SMS API METHODS (api/mobile/sms.php) ---

    /** The user's SMS numbers (sendable DIDs). */
    suspend fun getSmsConfig(baseUrl: String, token: String): Result<SmsConfigResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val request =
                    Request.Builder()
                        .url("$cleanUrl/api/mobile/sms.php?action=config")
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", SmsConfigResponse::class.java)
                    if (response.isSuccessful && result != null && result.success)
                        Result.success(result)
                    else
                        Result.failure(
                            Exception(result?.error ?: L10n.str(R.string.sms_load_failed))
                        )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** SMS conversations (one of our numbers with one peer), newest first. */
    suspend fun getSmsThreads(baseUrl: String, token: String): Result<SmsThreadsResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val request =
                    Request.Builder()
                        .url("$cleanUrl/api/mobile/sms.php?action=threads")
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", SmsThreadsResponse::class.java)
                    if (response.isSuccessful && result != null && result.success)
                        Result.success(result)
                    else
                        Result.failure(
                            Exception(result?.error ?: L10n.str(R.string.sms_load_failed))
                        )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** The messages of one conversation, oldest first (opening it marks them read). */
    suspend fun getSmsThread(
        baseUrl: String,
        token: String,
        numberId: Int,
        peer: String,
    ): Result<SmsThreadResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
                val request =
                    Request.Builder()
                        .url(
                            "$cleanUrl/api/mobile/sms.php?action=thread&number_id=$numberId&peer=${enc(peer)}"
                        )
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", SmsThreadResponse::class.java)
                    if (response.isSuccessful && result != null && result.success)
                        Result.success(result)
                    else
                        Result.failure(
                            Exception(result?.error ?: L10n.str(R.string.sms_load_failed))
                        )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** The newest message id of the user's numbers, for polling. */
    suspend fun pollSms(baseUrl: String, token: String): Result<SmsPollResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val request =
                    Request.Builder()
                        .url("$cleanUrl/api/mobile/sms.php?action=poll")
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", SmsPollResponse::class.java)
                    if (response.isSuccessful && result != null && result.success)
                        Result.success(result)
                    else
                        Result.failure(
                            Exception(result?.error ?: L10n.str(R.string.sms_load_failed))
                        )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Sends an SMS/MMS. [imageFiles] are attached as media[] (at most 3,
     * 5 MB each — the plugin's own limits); text-only sends no parts.
     */
    suspend fun sendSms(
        baseUrl: String,
        token: String,
        numberId: Int,
        peer: String,
        text: String,
        imageFiles: List<File> = emptyList(),
        replyTo: Int = 0,
        gifUrl: String? = null,
    ): Result<SmsSendResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val builder =
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("action", "send")
                        .addFormDataPart("number_id", numberId.toString())
                        .addFormDataPart("peer", peer.trim())
                        .addFormDataPart("text", text)
                if (replyTo > 0) builder.addFormDataPart("reply_to", replyTo.toString())
                if (!gifUrl.isNullOrBlank()) builder.addFormDataPart("gif_url", gifUrl)
                for (file in imageFiles) {
                    val mime = if (file.name.endsWith(".gif", ignoreCase = true)) "image/gif" else "image/jpeg"
                    builder.addFormDataPart(
                        "media[]",
                        file.name,
                        file.asRequestBody(mime.toMediaType()),
                    )
                }
                val request =
                    Request.Builder()
                        .url("$cleanUrl/api/mobile/sms.php?action=send")
                        .addHeader("Authorization", "Bearer $token")
                        .post(builder.build())
                        .build()
                sendClient.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", SmsSendResponse::class.java)
                    if (response.isSuccessful && result != null && result.success)
                        Result.success(result)
                    else
                        Result.failure(
                            Exception(
                                result?.error ?: L10n.str(R.string.sms_send_failed, response.code)
                            )
                        )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Deletes one message from the PBX (it cannot unsend a text the peer already has). */
    suspend fun deleteSmsMessage(baseUrl: String, token: String, numberId: Int, messageId: Int): Result<Unit> =
        postSmsJson(baseUrl, token, JSONObject().apply {
            put("action", "delete")
            put("number_id", numberId)
            put("message_id", messageId)
        })

    /** Deletes a whole conversation (one number with one peer) from the PBX. */
    suspend fun deleteSmsThread(baseUrl: String, token: String, numberId: Int, peer: String): Result<Unit> =
        postSmsJson(baseUrl, token, JSONObject().apply {
            put("action", "delete_thread")
            put("number_id", numberId)
            put("peer", peer)
        })

    private suspend fun postSmsJson(baseUrl: String, token: String, body: JSONObject): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request.Builder()
                        .url("${baseUrl.trim().trimEnd('/')}/api/mobile/sms.php")
                        .addHeader("Authorization", "Bearer $token")
                        .post(body.toString().toRequestBody(jsonMediaType))
                        .build()
                client.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", SmsSendResponse::class.java)
                    if (response.isSuccessful && result != null && result.success) Result.success(Unit)
                    else Result.failure(Exception(result?.error ?: "HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** GIF search through the server (GIPHY); an empty [query] gives trending GIFs. */
    suspend fun searchSmsGifs(baseUrl: String, token: String, query: String): Result<SmsGifsResponse> =
        withContext(Dispatchers.IO) {
            try {
                val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                val request =
                    Request.Builder()
                        .url("${baseUrl.trim().trimEnd('/')}/api/mobile/sms.php?action=gifs&q=$q")
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", SmsGifsResponse::class.java)
                    if (response.isSuccessful && result != null && result.success) Result.success(result)
                    else Result.failure(Exception(result?.error ?: "HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Names a peer for everyone on that number (empty name removes it). */
    suspend fun saveSmsContact(
        baseUrl: String,
        token: String,
        numberId: Int,
        peer: String,
        name: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val body =
                    JSONObject()
                        .apply {
                            put("action", "contact")
                            put("number_id", numberId)
                            put("peer", peer)
                            put("name", name)
                        }
                        .toString()
                val request =
                    Request.Builder()
                        .url("${baseUrl.trim().trimEnd('/')}/api/mobile/sms.php")
                        .addHeader("Authorization", "Bearer $token")
                        .post(body.toRequestBody(jsonMediaType))
                        .build()
                client.newCall(request).execute().use { response ->
                    val result =
                        gson.fromJson(response.body?.string() ?: "", SmsSendResponse::class.java)
                    if (response.isSuccessful && result != null && result.success)
                        Result.success(Unit)
                    else Result.failure(Exception(result?.error ?: "HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getCallHistory(
        baseUrl: String,
        token: String,
        filter: String = "all",
        query: String? = null,
        limit: Int = 50
    ): Result<CallHistoryResponse> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            var url = "$cleanUrl/api/mobile/call_history.php?filter=$filter&limit=$limit"
            if (!query.isNullOrBlank()) {
                url += "&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val result = gson.fromJson(body, CallHistoryResponse::class.java)
                if (response.isSuccessful && result != null && result.success) {
                    Result.success(result)
                } else {
                    Result.failure(Exception(L10n.str(R.string.api_history_failed, response.code)))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Removes calls from the user's own call history (#10), or all of them
     * with [callKeys] = null. Only the list changes: the server keeps the
     * call records (reports, recordings).
     */
    suspend fun hideCalls(
        baseUrl: String,
        token: String,
        callKeys: List<String>?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            val payload = JSONObject().apply {
                if (callKeys == null) {
                    put("action", "clear")
                } else {
                    put("action", "hide")
                    put("call_keys", JSONArray(callKeys))
                }
            }.toString()
            val request = Request.Builder()
                .url("$cleanUrl/api/mobile/call_history.php")
                .addHeader("Authorization", "Bearer $token")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getContacts(
        baseUrl: String,
        token: String
    ): Result<ContactsResponse> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/api/mobile/contacts.php"
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val result = gson.fromJson(body, ContactsResponse::class.java)
                if (response.isSuccessful && result != null && result.success) {
                    Result.success(result)
                } else {
                    Result.failure(Exception(L10n.str(R.string.api_contacts_failed, response.code)))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFeatures(
        baseUrl: String,
        token: String
    ): Result<FeaturesResponse> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/api/mobile/features.php"
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val result = gson.fromJson(body, FeaturesResponse::class.java)
                if (response.isSuccessful && result != null && result.success) {
                    Result.success(result)
                } else {
                    Result.failure(Exception(L10n.str(R.string.api_features_failed, response.code)))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateFeatures(
        baseUrl: String,
        token: String,
        dndEnabled: Boolean,
        callForwardNumber: String,
        cfBusyNumber: String = "",
        cfNoAnswerNumber: String = "",
        cfNoAnswerTimeout: Int = 20,
        phoneMode: String = "both"
    ): Result<FeaturesResponse> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/api/mobile/features.php"

            val jsonBody = JSONObject().apply {
                put("dnd_enabled", dndEnabled)
                put("call_forward_number", callForwardNumber.trim())
                put("cf_busy_number", cfBusyNumber.trim())
                put("cf_noanswer_number", cfNoAnswerNumber.trim())
                put("cf_noanswer_timeout", cfNoAnswerTimeout)
                put("allowed_phone_mode", phoneMode)
            }.toString()

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $token")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val result = gson.fromJson(body, FeaturesResponse::class.java)
                if (response.isSuccessful && result != null && result.success) {
                    Result.success(result)
                } else {
                    Result.failure(Exception(result?.message ?: L10n.str(R.string.api_settings_failed, response.code)))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** "Call from": the numbers this user may call out as (default first). Parsed with org.json (no R8/Gson reflection). */
    suspend fun getCallerIds(baseUrl: String, token: String): Result<List<CallerIdOption>> =
        callerIdRequest(baseUrl, token, null)

    /** Makes one of the user's numbers the default ("" = the line's own number). */
    suspend fun setDefaultCallerId(baseUrl: String, token: String, number: String): Result<List<CallerIdOption>> =
        callerIdRequest(baseUrl, token, JSONObject().put("default", number).toString())

    private suspend fun callerIdRequest(baseUrl: String, token: String, postBody: String?): Result<List<CallerIdOption>> =
        withContext(Dispatchers.IO) {
            try {
                val endpoint = baseUrl.trim().trimEnd('/') + "/api/mobile/caller_ids.php"
                val builder = Request.Builder().url(endpoint).addHeader("Authorization", "Bearer $token")
                if (postBody != null) builder.post(postBody.toRequestBody(jsonMediaType)) else builder.get()
                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val json = try { JSONObject(body) } catch (e: Exception) { null }
                    if (!response.isSuccessful || json == null || !json.optBoolean("success")) {
                        val msg = json?.optString("error")?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}"
                        return@withContext Result.failure(Exception(msg))
                    }
                    val arr = json.optJSONArray("numbers") ?: JSONArray()
                    val list = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        CallerIdOption(o.optString("number"), o.optString("title"), o.optBoolean("default"))
                    }
                    Result.success(list)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun registerFcmToken(
        baseUrl: String,
        token: String,
        fcmToken: String,
        deviceId: String? = null,
        deviceName: String? = null
    ): Result<FcmTokenResponse> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/api/mobile/fcm_token.php"

            val jsonBody = JSONObject().apply {
                put("fcm_token", fcmToken.trim())
                put("device_id", deviceId ?: android.os.Build.ID)
                put("device_name", deviceName ?: "Android-${android.os.Build.MODEL}")
                put("platform", "android")
                put("app_version", com.mhrgl.aipbx.BuildConfig.VERSION_NAME)
            }.toString()

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $token")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val result = gson.fromJson(body, FcmTokenResponse::class.java)
                if (response.isSuccessful && result != null && result.success) {
                    Result.success(result)
                } else {
                    Result.failure(Exception(L10n.str(R.string.api_fcm_token_failed, response.code)))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unregisterFcmToken(
        baseUrl: String,
        token: String,
        deviceId: String? = null,
        fcmToken: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/api/mobile/fcm_token.php"

            val jsonBody = JSONObject().apply {
                if (!deviceId.isNullOrEmpty()) put("device_id", deviceId)
                if (!fcmToken.isNullOrEmpty()) put("fcm_token", fcmToken)
            }.toString()

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $token")
                .delete(jsonBody.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Failed to unregister FCM token: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshToken(baseUrl: String, token: String): Result<LoginResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/api/mobile/refresh.php"

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val result = gson.fromJson(body, LoginResponse::class.java)
                    if (response.isSuccessful && result != null && result.success) {
                        Result.success(result)
                    } else {
                        val errMsg = result?.error ?: L10n.str(R.string.api_token_failed, response.code)
                        if (response.code == 401 || response.code == 403) {
                            Result.failure(com.mhrgl.aipbx.model.SessionExpiredException(errMsg))
                        } else {
                            Result.failure(Exception(errMsg))
                        }
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // --- CHAT API METHODS ---

    suspend fun getChatConversations(baseUrl: String, token: String): Result<List<ChatConversation>> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/conversations"

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, ChatConversationsResponse::class.java)
                    if (response.isSuccessful && res != null && res.success) {
                        Result.success(res.conversations ?: emptyList())
                    } else {
                        Result.failure(Exception(L10n.str(R.string.api_chats_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Sends a text message over the chat REST API (no WebSocket needed); returns the saved message id. */
    suspend fun sendChatText(baseUrl: String, token: String, convId: Int, text: String): Result<Long> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val payload = JSONObject().apply {
                    put("conversation_id", convId)
                    put("msg_type", "text")
                    put("message", text)
                }.toString()
                val request = Request.Builder()
                    .url("$cleanUrl/chat/api/messages")
                    .addHeader("Authorization", "Bearer $token")
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val json = try { JSONObject(body) } catch (e: Exception) { null }
                    if (response.isSuccessful && json?.optBoolean("success") == true) {
                        Result.success(json.optJSONObject("message")?.optLong("id") ?: 0L)
                    } else {
                        Result.failure(Exception(json?.optString("error")?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Marks a conversation read up to [lastMessageId] over the chat REST API. */
    suspend fun markChatRead(baseUrl: String, token: String, convId: Int, lastMessageId: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val payload = JSONObject().apply {
                    put("conversation_id", convId)
                    put("last_message_id", lastMessageId)
                }.toString()
                val request = Request.Builder()
                    .url("$cleanUrl/chat/api/read")
                    .addHeader("Authorization", "Bearer $token")
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Result.success(Unit) else Result.failure(Exception("HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getChatMessages(baseUrl: String, token: String, convId: Int, limit: Int = 50, beforeId: Long = 0): Result<List<ChatMessage>> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                var endpoint = "$cleanUrl/chat/api/messages?conversation_id=$convId&limit=$limit"
                if (beforeId > 0) {
                    endpoint += "&before_id=$beforeId"
                }

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, ChatMessagesResponse::class.java)
                    if (response.isSuccessful && res != null && res.success) {
                        Result.success(res.messages ?: emptyList())
                    } else {
                        Result.failure(Exception(L10n.str(R.string.api_messages_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createDirectChat(baseUrl: String, token: String, targetExt: String): Result<ChatConversation> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/conversations/direct"
                val jsonBody = JSONObject().put("target_extension", targetExt).toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, DirectChatResponse::class.java)
                    if (response.isSuccessful && res != null && res.success && res.conversation != null) {
                        Result.success(res.conversation)
                    } else {
                        Result.failure(Exception(L10n.str(R.string.api_chat_create_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun uploadChatFile(baseUrl: String, token: String, file: File, mimeType: String): Result<ChatUploadResponse> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/upload"

                val mediaType = mimeType.toMediaTypeOrNull()
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
                    .build()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, ChatUploadResponse::class.java)
                    if (response.isSuccessful && res != null && res.success) {
                        Result.success(res)
                    } else {
                        Result.failure(Exception(res?.error ?: L10n.str(R.string.api_upload_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createGroupChat(
        baseUrl: String,
        token: String,
        title: String,
        description: String? = null,
        avatarUrl: String? = null,
        members: List<String>
    ): Result<ChatConversation> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/conversations/group"
                val jsonArr = JSONArray()
                members.forEach { jsonArr.put(it) }

                val jsonBody = JSONObject().apply {
                    put("title", title)
                    if (!description.isNullOrEmpty()) put("description", description)
                    if (!avatarUrl.isNullOrEmpty()) put("avatar_url", avatarUrl)
                    put("members", jsonArr)
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, GroupChatResponse::class.java)
                    if (response.isSuccessful && res != null && res.success && res.conversation != null) {
                        Result.success(res.conversation)
                    } else {
                        Result.failure(Exception(res?.error ?: L10n.str(R.string.api_group_create_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getGroupDetails(baseUrl: String, token: String, convId: Int): Result<ChatConversation> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/conversations/group?conversation_id=$convId"

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, GroupChatResponse::class.java)
                    if (response.isSuccessful && res != null && res.success && res.conversation != null) {
                        Result.success(res.conversation)
                    } else {
                        Result.failure(Exception(res?.error ?: L10n.str(R.string.api_group_details_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun updateGroupInfo(
        baseUrl: String,
        token: String,
        convId: Int,
        title: String,
        avatarUrl: String? = null,
        description: String? = null
    ): Result<ChatConversation> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/conversations/group/update"

                val jsonBody = JSONObject().apply {
                    put("conversation_id", convId)
                    put("title", title)
                    if (avatarUrl != null) put("avatar_url", avatarUrl)
                    if (description != null) put("description", description)
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, GroupChatResponse::class.java)
                    if (response.isSuccessful && res != null && res.success && res.conversation != null) {
                        Result.success(res.conversation)
                    } else {
                        Result.failure(Exception(res?.error ?: L10n.str(R.string.api_group_update_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun addGroupMembers(
        baseUrl: String,
        token: String,
        convId: Int,
        extensions: List<String>
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/conversations/group/members/add"
                val jsonArr = JSONArray()
                extensions.forEach { jsonArr.put(it) }

                val jsonBody = JSONObject().apply {
                    put("conversation_id", convId)
                    put("extensions", jsonArr)
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, GroupMembersAddedResponse::class.java)
                    if (response.isSuccessful && res != null && res.success) {
                        Result.success(res.added ?: emptyList())
                    } else {
                        Result.failure(Exception(res?.error ?: L10n.str(R.string.api_members_add_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun removeGroupMember(baseUrl: String, token: String, convId: Int, extension: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/conversations/group/members/remove"

                val jsonBody = JSONObject().apply {
                    put("conversation_id", convId)
                    put("extension", extension)
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, GenericChatActionResponse::class.java)
                    if (response.isSuccessful && res != null && res.success) {
                        Result.success(true)
                    } else {
                        Result.failure(Exception(res?.error ?: L10n.str(R.string.api_member_remove_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun updateGroupMemberRole(baseUrl: String, token: String, convId: Int, extension: String, role: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/conversations/group/members/role"

                val jsonBody = JSONObject().apply {
                    put("conversation_id", convId)
                    put("extension", extension)
                    put("role", role)
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, GenericChatActionResponse::class.java)
                    if (response.isSuccessful && res != null && res.success) {
                        Result.success(true)
                    } else {
                        Result.failure(Exception(res?.error ?: L10n.str(R.string.api_role_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun leaveGroup(baseUrl: String, token: String, convId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/conversations/group/leave"

                val jsonBody = JSONObject().apply {
                    put("conversation_id", convId)
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, GenericChatActionResponse::class.java)
                    if (response.isSuccessful && res != null && res.success) {
                        Result.success(true)
                    } else {
                        Result.failure(Exception(res?.error ?: L10n.str(R.string.api_leave_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Deletes your own chat message (REST, so the reason of a refusal comes
     * back). A message that is already deleted counts as success.
     */
    suspend fun deleteChatMessage(baseUrl: String, token: String, messageId: Long): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val jsonBody = JSONObject().apply { put("message_id", messageId) }.toString()
                val request = Request.Builder()
                    .url("$cleanUrl/chat/api/messages/delete")
                    .addHeader("Authorization", "Bearer $token")
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful || response.code == 409 -> Result.success(true)
                        response.code == 403 -> Result.failure(Exception(L10n.str(R.string.err_message_delete_not_allowed)))
                        else -> Result.failure(Exception(L10n.str(R.string.api_message_delete_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Deletes a whole chat conversation for this user only. The other
     * participant keeps their copy; a new message revives the thread.
     * (Not the SMS delete_thread behaviour, which hard-deletes the shared
     * thread's messages.)
     */
    suspend fun deleteChatConversation(baseUrl: String, token: String, convId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/conversations/delete"

                val jsonBody = JSONObject().apply {
                    put("conversation_id", convId)
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, GenericChatActionResponse::class.java)
                    if (response.isSuccessful && res != null && res.success) {
                        Result.success(true)
                    } else {
                        Result.failure(Exception(res?.error ?: L10n.str(R.string.api_conversation_delete_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteGroup(baseUrl: String, token: String, convId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trim().trimEnd('/')
                val endpoint = "$cleanUrl/chat/api/conversations/group/delete"

                val jsonBody = JSONObject().apply {
                    put("conversation_id", convId)
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $token")
                    .post(jsonBody.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val res = gson.fromJson(body, GenericChatActionResponse::class.java)
                    if (response.isSuccessful && res != null && res.success) {
                        Result.success(true)
                    } else {
                        Result.failure(Exception(res?.error ?: L10n.str(R.string.api_group_delete_failed, response.code)))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
