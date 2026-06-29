package com.cncverse.xr3edtv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

class Xr3edtvProvider : MainAPI() {
    override var mainUrl = "https://xys1-depan.pages.dev"
    override var name = "XR3EDTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "id"
    override val hasMainPage = true

    private val jsonUrl = "https://api-tvnetx01.pages.dev/netxtv/depan.js"
    private val ifmUrl = "https://api-tvnetx01.pages.dev/netxtv/ifm.js"

    override val mainPage = listOf(
        MainPageData("Live & TV Channels", "Live & TV Channels")
    )

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val messageDigest = md.digest(input.toByteArray(Charsets.UTF_8))
        val no = java.math.BigInteger(1, messageDigest)
        var hashtext = no.toString(16)
        while (hashtext.length < 32) {
            hashtext = "0$hashtext"
        }
        return hashtext
    }

    private fun decryptAesGcm(encDataB64: String, ivB64: String): String {
        val password = "xys1-gh".toCharArray()
        val salt = "salt123".toByteArray(Charsets.UTF_8)
        
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, 1000, 256)
        val tmp = factory.generateSecret(spec)
        val secretKey = SecretKeySpec(tmp.encoded, "AES")

        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val fullCiphertext = Base64.decode(encDataB64, Base64.NO_WRAP)

        val ciphertext = fullCiphertext.copyOfRange(0, fullCiphertext.size - 16)
        val tag = fullCiphertext.copyOfRange(fullCiphertext.size - 16, fullCiphertext.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        
        val decryptedBytes = cipher.doFinal(fullCiphertext)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val response = app.get("$jsonUrl?v=${System.currentTimeMillis()}", timeout = 15)
        if (response.code != 200) return null
        
        val channelsArray = JSONObject("{\"data\":${response.text}}").getJSONArray("data")
        val searchResps = ArrayList<SearchResponse>()

        for (i in 0 until channelsArray.length()) {
            val item = channelsArray.getJSONObject(i)
            val active = item.optBoolean("active", false)
            if (!active) continue

            val name = item.getString("name")
            val link = item.getString("link")
            val img = item.optString("img", "")

            if (link.startsWith("http") && !link.contains("pages.dev") && !link.contains("netxtv.id")) continue

            searchResps.add(
                newLiveSearchResponse(
                    name,
                    link,
                    TvType.Live
                ) {
                    this.posterUrl = img
                }
            )
        }

        return newHomePageResponse(
            listOf(HomePageList("Live & TV Channels", searchResps)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$jsonUrl?v=${System.currentTimeMillis()}", timeout = 15)
        if (response.code != 200) return emptyList()

        val channelsArray = JSONObject("{\"data\":${response.text}}").getJSONArray("data")
        val results = ArrayList<SearchResponse>()

        for (i in 0 until channelsArray.length()) {
            val item = channelsArray.getJSONObject(i)
            val active = item.optBoolean("active", false)
            if (!active) continue

            val name = item.getString("name")
            val link = item.getString("link")
            val img = item.optString("img", "")

            if (name.contains(query, ignoreCase = true)) {
                results.add(
                    newLiveSearchResponse(
                        name,
                        link,
                        TvType.Live
                    ) {
                        this.posterUrl = img
                    }
                )
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val name = if (url.startsWith("go:")) url.substring(3) else url
        return newLiveStreamLoadResponse(
            name.uppercase(),
            url,
            url
        ) {
            this.plot = "Tonton siaran langsung di XR3EDTV"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val channelKey = if (data.startsWith("go:")) data.substring(3) else data
            val ifmResponse = app.get("$ifmUrl?v=${System.currentTimeMillis()}", timeout = 15)
            if (ifmResponse.code != 200) return false
            
            val mapping = JSONObject(ifmResponse.text)
            if (!mapping.has(channelKey)) return false
            
            val targetUrl = mapping.getString(channelKey)
            val uri = URI(targetUrl)
            val queryMap = uri.query?.split("&")?.associate {
                val parts = it.split("=")
                parts[0] to parts.getOrNull(1)
            } ?: emptyMap()
            
            val channelId = queryMap["id"] ?: channelKey
            
            val workerUrl = "https://bitmovin.03anutv.workers.dev/?id=$channelId&t=${System.currentTimeMillis()}"
            val workerResponse = app.get(workerUrl, timeout = 15)
            if (workerResponse.code != 200) return false
            
            val payload = JSONObject(workerResponse.text)
            val encData = payload.getString("data")
            val encIv = payload.getString("iv")
            
            val decryptedJsonStr = decryptAesGcm(encData, encIv)
            val decrypted = JSONObject(decryptedJsonStr)
            
            val dashUrl = decrypted.getString("dash")
            val drmKeys = decrypted.optString("drm", "")
            
            val headersMap = mapOf(
                "Referer" to "https://lola30es.mpipzni2naturally32kistomach.ru/",
                "Origin" to "https://lola30es.mpipzni2naturally32kistomach.ru",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            callback.invoke(
                newExtractorLink(
                    name = "Source Live",
                    source = "XR3EDTV",
                    url = dashUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P1080.value
                    this.headers = headersMap
                    if (drmKeys.isNotEmpty()) {
                        this.headers = headersMap + mapOf("key" to drmKeys)
                    }
                }
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
