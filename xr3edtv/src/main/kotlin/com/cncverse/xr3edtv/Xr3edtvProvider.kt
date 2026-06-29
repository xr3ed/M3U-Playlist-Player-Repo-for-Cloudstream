package com.cncverse.xr3edtv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URI
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
    private val menuUrl = "https://api-tvnetx01.pages.dev/netxtv/menu.js"

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

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        
        val decryptedBytes = cipher.doFinal(fullCiphertext)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    // Pemuatan Halaman Depan Cloudstream yang Benar
    // Menampilkan langsung Kategori (sebagai Baris) dan saluran TV di dalamnya (sebagai Item)
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // 1. Ambil kategori utama dari depan.js
        val depanResponse = app.get("$jsonUrl?v=${System.currentTimeMillis()}", timeout = 15)
        if (depanResponse.code != 200) return null
        
        val categoriesArray = JSONObject("{\"data\":${depanResponse.text}}").getJSONArray("data")
        
        // 2. Ambil seluruh data channels & groups dari menu.js
        val menuResponse = app.get("$menuUrl?t=${System.currentTimeMillis()}", timeout = 15)
        if (menuResponse.code != 200) return null
        
        val menuData = JSONObject(menuResponse.text)
        val groupsObj = menuData.getJSONObject("groups")
        val channelsObj = menuData.getJSONObject("channels")
        
        val homePageLists = ArrayList<HomePageList>()

        // 3. Iterasi setiap kategori di depan.js untuk dijadikan baris/kategori utama di beranda Cloudstream
        for (i in 0 until categoriesArray.length()) {
            val catItem = categoriesArray.getJSONObject(i)
            val active = catItem.optBoolean("active", false)
            if (!active) continue

            val catName = catItem.getString("name")
            val catLink = catItem.getString("link")

            // Kita hanya memuat kategori yang mengarah ke internal group ("go:...")
            if (!catLink.startsWith("go:")) continue
            val groupId = catLink.substring(3)

            if (!groupsObj.has(groupId)) continue
            val channelIds = groupsObj.getJSONArray(groupId)
            val searchResps = ArrayList<SearchResponse>()

            // Penuhi item saluran TV di dalam kategori ini
            for (j in 0 until channelIds.length()) {
                val chId = channelIds.getString(j)
                if (!channelsObj.has(chId)) continue

                val channel = channelsObj.getJSONObject(chId)
                val chName = channel.getString("name")
                val chImg = channel.optString("img", "")
                val chHref = channel.getString("href")

                searchResps.add(
                    newLiveSearchResponse(
                        chName,
                        chHref, // Menggunakan link putar asli (misal go:rctivp) agar langsung memicu pemutaran
                        TvType.Live
                    ) {
                        this.posterUrl = chImg
                    }
                )
            }

            if (searchResps.isNotEmpty()) {
                homePageLists.add(HomePageList(catName, searchResps))
            }
        }

        return if (homePageLists.isNotEmpty()) {
            newHomePageResponse(homePageLists, hasNext = false)
        } else {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$menuUrl?t=${System.currentTimeMillis()}", timeout = 15)
        if (response.code != 200) return emptyList()

        val data = JSONObject(response.text)
        val channelsObj = data.getJSONObject("channels")
        val results = ArrayList<SearchResponse>()

        for (key in channelsObj.keys()) {
            val channel = channelsObj.getJSONObject(key)
            val name = channel.getString("name")
            val img = channel.optString("img", "")
            val href = channel.getString("href")

            if (name.contains(query, ignoreCase = true)) {
                results.add(
                    newLiveSearchResponse(
                        name,
                        href,
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
            
            // 1. Dapatkan mapping dari ifmUrl
            val ifmResponse = app.get("$ifmUrl?v=${System.currentTimeMillis()}", timeout = 15)
            if (ifmResponse.code != 200) return false
            
            val mapping = JSONObject(ifmResponse.text)
            if (!mapping.has(channelKey)) return false
            
            val targetUrl = mapping.getString(channelKey)
            
            // Ambil id dari parameter query URL player (misal: ?id=rcti)
            val uri = URI(targetUrl)
            val queryMap = uri.query?.split("&")?.associate {
                val parts = it.split("=")
                parts[0] to parts.getOrNull(1)
            } ?: emptyMap()
            
            val channelId = queryMap["id"] ?: channelKey
            
            // 2. Ambil payload terenkripsi dari workers API
            val workerUrl = "https://bitmovin.03anutv.workers.dev/?id=$channelId&t=${System.currentTimeMillis()}"
            val workerResponse = app.get(workerUrl, timeout = 15)
            if (workerResponse.code != 200) return false
            
            val payload = JSONObject(workerResponse.text)
            val encData = payload.getString("data")
            val encIv = payload.getString("iv")
            
            // 3. Dekripsi payload
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
