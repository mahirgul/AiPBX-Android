package com.mhrgl.aipbx.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.mhrgl.aipbx.data.ApiClient
import com.mhrgl.aipbx.data.AppPreferences
import com.mhrgl.aipbx.data.AuthSession
import com.mhrgl.aipbx.model.PushConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object FcmHelper {
    private const val TAG = "FcmHelper"
    private val deviceOperations = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deviceLock = Mutex()

    /**
     * Dynamically initialize Firebase at runtime ONLY if FCM is configured and enabled.
     * If push is disabled or provider is 'none', this function returns immediately
     * without making any connection or initializing Google SDKs.
     */
    fun initIfConfigured(context: Context, pushConfig: PushConfig? = null) {
        val prefs = AppPreferences.getInstance(context)
        val config = pushConfig ?: prefs.pushConfig

        if (config == null || !config.enabled || config.provider != "fcm") {
            FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }?.delete()
            prefs.fcmToken = null
            Log.d(TAG, "Push provider is not FCM (provider='${config?.provider}').")
            return
        }

        val appId = config.fcmAppId?.trim() ?: ""
        val apiKey = config.fcmApiKey?.trim() ?: ""
        val projectId = config.fcmProjectId?.trim() ?: ""
        val senderId = config.fcmSenderId?.trim() ?: ""

        if (appId.isEmpty() || apiKey.isEmpty() || projectId.isEmpty()) {
            Log.w(TAG, "Incomplete FCM credentials provided by server. Skipping Firebase initialization.")
            return
        }

        val expectedSession = prefs.sessionSnapshot()

        try {
            val options = FirebaseOptions.Builder()
                .setApplicationId(appId)
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .apply {
                    if (senderId.isNotEmpty()) {
                        setGcmSenderId(senderId)
                    }
                }
                .build()

            getOrCreateFirebaseApp(context.applicationContext, options)
            Log.i(TAG, "FirebaseApp dynamically initialized for project: $projectId")

            // Fetch the registration token of the default app, now set up for this server's project
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (expectedSession != null && prefs.sessionSnapshot() != expectedSession) {
                    Log.d(TAG, "Session changed before FCM token acquired for project $projectId; ignoring.")
                    return@addOnCompleteListener
                }
                val currentProjectId = prefs.pushConfig?.fcmProjectId?.trim() ?: ""
                if (currentProjectId.isNotEmpty() && currentProjectId != projectId) {
                    Log.d(TAG, "Active FCM project changed ($currentProjectId != $projectId); ignoring stale token.")
                    return@addOnCompleteListener
                }
                if (task.isSuccessful && !task.result.isNullOrEmpty()) {
                    val token = task.result
                    Log.i(TAG, "FCM registration token acquired: ${token.take(15)}...")
                    prefs.fcmToken = token
                    sendTokenToServer(context, token, expectedSession)
                } else {
                    Log.w(TAG, "Failed to retrieve FCM registration token", task.exception)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing dynamic FirebaseApp", e)
        }
    }

    /**
     * The default FirebaseApp, set up for this server's project. FCM delivers messages
     * (FirebaseMessagingService) only through the default app, so a named app would get
     * a token but never the pushes; when the server's project changed, the default app is
     * deleted and set up again with the new options.
     */
    private fun getOrCreateFirebaseApp(
        appContext: Context,
        options: FirebaseOptions
    ): FirebaseApp {
        val existing = FirebaseApp.getApps(appContext).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (existing != null) {
            val opts = existing.options
            if (opts.applicationId == options.applicationId &&
                opts.apiKey == options.apiKey &&
                opts.projectId == options.projectId &&
                (opts.gcmSenderId ?: "") == (options.gcmSenderId ?: "")
            ) {
                return existing
            }
            existing.delete()
        }
        return FirebaseApp.initializeApp(appContext, options)
    }

    fun sendTokenToServer(context: Context, fcmToken: String, expectedSession: AuthSession? = null) {
        val prefs = AppPreferences.getInstance(context)
        val session = prefs.sessionSnapshot() ?: return
        if (expectedSession != null && session != expectedSession) return

        deviceOperations.launch {
            deviceLock.withLock {
                try {
                    if (prefs.sessionSnapshot() != session) return@withLock
                    val apiClient = ApiClient { prefs }
                    val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
                    val res = apiClient.registerFcmToken(
                        baseUrl = session.serverUrl,
                        token = session.token,
                        fcmToken = fcmToken,
                        deviceId = prefs.deviceUuid,
                        deviceName = deviceName
                    )
                    res.onSuccess {
                        Log.i(TAG, "FCM token successfully registered with AI PBX server.")
                    }.onFailure { err ->
                        Log.w(TAG, "Failed to register FCM token with server: ${err.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception registering FCM token with server", e)
                }
            }
        }
    }

    fun unregisterTokenFromServer(context: Context) {
        val prefs = AppPreferences.getInstance(context)
        val session = prefs.sessionSnapshot() ?: return
        val deviceId = prefs.deviceUuid
        val fcmToken = prefs.fcmToken

        deviceOperations.launch {
            deviceLock.withLock {
                try {
                    // Logout clears preferences immediately. Cleanup uses only the captured
                    // session and must never refresh or borrow a subsequent login's token.
                    val apiClient = ApiClient()
                    val result = apiClient.unregisterFcmToken(
                        baseUrl = session.serverUrl,
                        token = session.token,
                        deviceId = deviceId,
                        fcmToken = fcmToken
                    )
                    result.onSuccess {
                        Log.i(TAG, "FCM token deregistered from AI PBX server.")
                    }.onFailure { error ->
                        Log.w(TAG, "FCM token deregistration failed: ${error.message}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Exception deregistering FCM token from server: ${e.message}")
                }
            }
        }
    }
}
