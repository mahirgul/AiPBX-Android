package com.mhrgl.aipbx.util

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.mhrgl.aipbx.BuildConfig
import com.mhrgl.aipbx.R
import com.mhrgl.aipbx.data.AppPreferences
import com.mhrgl.aipbx.ui.CallActivity
import com.mhrgl.aipbx.ui.IncomingCallActivity
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * In-app update check against the public GitHub releases of the OwnPBX Android app
 * (DanversKara/android.OwnPBX.org). Runs when the app comes to the foreground; silent when up to
 * date, offline, or on any error. When a newer release exists, one dialog per version offers to
 * download the APK and install it from inside the app — the old My Phone app checked on every
 * foreground, this is the same behaviour here.
 *
 * No auth: the repo is public. Unauthenticated GitHub API limit is 60 requests/hour per IP; the
 * check runs at most once per foreground entry and never overlaps itself.
 */
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/DanversKara/android.OwnPBX.org/releases/latest"
    private const val MIME_APK = "application/vnd.android.package-archive"

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var checkInFlight = false

    /** A release new enough to offer, with the APK asset to download. */
    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val fileName: String,
        val isDebugAsset: Boolean,
    )

    private data class GithubRelease(
        @SerializedName("tag_name") val tagName: String?,
        @SerializedName("assets") val assets: List<GithubAsset> = emptyList(),
    )

    private data class GithubAsset(
        @SerializedName("name") val name: String?,
        @SerializedName("browser_download_url") val downloadUrl: String?,
    )

    /** Called on the 0 -> 1 foreground transition (see AiPbxApp). */
    fun checkForUpdate(activity: Activity) {
        if (checkInFlight) return
        if (activity.isFinishing || activity.isDestroyed) return
        // Never pop an update dialog over a call screen.
        if (activity is IncomingCallActivity || activity is CallActivity) return
        checkInFlight = true
        scope.launch {
            try {
                val latest = fetchLatestRelease() ?: return@launch
                if (!isNewerVersion(latest.version, BuildConfig.VERSION_NAME)) return@launch
                val prefs = AppPreferences.getInstance(activity)
                if (prefs.updateDismissedVersion == latest.version) return@launch
                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    // Post to the decor view so the window token exists even
                    // when the check returns during cold start.
                    activity.window?.decorView?.post { showUpdateDialog(activity, latest) }
                        ?: showUpdateDialog(activity, latest)
                }
            } catch (e: Exception) {
                // Offline, rate-limited, bad JSON — stay silent, try next time.
                Log.d(TAG, "Update check failed", e)
            } finally {
                checkInFlight = false
            }
        }
    }

    /** "1.60.9" -> [1, 60, 9]; leading "v" and a "-suffix" are ignored. */
    fun parseVersion(version: String): List<Int> =
        version.trim().removePrefix("v").substringBefore("-").split(".").map {
            it.toIntOrNull() ?: 0
        }

    /** Numeric per-part compare: 1.60.10 is newer than 1.60.9. */
    fun isNewerVersion(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        val n = maxOf(l.size, c.size)
        for (i in 0 until n) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        val request =
            Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OwnPBX-Android")
                .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.d(TAG, "GitHub latest release: HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val release = gson.fromJson(body, GithubRelease::class.java)
            val version = release.tagName?.trim()?.removePrefix("v").orEmpty()
            if (version.isEmpty()) return null
            val apks =
                release.assets.filter {
                    !it.name.isNullOrBlank() &&
                        it.name.endsWith(".apk") &&
                        !it.downloadUrl.isNullOrBlank()
                }
            if (apks.isEmpty()) return null
            // Prefer the release-signed APK; fall back to the debug one when
            // the workflow only attached that (see auto-release.yml).
            val asset = apks.firstOrNull { !it.name!!.contains("-debug") } ?: apks.first()
            return UpdateInfo(
                version = version,
                downloadUrl = asset.downloadUrl!!,
                fileName = asset.name!!,
                isDebugAsset = asset.name.contains("-debug"),
            )
        }
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        // Re-checked here: the dialog is posted to the decor view, so the
        // activity can be gone by the time it runs (e.g. user backgrounded
        // the app while the release check was in flight).
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = AppPreferences.getInstance(activity)
        // At most once per new version: Later, back, or tap-outside all count
        // as "not now" for this version; the next version prompts again.
        val markDismissed = { prefs.updateDismissedVersion = info.version }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, info.version))
            .setMessage(activity.getString(R.string.update_message))
            .setPositiveButton(R.string.update_download) { _, _ ->
                startDownload(activity, info)
            }
            .setNegativeButton(R.string.update_later) { _, _ -> markDismissed() }
            .setOnCancelListener { markDismissed() }
            .show()
    }

    private fun startDownload(activity: Activity, info: UpdateInfo) {
        val prefs = AppPreferences.getInstance(activity)
        // A release-signed APK cannot install over a debug-signed install
        // (and vice versa), and the install intent reports no failure — the
        // installer just closes. Catch the mismatch here instead of failing
        // silently after a download. This version can never install, so do
        // not keep prompting for it.
        if (BuildConfig.DEBUG != info.isDebugAsset) {
            prefs.updateDismissedVersion = info.version
            Toast.makeText(activity, R.string.update_signature_mismatch, Toast.LENGTH_LONG).show()
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            // Android 8+: the user must allow "install unknown apps" for
            // OwnPBX first. Deliberately NOT dismissed: the toast tells them
            // to tap Download again, and the dialog reappears on the next
            // foreground so they can.
            Toast.makeText(activity, R.string.update_allow_unknown_sources, Toast.LENGTH_LONG)
                .show()
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        val request =
            DownloadManager.Request(Uri.parse(info.downloadUrl))
                .setTitle(activity.getString(R.string.update_downloading, info.version))
                .setDescription(info.fileName)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, info.fileName)
                .setMimeType(MIME_APK)
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        prefs.updateDownloadId = dm.enqueue(request)
        prefs.updateDownloadFileName = info.fileName
        // Only now that the download actually started: at most one prompt
        // per version, and no re-prompt while it downloads/installs.
        prefs.updateDismissedVersion = info.version
    }
}

/**
 * Fires the installer when the update APK finishes downloading. Registered in the manifest for
 * DownloadManager.ACTION_DOWNLOAD_COMPLETE; ignores any download that is not the one
 * AppUpdateChecker started.
 */
class AppUpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val prefs = AppPreferences.getInstance(context)
        if (id == -1L || id != prefs.updateDownloadId) return
        prefs.updateDownloadId = -1L
        val fileName = prefs.updateDownloadFileName
        if (fileName.isEmpty()) return
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return
                val status =
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status != DownloadManager.STATUS_SUCCESSFUL) return
            }
            @Suppress("DEPRECATION")
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val apk = File(downloadsDir, fileName)
            if (!apk.exists()) return
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apk,
                )
            val install =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
            context.startActivity(install)
        } catch (e: Exception) {
            Log.e("AppUpdateChecker", "Could not open installer for $fileName", e)
            Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
        }
    }
}
