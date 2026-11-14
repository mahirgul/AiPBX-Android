package com.mhrgl.aipbx.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.widget.ImageView
import android.widget.TextView
import com.mhrgl.aipbx.R
import com.mhrgl.aipbx.data.ApiClient
import com.mhrgl.aipbx.data.AppPreferences
import com.mhrgl.aipbx.model.ServerInfo
import java.io.File

/**
 * The company's logo and colour from its server (Appearance page of the portal),
 * shown in the app instead of the AiPBX logo. The launcher icon stays AiPBX:
 * Android does not let an app take its icon from the internet.
 *
 * The logos are saved once (files/brand_logo.png, brand_logo_dark.png) and fetched again only when its
 * path on the server changes (the portal adds ?v=... on every upload). SVG cannot
 * be shown by Android without a library, so an SVG logo falls back to the favicon.
 */
object BrandLook {
    private const val FILE = "brand_logo.png"
    private const val FILE_DARK = "brand_logo_dark.png"

    /** Keeps what [info] says; returns true when a logo or the colour changed. */
    suspend fun update(context: Context, prefs: AppPreferences, api: ApiClient, info: ServerInfo): Boolean {
        val color = info.brandColor.orEmpty().takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) }.orEmpty()
        var changed = color != prefs.brandColor
        prefs.brandColor = color
        prefs.brandLogoStyle = if (info.logoStyle == "wide") "wide" else "square"
        val base = prefs.serverUrl.trim().trimEnd('/')

        val light = fetch(api, base, listOfNotNull(info.logoUrl, info.faviconUrl), File(context.filesDir, FILE), prefs.brandLogoSource)
        if (light != null) { prefs.brandLogoSource = light; changed = true }
        // The dark-theme logo only stands for the company's own logo, never for the favicon.
        val dark = fetch(api, base, listOfNotNull(info.logoUrlDark), File(context.filesDir, FILE_DARK), prefs.brandLogoDarkSource)
        if (dark != null) { prefs.brandLogoDarkSource = dark; changed = true }
        return changed
    }

    /**
     * Saves the first logo of [paths] that Android can show into [target].
     * Returns the new source ("" when there is none any more), or null when nothing changed.
     */
    private suspend fun fetch(api: ApiClient, base: String, paths: List<String>, target: File, current: String): String? {
        val usable = paths.filter { it.startsWith("/") && !it.startsWith("//") && !it.substringBefore('?').endsWith(".svg", ignoreCase = true) }
        for (path in usable) {
            if (path == current && target.exists()) return null
            val bitmap = api.download(base + path)?.let { decode(it) } ?: continue
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return path
        }
        if (current.isEmpty() && !target.exists()) return null
        target.delete()
        return ""
    }

    /** At most 512 px on the long side: a logo, not a photo. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * The company logo in [view] (wider for a wide logo), its dark-theme logo when
     * the app is dark and the company has one, or the AiPBX logo (on its own white
     * circle, so it reads in both themes).
     */
    fun applyLogo(view: ImageView, prefs: AppPreferences) {
        val dir = view.context.filesDir
        val night = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = File(dir, FILE_DARK).takeIf { night && prefs.brandLogoDarkSource.isNotEmpty() && it.exists() }
        val light = File(dir, FILE).takeIf { prefs.brandLogoSource.isNotEmpty() && it.exists() }
        val bitmap = (dark ?: light)?.let { BitmapFactory.decodeFile(it.path) }
        if (bitmap == null) {
            view.setImageResource(R.drawable.app_logo_white_circle)
            view.layoutParams = view.layoutParams.apply { width = height }
            return
        }
        view.setImageBitmap(bitmap)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.layoutParams = view.layoutParams.apply { width = if (prefs.brandLogoStyle == "wide") height * 3 else height }
    }

    /** The company colour on the brand name and the main button, when one is set. */
    fun applyColor(prefs: AppPreferences, title: TextView?, vararg buttons: android.view.View) {
        val c = prefs.brandColor.takeIf { it.isNotEmpty() }?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: return
        title?.setTextColor(c)
        buttons.forEach { it.backgroundTintList = ColorStateList.valueOf(c) }
    }
}
