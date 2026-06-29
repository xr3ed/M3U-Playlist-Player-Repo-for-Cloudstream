package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import java.net.URLDecoder

class EventProvider : MainAPI() {
    override var mainUrl = "https://wc26.netxtv.id"
    override var name = "Event"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("World Cup 2026", "worldcup")
    )

    private val defaultLogo = "https://raw.githubusercontent.com/xr3ed/Auto-IPTV/main/logo/fifa.png"

    private fun hexToBase64Url(str: String): String {
        val clean = str.replace(" ", "").trim()
        val isHex = clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } && clean.length % 2 == 0
        if (!isHex || clean.isEmpty()) return clean
        return try {
            val bytes = ByteArray(clean.length / 2)
            for (i in bytes.indices) {
                bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        } catch (e: Exception) {
            clean
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = ArrayList<SearchResponse>()
        try {
            // Mengambil channel.js dari NetxTV Pages API
            val jsUrl = "https://api-tvnetx01.pages.dev/netxtv/channel.js"
            val response = app.get(jsUrl, timeout = 15).text
            
            // Parsing manual data JSON yang dikelilingi metadata atau langsung sebagai JSON string
            val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
            val root = JSONObject(jsonStr)
            val channelsObj = root.optJSONObject("channels") ?: JSONObject()
            val groupsObj = root.optJSONObject("groups") ?: JSONObject()

            // Dapatkan list id channel untuk piala dunia (ucl1 sampai ucl20, wc17, dsb)
            // Di sini kita ambil semua channel yang memiliki keyword 'ucl', 'wc', atau 'fifa'
            val targetChannelIds = mutableSetOf<String>()
            val keys = groupsObj.keys()
            while (keys.hasNext()) {
                val groupKey = keys.next()
                if (groupKey.contains("ucl") || groupKey.contains("wc") || groupKey.contains("timnas")) {
                    val arr = groupsObj.optJSONArray(groupKey)
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            targetChannelIds.add(arr.optString(i))
                        }
                    }
                }
            }

            // Fallback: tambahkan channel bernomor fifa secara langsung
            for (i in 1..20) {
                targetChannelIds.add("fifa$i")
            }

            // Urutkan dan filter agar unik
            val addedUrls = mutableSetOf<String>()
            for (chId in targetChannelIds) {
                val ch = channelsObj.optJSONObject(chId) ?: continue
                val chName = ch.optString("name", chId)
                val chImg = ch.optString("img", defaultLogo)
                val chHref = ch.optString("href")

                if (chHref.isNullOrBlank()) continue
                
                // Konversi href kustom netxtv ke format URL penuh
                // misal 'go:go3x6' atau direct link player
                val streamUrl = if (chHref.startsWith("go:")) {
                    "https://wc26.netxtv.id/?id=jadwal#$chHref"
                } else {
                    "https://wc26.netxtv.id/?id=jadwal#go:$chId"
                }

                if (!addedUrls.contains(streamUrl)) {
                    addedUrls.add(streamUrl)
                    list.add(
                        newLiveSearchResponse(
                            chName,
                            streamUrl,
                            TvType.Live
                        ) {
                            this.posterUrl = chImg
                        }
                    )
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (list.isEmpty()) {
            list.add(
                newLiveSearchResponse(
                    "Sedang tidak ada pertandingan piala dunia aktif saat ini",
                    "https://wc26.netxtv.id/?id=jadwal#none",
                    TvType.Live
                ) {
                    this.posterUrl = defaultLogo
                }
            )
        }

        return newHomePageResponse(
            listOf(HomePageList("World Cup 2026", list)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val list = ArrayList<SearchResponse>()
        try {
            val jsUrl = "https://api-tvnetx01.pages.dev/netxtv/channel.js"
            val response = app.get(jsUrl, timeout = 15).text
            val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
            val root = JSONObject(jsonStr)
            val channelsObj = root.optJSONObject("channels") ?: JSONObject()
            val groupsObj = root.optJSONObject("groups") ?: JSONObject()

            val targetChannelIds = mutableSetOf<String>()
            val keys = groupsObj.keys()
            while (keys.hasNext()) {
                val groupKey = keys.next()
                if (groupKey.contains("ucl") || groupKey.contains("wc") || groupKey.contains("timnas")) {
                    val arr = groupsObj.optJSONArray(groupKey)
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            targetChannelIds.add(arr.optString(i))
                        }
                    }
                }
            }

            for (chId in targetChannelIds) {
                val ch = channelsObj.optJSONObject(chId) ?: continue
                val chName = ch.optString("name", chId)
                val chImg = ch.optString("img", defaultLogo)
                val chHref = ch.optString("href")

                if (chHref.isNullOrBlank()) continue
                if (!chName.contains(query, ignoreCase = true)) continue

                val streamUrl = if (chHref.startsWith("go:")) {
                    "https://wc26.netxtv.id/?id=jadwal#$chHref"
                } else {
                    "https://wc26.netxtv.id/?id=jadwal#go:$chId"
                }

                list.add(
                    newLiveSearchResponse(
                        chName,
                        streamUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = chImg
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse {
        var title = "Live Stream World Cup"
        var resolvedUrl = url
        var debugDrmInfo = "Tidak menggunakan DRM / Player Bitmovin"
        
        try {
            if (url.contains("#go:")) {
                var code = url.substringAfter("#go:")
                title = "Live Event - $code"
                
                val jsUrl = "https://api-tvnetx01.pages.dev/netxtv/channel.js"
                val response = app.get(jsUrl, timeout = 15).text
                val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
                val root = JSONObject(jsonStr)
                val channelsObj = root.optJSONObject("channels") ?: JSONObject()
                
                var resolved = false
                var depth = 0
                while (!resolved && depth < 5) {
                    val channelData = channelsObj.optJSONObject(code)
                    if (channelData != null) {
                        val chName = channelData.optString("name")
                        if (!chName.isNullOrBlank()) {
                            title = chName
                        }
                        val href = channelData.optString("href")
                        if (!href.isNullOrBlank()) {
                            if (href.startsWith("go:")) {
                                code = href.substringAfter("go:")
                                depth++
                            } else {
                                resolvedUrl = href
                                resolved = true
                            }
                        } else {
                            resolved = true
                        }
                    } else {
                        resolvedUrl = "https://xys1-player.pages.dev/bitmovin/?id=$code"
                        resolved = true
                    }
                }
                
                if (resolvedUrl.contains("xys1-player.pages.dev/bitmovin/")) {
                    val idVal = resolvedUrl.substringAfter("?id=").substringBefore("&")
                    debugDrmInfo = "Mencoba fetch worker untuk id: $idVal...\n"
                    try {
                        val workerUrl = "https://bitmovin.03anutv.workers.dev/?id=$idVal&t=${System.currentTimeMillis()}"
                        val responseText = app.get(workerUrl, timeout = 10).text
                        debugDrmInfo += "Respon worker didapatkan (${responseText.length} chars)\n"
                        val responseJson = JSONObject(responseText.trim())
                        val ivB64 = responseJson.optString("iv")
                        val dataB64 = responseJson.optString("data")
                        
                        if (!ivB64.isNullOrEmpty() && !dataB64.isNullOrEmpty()) {
                            debugDrmInfo += "Mencoba AES-GCM decryption...\n"
                            val password = "xys1-gh"
                            val salt = "salt123"
                            val iterations = 1000
                            val keySize = 256
                            
                            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                            val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), iterations, keySize)
                            val tmp = factory.generateSecret(spec)
                            val secretKey = javax.crypto.spec.SecretKeySpec(tmp.encoded, "AES")
                            
                            val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
                            val combinedCipher = android.util.Base64.decode(dataB64, android.util.Base64.NO_WRAP)
                            
                            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
                            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec)
                            
                            val decryptedBytes = cipher.doFinal(combinedCipher)
                            val decryptedText = String(decryptedBytes, Charsets.UTF_8)
                            val decryptedJson = JSONObject(decryptedText)
                            
                            val dashUrl = decryptedJson.optString("dash")
                            val drmStr = decryptedJson.optString("drm")
                            if (!dashUrl.isNullOrBlank() && !drmStr.isNullOrBlank()) {
                                debugDrmInfo += "DECRYPT SUCCESS!\n• DASH: $dashUrl\n• DRM: $drmStr"
                            } else {
                                debugDrmInfo += "DECRYPTED JSON but no dash/drm found: $decryptedText"
                            }
                        } else {
                            debugDrmInfo += "Worker response error/empty"
                        }
                    } catch (err: Exception) {
                        debugDrmInfo += "Error decryption: ${err.message}"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return newLiveStreamLoadResponse(
            title,
            url,
            url
        ) {
            this.posterUrl = defaultLogo
            this.plot = "DEBUG INFO (Silakan foto/infokan ini jika error):\n" +
                        "• Link Asli: $url\n" +
                        "• Resolved Link: $resolvedUrl\n" +
                        "• DRM Status:\n$debugDrmInfo\n\n" +
                        "Tonton siaran langsung Piala Dunia 2026 gratis via plugin Event."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // logic pemutaran / ekstrak stream
        // format data: url yang dikirim dari load
        try {
            var targetUrl = data
            if (data.contains("#go:")) {
                var code = data.substringAfter("#go:")
                val jsUrl = "https://api-tvnetx01.pages.dev/netxtv/channel.js"
                val response = app.get(jsUrl, timeout = 15).text
                val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
                val root = JSONObject(jsonStr)
                val channelsObj = root.optJSONObject("channels") ?: JSONObject()
                
                var resolved = false
                var depth = 0
                while (!resolved && depth < 5) {
                    val channelData = channelsObj.optJSONObject(code)
                    if (channelData != null) {
                        val href = channelData.optString("href")
                        if (!href.isNullOrBlank()) {
                            if (href.startsWith("go:")) {
                                code = href.substringAfter("go:")
                                depth++
                            } else {
                                targetUrl = href
                                resolved = true
                            }
                        } else {
                            resolved = true
                        }
                    } else {
                        // Jika tidak ada di channels, mungkin alias langsung
                        targetUrl = "https://xys1-player.pages.dev/bitmovin/?id=$code"
                        resolved = true
                    }
                }
            }

            android.util.Log.d("EventProvider", "Resolved targetUrl: $targetUrl")

            // Jika targetUrl merupakan bitmovin player (misal: https://xys1-player.pages.dev/bitmovin/?id=...)
            // Kita bypass dan decode DRM ClearKey-nya agar dapat dimainkan secara native di Cloudstream.
            if (targetUrl.contains("xys1-player.pages.dev/bitmovin/")) {
                val idVal = targetUrl.substringAfter("?id=").substringBefore("&")
                android.util.Log.d("EventProvider", "Resolving bitmovin id: $idVal")
                
                try {
                    val workerUrl = "https://bitmovin.03anutv.workers.dev/?id=$idVal&t=${System.currentTimeMillis()}"
                    val responseText = app.get(workerUrl, timeout = 10).text
                    val responseJson = JSONObject(responseText.trim())
                    
                    val ivB64 = responseJson.optString("iv")
                    val dataB64 = responseJson.optString("data")
                    
                    if (!ivB64.isNullOrEmpty() && !dataB64.isNullOrEmpty()) {
                        // Dekripsi data AES-GCM secara native di Kotlin
                        val password = "xys1-gh"
                        val salt = "salt123"
                        val iterations = 1000
                        val keySize = 256
                        
                        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), iterations, keySize)
                        val tmp = factory.generateSecret(spec)
                        val secretKey = javax.crypto.spec.SecretKeySpec(tmp.encoded, "AES")
                        
                        val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
                        val combinedCipher = android.util.Base64.decode(dataB64, android.util.Base64.NO_WRAP)
                        
                        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec)
                        
                        val decryptedBytes = cipher.doFinal(combinedCipher)
                        val decryptedText = String(decryptedBytes, Charsets.UTF_8)
                        val decryptedJson = JSONObject(decryptedText)
                        
                        val dashUrl = decryptedJson.optString("dash")
                        val drmStr = decryptedJson.optString("drm")
                        
                        if (!dashUrl.isNullOrBlank() && !drmStr.isNullOrBlank()) {
                            val parts = drmStr.split(":")
                            if (parts.size == 2) {
                                val keyId = parts[0].trim()
                                val keyValue = parts[1].trim()
                                
                                val clearkeyKid = hexToBase64Url(keyId)
                                val clearkeyKey = hexToBase64Url(keyValue)
                                
                                val headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                    "Referer" to "https://xys1-player.pages.dev/",
                                    "Origin" to "https://xys1-player.pages.dev"
                                )
                                
                                callback.invoke(
                                    newDrmExtractorLink(
                                        this.name,
                                        this.name,
                                        dashUrl,
                                        ExtractorLinkType.DASH,
                                        CLEARKEY_UUID
                                    ) {
                                        quality = Qualities.Unknown.value
                                        this.headers = headers
                                        kty = "oct"
                                        kid = clearkeyKid
                                        key = clearkeyKey
                                    }
                                )
                                return true
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EventProvider", "Failed to decrypt bitmovin source", e)
                }
                
                // Fallback default stream
                targetUrl = "https://stream.netxtv.id/live/$idVal/index.m3u8"
            }

            android.util.Log.d("EventProvider", "Final targetUrl to stream: $targetUrl")

            val isFlv = targetUrl.contains(".flv", ignoreCase = true) || targetUrl.contains("flv", ignoreCase = true)
            val url = if (isFlv) {
                if (!targetUrl.contains("#")) "$targetUrl#.flv" else targetUrl
            } else if (!targetUrl.contains(".m3u8", ignoreCase = true) && 
                          !targetUrl.contains("m3u8", ignoreCase = true) && 
                          !targetUrl.contains(".mpd", ignoreCase = true) && 
                          !targetUrl.contains("mpd", ignoreCase = true) && 
                          !targetUrl.contains("#") && 
                          (targetUrl.contains("live.php") || targetUrl.contains("play.php") || targetUrl.contains("/live/"))) {
                "$targetUrl#.m3u8"
            } else {
                targetUrl
            }

            val isM3u8 = url.contains(".m3u8", ignoreCase = true) || url.contains("m3u8", ignoreCase = true)
            val isDash = url.contains(".mpd", ignoreCase = true) || url.contains("mpd", ignoreCase = true)
            val type = when {
                isM3u8 -> ExtractorLinkType.M3U8
                isDash -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to "https://netxtv.pages.dev/"
            )

            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    url,
                    type
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
