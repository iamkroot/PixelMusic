package com.unshoo.pixelmusic.data.lastfm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LastFmAuthUtilsTest {

    @Test
    fun `generateSignature correctly sorts parameters and computes MD5`() {
        val params = mapOf(
            "method" to "auth.getMobileSession",
            "username" to "testUser",
            "password" to "testPass",
            "api_key" to "myApiKey",
            "format" to "json" // Should be ignored in signature
        )
        val secret = "mySecret"

        // Sorted: api_key, method, password, username
        // String: api_keymyApiKeymethodauth.getMobileSessionpasswordtestPassusernametestUsermySecret
        val expectedString = "api_keymyApiKeymethodauth.getMobileSessionpasswordtestPassusernametestUsermySecret"
        val expectedMd5 = LastFmAuthUtils.md5(expectedString)

        val signature = LastFmAuthUtils.generateSignature(params, secret)
        assertEquals(expectedMd5, signature)
    }

    @Test
    fun `generateSignature excludes callback and format`() {
        val paramsWithFormatAndCallback = mapOf(
            "track" to "Song Title",
            "artist" to "Artist Name",
            "format" to "json",
            "callback" to "myCallback"
        )
        val cleanParams = mapOf(
            "track" to "Song Title",
            "artist" to "Artist Name"
        )
        val secret = "secret123"

        val sig1 = LastFmAuthUtils.generateSignature(paramsWithFormatAndCallback, secret)
        val sig2 = LastFmAuthUtils.generateSignature(cleanParams, secret)

        assertEquals(sig2, sig1)
    }

    @Test
    fun `md5 produces standard 32 char lowercase hex`() {
        val hash = LastFmAuthUtils.md5("The quick brown fox jumps over the lazy dog")
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", hash)
    }
}
