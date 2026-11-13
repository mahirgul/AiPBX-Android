package com.mhrgl.aipbx.util

import java.net.URI

/**
 * Chat attachment addresses. The app sends its session token with every request
 * for one, so the request must go to this PBX only: an absolute address is
 * reduced to its path and query on the configured server.
 */
object MediaUrls {

    fun resolve(baseUrl: String, path: String): String {
        val local = if (path.startsWith("http://") || path.startsWith("https://")) {
            runCatching { URI(path) }.getOrNull()
                ?.let { (it.rawPath ?: "") + (it.rawQuery?.let { q -> "?$q" } ?: "") }
                ?: ""
        } else {
            path
        }
        return baseUrl.trimEnd('/') + "/" + local.trimStart('/')
    }
}
