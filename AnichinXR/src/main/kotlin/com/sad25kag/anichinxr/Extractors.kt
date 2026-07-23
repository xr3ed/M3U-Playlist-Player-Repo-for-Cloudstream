package com.sad25kag.anichinxr

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.VidhideExtractor

class Morencius : VidhideExtractor() {
    override var name = "Vidhide"
    override var mainUrl = "https://morencius.com"
}

open class TurboVIP : ExtractorApi() {
    override val name = "TurboVIP"
    override val mainUrl = "https://turbovip.site"
    override val requiresReferer = true

    private val cleanClient = app.baseClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", referer ?: url)
            .build()
        val responseBody = cleanClient.newCall(request).execute().body?.string() ?: ""
        val document = org.jsoup.Jsoup.parse(responseBody)
        val script = document.select("script").map { it.html() }.firstOrNull { it.contains("urlPlay") } ?: return
        val videoUrl = Regex("urlPlay\\s*=\\s*'([^']*)'").find(script)?.groupValues?.getOrNull(1) ?: return
        if (videoUrl.isNotBlank()) {
            callback.invoke(newExtractorLink(
                source = this.name,
                name = this.name,
                url  = videoUrl,
                type = if (videoUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to url
                )
            })
        }
    }
}

class TurboVidHls : ExtractorApi() {
    override val name = "TurboVIP"
    override val mainUrl = "https://turbovidhls.com"
    override val requiresReferer = true

    private val cleanClient = app.baseClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", referer ?: url)
            .build()
        val responseBody = cleanClient.newCall(request).execute().body?.string() ?: ""
        val document = org.jsoup.Jsoup.parse(responseBody)
        val script = document.select("script").map { it.html() }.firstOrNull { it.contains("urlPlay") } ?: return
        val videoUrl = Regex("urlPlay\\s*=\\s*'([^']*)'").find(script)?.groupValues?.getOrNull(1) ?: return
        if (videoUrl.isNotBlank()) {
            callback.invoke(newExtractorLink(
                source = this.name,
                name = this.name,
                url  = videoUrl,
                type = if (videoUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to url
                )
            })
        }
    }
}

class RPMShare : ExtractorApi() {
    override val name = "RPMShare"
    override val mainUrl = "https://anichin.rpmvid.com"
    override val requiresReferer = true

    private val cleanClient = app.baseClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()
    private val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = if (url.contains("/embed/")) {
            url.substringAfter("/embed/").substringBefore("?").substringBefore("/")
        } else {
            url.substringAfter("#", "").substringBefore("&")
        }
        if (id.isBlank()) return

        val apiUrl = "https://anichin.rpmvid.com/api/v1/video?id=$id"
        val request = okhttp3.Request.Builder()
            .url(apiUrl)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Referer", "https://anichin.rpmvid.com/")
            .header("Accept", "application/json,text/plain,*/*")
            .build()

        val response = cleanClient.newCall(request).execute().body?.string() ?: ""

        val decrypted = runCatching {
            decryptAesCbc(response, "kiemtienmua911ca", "1234567890oiuytr")
        }.getOrNull() ?: return

        val json = runCatching { org.json.JSONObject(decrypted) }.getOrNull() ?: return
        val source = json.optString("source").replace("\\/", "/")
        val cfNative = json.optString("cfNative").replace("\\/", "/")

        if (cfNative.isNotBlank()) {
            callback.invoke(newExtractorLink(
                source = this.name,
                name = "${this.name} [Cloudflare]",
                url = cfNative,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to url
                )
            })
        }
        if (source.isNotBlank()) {
            callback.invoke(newExtractorLink(
                source = this.name,
                name = "${this.name} [Direct]",
                url = source,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to url
                )
            })
        }
    }

    private fun decryptAesCbc(ciphertextHex: String, keyStr: String, ivStr: String): String {
        val keySpec = javax.crypto.spec.SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = javax.crypto.spec.IvParameterSpec(ivStr.toByteArray(Charsets.UTF_8))
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val encryptedBytes = hexToBytes(ciphertextHex)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun hexToBytes(hexString: String): ByteArray {
        val len = hexString.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hexString[i], 16) shl 4) + Character.digit(hexString[i + 1], 16)).toByte()
        }
        return data
    }
}
