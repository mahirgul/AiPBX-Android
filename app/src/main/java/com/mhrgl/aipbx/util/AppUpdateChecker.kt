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
 * foreground, this is the same behaviour here. A manual check (Settings → Check for updates)
 * behaves the same except it always answers: it ignores the dismissed-version suppression and
 * toasts when up to date or when the check fails.
 *
 * No auth: the repo is public. Unauthenticated GitHub API limit is 60 requests/hour per IP, so
 * the automatic check runs at most once an hour (see AUTO_CHECK_INTERVAL_MS) and never overlaps
 * itself — firing on every foreground burned the budget and the next check failed with a
 * misleading "check your connection". A manual check always runs, and a 403/429 from GitHub gets
 * its own "rate limited" message instead of blaming the connection.
 */
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/DanversKara/android.OwnPBX.org/releases/latest"
    private const val MIME_APK = "application/vnd.android.package-archive"

    /** Automatic check interval: GitHub allows 60 unauthenticated calls/hour per IP. */
    private const val AUTO_CHECK_INTERVAL_MS = 60 * 60 * 1000L

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

    /** What the GitHub lookup returned, so the caller can tell the user the truth. */
    private sealed interface FetchResult {
        data class Available(val info: UpdateInfo) : FetchResult
        data class HttpError(val code: Int) : FetchResult
        data object Failed : FetchResult
    }

    /**
     * Automatic check: called on the 0 -> 1 foreground transition (see AiPbxApp). Silent when up
     * to date, offline, or on any error; a version the user dismissed is not offered again. Runs
     * at most once an hour — GitHub allows 60 unauthenticated API calls per hour per IP, and a
     * check on every foreground burned that budget. Manual check (manual = true, Settings →
     * Check for updates): the user asked, so it always runs, ignores the dismissed-version
     * suppression and always answers — the update dialog when a newer release exists, an "up to
     * date" toast when there isn't one, a "rate limited" toast when GitHub says 403/429, and a
     * failure toast when the check itself fails.
     */
    fun checkForUpdate(activity: Activity, manual: Boolean = false) {
        if (checkInFlight) return
        if (activity.isFinishing || activity.isDestroyed) return
        // Never pop an update dialog over a call screen.
        if (activity is IncomingCallActivity || activity is CallActivity) return
        checkInFlight = true
        scope.launch {
            try {
                val prefs = AppPreferences.getInstance(activity)
                if (!manual) {
                    val lastCheck = prefs.updateLastCheckTime
                    if (
                        lastCheck > 0L &&
                            System.currentTimeMillis() - lastCheck < AUTO_CHECK_INTERVAL_MS
                    ) {
                        return@launch
                    }
                }
                // Last attempt, automatic or manual: a fresh manual answer
                // makes an automatic re-check within the hour pointless.
                prefs.updateLastCheckTime = System.currentTimeMillis()
                when (val result = fetchLatestRelease()) {
                    is FetchResult.Available -> {
                        val latest = result.info
                        if (!isNewerVersion(latest.version, BuildConfig.VERSION_NAME)) {
                            if (manual) {
                                toastOnMain(
                                    activity,
                                    R.string.update_up_to_date,
                                    BuildConfig.VERSION_NAME,
                                )
                            }
                            return@launch
                        }
                        // Manual checks ignore the dismissed-version suppression: the
                        // user asked, so a version they tapped Later on still shows.
                        // Showing it again dismisses nothing — the dialog's own
                        // Later/Download handling is unchanged.
                        if (!manual && prefs.updateDismissedVersion == latest.version) {
                            return@launch
                        }
                        withContext(Dispatchers.Main) {
                            if (activity.isFinishing || activity.isDestroyed) return@withContext
                            // Post to the decor view so the window token exists even
                            // when the check returns during cold start.
                            activity.window?.decorView?.post { showUpdateDialog(activity, latest) }
                                ?: showUpdateDialog(activity, latest)
                        }
                    }
                    is FetchResult.HttpError -> {
                        // 403/429 is GitHub rate-limiting the check, not a broken
                        // connection — say so instead of blaming the network.
                        if (manual) {
                            val messageRes =
                                if (result.code == 403 || result.code == 429) {
                                    R.string.update_rate_limited
                                } else {
                                    R.string.update_check_failed
                                }
                            toastOnMain(activity, messageRes)
                        }
                    }
                    FetchResult.Failed -> {
                        // No release info (empty body, no tag, no APK): the
                        // automatic check stays silent; a manual check says so.
                        if (manual) toastOnMain(activity, R.string.update_check_failed)
                    }
                }
            } catch (e: Exception) {
                // Offline, bad JSON — the automatic check stays silent and
                // tries next time; a manual check says so.
                Log.d(TAG, "Update check failed", e)
                if (manual) toastOnMain(activity, R.string.update_check_failed)
            } finally {
                checkInFlight = false
            }
        }
    }

    /** Manual-check feedback, shown on the main thread from the application context. */
    private suspend fun toastOnMain(activity: Activity, messageRes: Int, vararg args: Any) {
        withContext(Dispatchers.Main) {
            val text = activity.applicationContext.getString(messageRes, *args)
            Toast.makeText(activity.applicationContext, text, Toast.LENGTH_LONG).show()
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

    private fun fetchLatestRelease(): FetchResult {
        val request =
            Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OwnPBX-Android")
                .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "GitHub latest release: HTTP ${response.code}")
                    return FetchResult.HttpError(response.code)
                }
                val body = response.body?.string() ?: return FetchResult.Failed
                val release = gson.fromJson(body, GithubRelease::class.java)
                val version = release.tagName?.trim()?.removePrefix("v").orEmpty()
                if (version.isEmpty()) return FetchResult.Failed
                val apks =
                    release.assets.filter {
                        !it.name.isNullOrBlank() &&
                            it.name.endsWith(".apk") &&
                            !it.downloadUrl.isNullOrBlank()
                    }
                if (apks.isEmpty()) return FetchResult.Failed
                // Prefer the release-signed APK; fall back to the debug one when
                // the workflow only attached that (see auto-release.yml).
                val asset = apks.firstOrNull { !it.name!!.contains("-debug") } ?: apks.first()
                FetchResult.Available(
                    UpdateInfo(
                        version = version,
                        downloadUrl = asset.downloadUrl!!,
                        fileName = asset.name!!,
                        isDebugAsset = asset.name.contains("-debug"),
                    )
                )
            }
        } catch (e: Exception) {
            // Offline, DNS failure, timeout.
            Log.d(TAG, "Update check failed", e)
            FetchResult.Failed
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
