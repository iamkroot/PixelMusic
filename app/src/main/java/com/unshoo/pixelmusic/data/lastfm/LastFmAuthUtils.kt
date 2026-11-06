package com.unshoo.pixelmusic.data.lastfm

import java.security.MessageDigest

object LastFmAuthUtils {
    const val BASE_URL = "https://ws.audioscrobbler.com/"

    // Standard default credentials for Last.fm API client
    const val DEFAULT_API_KEY = "b25b959554ed76058ac220b7b2e0a026"
    const val DEFAULT_API_SECRET = "425b55975eed76058ac220b7b2e0a027"

    /**
     * Generates the Last.fm MD5 api_sig.
     * 1. Collect all parameters sent in the request EXCEPT 'format' and 'callback'.
     * 2. Sort by parameter name in UTF-8 alphabetical order.
     * 3. Concatenate parameter name + parameter value without separators.
     * 4. Append the api_secret.
     * 5. MD5 hash the UTF-8 bytes to a 32-character lowercase hex string.
     */
    fun generateSignature(params: Map<String, String>, secret: String): String {
        val sortedParams = params.entries
            .filter { it.key != "format" && it.key != "callback" }
            .sortedBy { it.key }

        val sb = StringBuilder()
        for ((key, value) in sortedParams) {
            sb.append(key).append(value)
        }
        sb.append(secret)

        return md5(sb.toString())
    }

    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
