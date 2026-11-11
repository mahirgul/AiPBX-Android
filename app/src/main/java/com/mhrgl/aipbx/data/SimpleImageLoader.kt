package com.mhrgl.aipbx.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

object SimpleImageLoader {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    // Raw GIF bytes, so a chat list re-binding while scrolling does not
    // re-download the same GIF every time. Small (4 MB) — GIFs are short.
    private val gifByteCache = object : LruCache<String, ByteArray>(4 * 1024) {
        override fun sizeOf(key: String, bytes: ByteArray): Int = bytes.size / 1024
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * @param authToken chat media (/chat/media/…) needs a session — when given it
     *   is downloaded with an Authorization: Bearer header, otherwise the server returns 401.
     * @param fallbackUrl address tried when the first one cannot be downloaded
     *   (e.g. the original image when there is no thumbnail).
     */
    fun load(
        urlStr: String,
        imageView: ImageView,
        authToken: String? = null,
        fallbackUrl: String? = null,
        maxDim: Int = 800,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        imageView.tag = urlStr

        // Animated GIFs (API 28+): BitmapFactory only ever shows the first
        // frame, so GIFs go through ImageDecoder into an AnimatedImageDrawable.
        // Below API 28 there is no ImageDecoder — the bitmap path below shows
        // the first frame as a still, which is the best that platform can do.
        if (isGifUrl(urlStr) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            loadAnimatedGif(urlStr, imageView, authToken, fallbackUrl, onDone)
            return
        }

        // The same address may be requested at different resolutions (list / full screen).
        val cacheKey = "$urlStr@$maxDim"
        val cached = memoryCache.get(cacheKey)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            onDone?.invoke(true)
            return
        }

        imageView.setImageDrawable(null)

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                downloadBitmap(urlStr, authToken, maxDim)
                    ?: fallbackUrl?.let { downloadBitmap(it, authToken, maxDim) }
            }
            if (bitmap != null) memoryCache.put(cacheKey, bitmap)
            if (imageView.tag == urlStr) {
                if (bitmap != null) imageView.setImageBitmap(bitmap)
                onDone?.invoke(bitmap != null)
            }
        }
    }

    /** True for a .gif address (query strings ignored). */
    fun isGifUrl(urlStr: String): Boolean {
        val clean = urlStr.substringBefore('?').substringBefore('#')
        return clean.endsWith(".gif", ignoreCase = true)
    }

    private fun loadAnimatedGif(
        urlStr: String,
        imageView: ImageView,
        authToken: String?,
        fallbackUrl: String?,
        onDone: ((Boolean) -> Unit)?
    ) {
        imageView.setImageDrawable(null)
        val cachedBytes = gifByteCache.get(urlStr)
        if (cachedBytes != null) {
            setAnimatedGif(imageView, urlStr, cachedBytes, onDone)
            return
        }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { downloadBytes(urlStr, authToken) }
            if (bytes != null) gifByteCache.put(urlStr, bytes)
            if (imageView.tag != urlStr) return@launch
            if (bytes != null) {
                setAnimatedGif(imageView, urlStr, bytes, onDone)
            } else {
                // The GIF itself failed: fall back to the static thumbnail
                // (a GIF decodes to its first frame as a Bitmap).
                val bitmap = withContext(Dispatchers.IO) {
                    fallbackUrl?.let { downloadBitmap(it, authToken, 800) }
                }
                if (imageView.tag == urlStr) {
                    if (bitmap != null) imageView.setImageBitmap(bitmap)
                    onDone?.invoke(bitmap != null)
                }
            }
        }
    }

    private fun setAnimatedGif(imageView: ImageView, urlStr: String, bytes: ByteArray, onDone: ((Boolean) -> Unit)?) {
        if (imageView.tag != urlStr) return
        try {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
            imageView.setImageDrawable(drawable)
            if (drawable is AnimatedImageDrawable) drawable.start()
            onDone?.invoke(true)
        } catch (e: Exception) {
            onDone?.invoke(false)
        }
    }

    private fun downloadBytes(urlStr: String, authToken: String?): ByteArray? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            if (!authToken.isNullOrEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $authToken")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadBitmap(urlStr: String, authToken: String?, maxDim: Int): Bitmap? {
        val bytes = downloadBytes(urlStr, authToken) ?: return null
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            // Scale to max maxDim x maxDim
            var inSampleSize = 1
            if (options.outHeight > maxDim || options.outWidth > maxDim) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= maxDim && (halfWidth / inSampleSize) >= maxDim) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (e: Exception) {
            null
        }
    }
}
