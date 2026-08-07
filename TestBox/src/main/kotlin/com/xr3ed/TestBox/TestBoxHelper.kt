package com.xr3ed.TestBox

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TestBoxHelper – singleton helper untuk header generation dan token management.
 *
 * Mekanisme auto-refresh token via Vibox:
 * - API Vibox (h5-api.aoneroom.com) secara otomatis mengembalikan guest token baru
 *   pada response header `x-user` dan `Set-Cookie: token=` untuk setiap request.
 * - Token berisi field `exp` (expiry ~90 hari) dan `uid` (guest user ID).
 * - Saat token expired atau tidak ada, request berikutnya akan otomatis mendapat
 *   token baru dari response header, yang langsung disimpan untuk reuse.
 */
object TestBoxHelper {

    // HMAC-MD5 secret key (double-encoded Base64)
    private val HMAC_KEY_B64A = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
    private val HMAC_KEY_BYTES: ByteArray by lazy {
        val b64b = Base64.decode(HMAC_KEY_B64A, Base64.DEFAULT)
        val b64Key = String(b64b, Charsets.UTF_8)
        Base64.decode(b64Key, Base64.DEFAULT)
    }

    // Default fallback token hardcoded (guest JWT, valid ~Aug 2026)
    // Format: double-Base64 encoded JWT
    private val DEFAULT_TOKEN_B64 = "ZXlKaGJHY2lPaUpJVXpJMU5pSXNJblI1Y0NJNklrcFhWQ0o5LmV5SnBaQ0k2SWpaaE56QTBNMkZrTWpJeU5HVTJPV1kyTURBeE0yVTFNU0lzSW1saGRDSTZNVGM0TlRjM09UUXhPSDAuQnVqWFBHT2lEekVEMnYxcHYtLWZwSWJ0SGlTRHptOTdGb0VDY3JwckR0UQ=="

    // Token saat ini (volatile untuk thread safety)
    private val _currentToken: AtomicReference<String> = AtomicReference(null)

    private val mapper = jacksonObjectMapper()

    /**
     * Ambil token aktif. Jika tidak ada, gunakan fallback default.
     * Token akan di-refresh otomatis dari response header Vibox.
     */
    val currentToken: String
        get() {
            val saved = _currentToken.get()
            if (saved != null && !isTokenExpiringSoon(saved)) return saved
            // Fallback ke hardcoded token
            return String(
                Base64.decode(DEFAULT_TOKEN_B64, Base64.DEFAULT),
                Charsets.UTF_8
            )
        }

    /**
     * Hapus token tersimpan, paksa pakai fallback atau refresh dari server.
     * Dipanggil ketika server mengembalikan 401.
     */
    fun invalidateToken() {
        _currentToken.set(null)
    }

    /**
     * Update token dari response header `x-user` Vibox.
     * Dipanggil setelah setiap request sukses yang mengandung header x-user.
     *
     * @param xUserHeader value dari response header `x-user`
     */
    fun updateTokenFromXUser(xUserHeader: String?) {
        xUserHeader ?: return
        try {
            val obj = mapper.readTree(xUserHeader)
            val token = obj["token"]?.asText() ?: return
            if (token.isNotBlank()) {
                _currentToken.set(token)
            }
        } catch (_: Exception) {}
    }

    /**
     * Update token dari `Set-Cookie: token=<jwt>` header.
     */
    fun updateTokenFromCookie(cookieHeader: String?) {
        cookieHeader ?: return
        try {
            val match = Regex("""(?:^|;\s*)token=([A-Za-z0-9_\-\.]+)""").find(cookieHeader)
            val token = match?.groupValues?.get(1) ?: return
            if (token.isNotBlank()) {
                _currentToken.set(token)
            }
        } catch (_: Exception) {}
    }

    /**
     * Cek apakah token akan expired dalam 7 hari ke depan.
     * Parse JWT payload untuk ambil field `exp`.
     */
    private fun isTokenExpiringSoon(token: String): Boolean {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return true
            var payload = parts[1]
            // Pad base64url
            val pad = payload.length % 4
            if (pad > 0) payload += "=".repeat(4 - pad)
            val payloadBytes = Base64.decode(payload.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
            val json = mapper.readTree(String(payloadBytes, Charsets.UTF_8))
            val exp = json["exp"]?.asLong() ?: return true
            val nowSeconds = System.currentTimeMillis() / 1000L
            val sevenDaysSec = 7 * 24 * 3600L
            exp - nowSeconds < sevenDaysSec
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Generate `x-client-token` header: `<timestamp>,<md5(reverse(timestamp))>`
     */
    fun generateXClientToken(timestamp: Long): String {
        val tsStr = timestamp.toString()
        val reversed = tsStr.reversed()
        val hash = md5(reversed.toByteArray(Charsets.UTF_8))
        return "$tsStr,$hash"
    }

    /**
     * Generate `x-tr-signature` header menggunakan HMAC-MD5.
     * Format: `<timestamp>|2|<base64(HMAC-MD5(path+querystring+'\n'+timestamp))>`
     */
    fun generateXTrSignature(url: String, body: String? = null, method: String = "GET", timestamp: Long): String {
        val uri = Uri.parse(url)
        val path = uri.path ?: ""
        val queryParamNames = uri.queryParameterNames
        val queryString = if (queryParamNames.isEmpty()) {
            ""
        } else {
            queryParamNames.joinToString("&") { key ->
                uri.getQueryParameters(key).joinToString("&") { value -> "$key=$value" }
            }
        }
        val toSign = buildString {
            append(path)
            if (queryString.isNotEmpty()) {
                append("?")
                append(queryString)
            }
            append("\n")
            append(timestamp)
        }
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(HMAC_KEY_BYTES, "HmacMD5"))
        val sig = Base64.encodeToString(mac.doFinal(toSign.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$timestamp|2|$sig"
    }

    /**
     * Build semua header request untuk Vibox API.
     * Mengunakan x-tr-signature (HMAC-MD5) dan Bearer token dari [currentToken].
     */
    fun getHeaders(url: String, body: String? = null, method: String = "POST"): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val xClientToken = generateXClientToken(timestamp)
        val xTrSignature = generateXTrSignature(url, body, method, timestamp)
        val token = currentToken
        return mapOf(
            "user-agent" to "com.vibox.play/106 (Linux; U; Android 13; en_US; Subsystem for Android(TM); Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
            "accept" to "application/json",
            "content-type" to "application/json; charset=utf-8",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to CLIENT_INFO,
            "x-client-status" to "0",
            "Authorization" to "Bearer $token",
            "x-user" to token
        )
    }

    private val CLIENT_INFO = """{"package_name":"com.vibox.play","version_name":"1.0.6","version_code":106,"os":"android","os_version":"13","install_ch":"ps","device_id":"da2b99c821e6ea023e4be55b54d5f7d8","install_store":"ps","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Windows","model":"Subsystem for Android(TM)","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}"""

    @SuppressLint("DefaultLocale")
    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
