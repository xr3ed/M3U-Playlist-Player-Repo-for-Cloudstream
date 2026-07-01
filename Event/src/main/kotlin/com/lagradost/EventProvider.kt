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
import android.content.Context

class EventProvider(val context: Context) : MainAPI() {
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

    private suspend fun getDrmDashManifestUrl(originalUrl: String, kidHex: String, headers: Map<String, String>): String {
        android.util.Log.d("EventProvider", "getDrmDashManifestUrl start: url=$originalUrl, kidHex=$kidHex")
        try {
            val response = app.get(originalUrl, headers = headers, timeout = 25)
            val manifestXml = response.text
            android.util.Log.d("EventProvider", "getDrmDashManifestUrl raw manifest downloaded. Length: ${manifestXml.length}")
            
            var modifiedXml = manifestXml
            
            // 1. Hapus seluruh blok ContentProtection Widevine (urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed)
            modifiedXml = modifiedXml.replace(Regex("""<ContentProtection[^>]*schemeIdUri=\s*["']urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed["'][^>]*>([\s\S]*?)</ContentProtection>""", RegexOption.IGNORE_CASE), "")
            modifiedXml = modifiedXml.replace(Regex("""<ContentProtection[^>]*schemeIdUri=\s*["']urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed["'][^>]*/\s*>""", RegexOption.IGNORE_CASE), "")
            
            // 2. Hapus seluruh blok ContentProtection PlayReady (urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95)
            modifiedXml = modifiedXml.replace(Regex("""<ContentProtection[^>]*schemeIdUri=\s*["']urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95["'][^>]*>([\s\S]*?)</ContentProtection>""", RegexOption.IGNORE_CASE), "")
            modifiedXml = modifiedXml.replace(Regex("""<ContentProtection[^>]*schemeIdUri=\s*["']urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95["'][^>]*/\s*>""", RegexOption.IGNORE_CASE), "")
            
            // Tentukan BaseURL absolut dan query parameters token asli
            val finalUrl = response.url
            val queryParams = if (finalUrl.contains("?")) finalUrl.substringAfter("?") else ""
            val baseUrlString = if (finalUrl.contains("?")) finalUrl.substringBefore("?") else finalUrl
            val absoluteBaseUrl = baseUrlString.substringBeforeLast("/") + "/"
            
            // Sisipkan BaseURL absolut di dalam <Period> atau <MPD>
            if (!modifiedXml.contains("<BaseURL>") && !modifiedXml.contains("<BaseURL ")) {
                val periodIdx = modifiedXml.indexOf("<Period")
                if (periodIdx != -1) {
                    val insertIdx = modifiedXml.indexOf(">", periodIdx) + 1
                    modifiedXml = modifiedXml.substring(0, insertIdx) + "\n<BaseURL>$absoluteBaseUrl</BaseURL>\n" + modifiedXml.substring(insertIdx)
                } else {
                    val mpdIdx = modifiedXml.indexOf("<MPD")
                    if (mpdIdx != -1) {
                        val insertIdx = modifiedXml.indexOf(">", mpdIdx) + 1
                        modifiedXml = modifiedXml.substring(0, insertIdx) + "\n<BaseURL>$absoluteBaseUrl</BaseURL>\n" + modifiedXml.substring(insertIdx)
                    }
                }
            }
            
            // 3. Cek apakah manifest bersih ini sudah memuat ClearKey secara native
            val hasClearKey = modifiedXml.contains("e2719d58-a985-b3c9-781a-b030af78d30e", ignoreCase = true) ||
                              modifiedXml.contains("cenc:default_KID", ignoreCase = true)
            
            if (!hasClearKey) {
                // Jika tidak memiliki ClearKey, sisipkan ContentProtection ClearKey
                val cleanKid = kidHex.replace("-", "").trim()
                val uuidKid = if (cleanKid.length == 32) {
                    "${cleanKid.substring(0, 8)}-${cleanKid.substring(8, 12)}-${cleanKid.substring(12, 16)}-${cleanKid.substring(16, 20)}-${cleanKid.substring(20)}"
                } else {
                    cleanKid
                }
                
                val contentProtectionXml = """
                    <ContentProtection schemeIdUri="urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e">
                        <cenc:default_KID xmlns:cenc="urn:mpeg:cenc:2013">$uuidKid</cenc:default_KID>
                    </ContentProtection>
                """.trimIndent()
                
                val parts = modifiedXml.split("<AdaptationSet")
                if (parts.size > 1) {
                    val sb = StringBuilder(parts[0])
                    for (i in 1 until parts.size) {
                        sb.append("<AdaptationSet")
                        val rest = parts[i]
                        val tagEndIdx = rest.indexOf(">")
                        if (tagEndIdx != -1) {
                            sb.append(rest.substring(0, tagEndIdx + 1))
                            sb.append("\n$contentProtectionXml\n")
                            sb.append(rest.substring(tagEndIdx + 1))
                        } else {
                            sb.append(rest)
                        }
                    }
                    modifiedXml = sb.toString()
                }
            }
            
            val cleanKid = kidHex.replace("-", "").trim()
            val tempFile = java.io.File(context.cacheDir, "manifest_${cleanKid}.mpd")
            tempFile.writeText(modifiedXml, Charsets.UTF_8)
            val resultUrl = tempFile.toURI().toString()
            android.util.Log.d("EventProvider", "getDrmDashManifestUrl success! Clean manifest written to: $resultUrl")
            return resultUrl
        } catch (e: Exception) {
            android.util.Log.e("EventProvider", "getDrmDashManifestUrl FATAL EXCEPTION: ${e.message}", e)
            return originalUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = ArrayList<SearchResponse>()
        try {
            // Mengambil jadwal.js
            val jadwalUrl = "https://api-tvnetx01.pages.dev/netxtv/jadwal.js"
            val response = app.get(jadwalUrl, timeout = 15).text
            val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
            val root = JSONObject(jsonStr)
            val scheduleArray = root.optJSONArray("schedule")
            
            if (scheduleArray != null) {
                for (i in 0 until scheduleArray.length()) {
                    val group = scheduleArray.optJSONObject(i) ?: continue
                    if (!group.optBoolean("active", true)) continue
                    
                    val groupTitle = group.optString("title", "EVENT")
                    // Filter khusus pertandingan Piala Dunia
                    if (!groupTitle.contains("WORLD CUP", ignoreCase = true) && !groupTitle.contains("FIFA", ignoreCase = true)) {
                        continue
                    }
                    
                    val linkVal = group.optString("link", "")
                    val cleanGroupId = if (linkVal.startsWith("go:")) linkVal.substringAfter("go:") else linkVal
                    
                    val matchesArray = group.optJSONArray("matches")
                    if (matchesArray != null) {
                        for (j in 0 until matchesArray.length()) {
                            val match = matchesArray.optJSONObject(j) ?: continue
                            if (!match.optBoolean("active", true)) continue
                            
                            val time = match.optString("time", "")
                            val home = match.optString("home", "")
                            val away = match.optString("away", "")
                            val nameVal = match.optString("name", "")
                            
                            val displayTitle = if (!home.isNullOrBlank() && !away.isNullOrBlank()) {
                                if (!time.isNullOrBlank()) "[$time] $home vs $away" else "$home vs $away"
                            } else if (!nameVal.isNullOrBlank()) {
                                if (!time.isNullOrBlank()) "[$time] $nameVal" else nameVal
                            } else {
                                continue
                            }
                            
                            // Link format: kita pasangkan id group sebagai hash anchor
                            val streamUrl = "https://wc26.netxtv.id/?id=jadwal#match:$cleanGroupId"
                            list.add(
                                newLiveSearchResponse(
                                    displayTitle,
                                    streamUrl,
                                    TvType.Live
                                ) {
                                    this.posterUrl = defaultLogo
                                }
                            )
                        }
                    }
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
        val episodeList = ArrayList<Episode>()
        
        try {
            if (url.contains("#match:")) {
                val groupId = url.substringAfter("#match:")
                title = "Live Match - $groupId"
                
                // Ambil channel.js
                val jsUrl = "https://api-tvnetx01.pages.dev/netxtv/channel.js"
                val response = app.get(jsUrl, timeout = 15).text
                val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
                val root = JSONObject(jsonStr)
                val channelsObj = root.optJSONObject("channels") ?: JSONObject()
                val groupsObj = root.optJSONObject("groups") ?: JSONObject()
                
                // Cari group yang mendekati, misal jika groupId adalah 'ucl', 
                // kita ambil channel dari grup ucl1, ucl2, ucl3, ucl4, ucl5, ucl6, ucl10, dsb.
                // Jika exact match, kita ambil grup itu saja.
                val targetGroups = mutableListOf<String>()
                val keys = groupsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.equals(groupId, ignoreCase = true)) {
                        targetGroups.add(k)
                    }
                }
                if (targetGroups.isEmpty()) {
                    val keys2 = groupsObj.keys()
                    while (keys2.hasNext()) {
                        val k = keys2.next()
                        if (k.startsWith(groupId, ignoreCase = true) || k.contains(groupId, ignoreCase = true)) {
                            targetGroups.add(k)
                            // Membatasi hanya mengambil 1 grup fuzzy pertama (misalnya ucl1) agar tidak menumpuk ucl1, ucl2, ucl3, dsb. yang melipatgandakan episode.
                            break
                        }
                    }
                }
                
                val addedChannels = mutableSetOf<String>()
                var epIndex = 1
                for (gId in targetGroups) {
                    val arr = groupsObj.optJSONArray(gId) ?: continue
                    for (i in 0 until arr.length()) {
                        val chId = arr.optString(i)
                        if (chId.equals("vvip", ignoreCase = true) || chId.equals("replay", ignoreCase = true) || chId.equals("wc-jadwal", ignoreCase = true)) continue
                        if (addedChannels.contains(chId)) continue
                        addedChannels.add(chId)
                        
                        val chData = channelsObj.optJSONObject(chId) ?: continue
                        val chName = chData.optString("name", chId)
                        val chImg = chData.optString("img", defaultLogo)
                        val chHref = chData.optString("href")
                        
                        val linkUrl = if (!chHref.isNullOrBlank()) {
                            if (chHref.startsWith("go:")) "https://wc26.netxtv.id/?id=jadwal#$chHref" else chHref
                        } else {
                            "https://wc26.netxtv.id/?id=jadwal#go:$chId"
                        }
                        
                        episodeList.add(
                            newEpisode(linkUrl) {
                                this.name = chName
                                this.episode = epIndex++
                                this.posterUrl = chImg
                            }
                        )
                    }
                }
                
                // Fallback default jika list kosong
                if (episodeList.isEmpty()) {
                    episodeList.add(
                        newEpisode("https://wc26.netxtv.id/?id=jadwal#go:tvri2") {
                            this.name = "TVRI+"
                            this.episode = 1
                        }
                    )
                }
                
                return newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    episodeList
                ) {
                    this.posterUrl = defaultLogo
                    this.plot = "Pilih server saluran streaming di bawah untuk menonton pertandingan ini secara langsung."
                }
            } else {
                var code = if (url.contains("#go:")) url.substringAfter("#go:") else url.substringAfter("#")
                if (code.contains("?")) {
                    code = code.substringBefore("?")
                }
                if (code.contains("&")) {
                    code = code.substringBefore("&")
                }
                title = "Live Stream - $code"
                val linkUrl = "https://wc26.netxtv.id/?id=jadwal#go:$code"
                return newLiveStreamLoadResponse(
                    title,
                    linkUrl,
                    linkUrl
                ) {
                    this.posterUrl = defaultLogo
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
                var code = data.substringAfter("#go:").substringBefore("#").substringBefore("&")
                if (code.contains("?")) {
                    code = code.substringBefore("?")
                }
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
                                 var nextCode = href.substringAfter("go:").substringBefore("#").substringBefore("&")
                                 if (nextCode.contains("?")) {
                                     nextCode = nextCode.substringBefore("?")
                                 }
                                 if (nextCode == code) {
                                     targetUrl = "https://xys1-2-player.pages.dev/bitmovin/?id=$code"
                                     resolved = true
                                 } else {
                                     code = nextCode
                                     depth++
                                 }
                             } else {
                                 targetUrl = href
                                 resolved = true
                             }
                        } else {
                            resolved = true
                        }
                    } else {
                        // Jika tidak ada di channels, mungkin alias langsung
                        targetUrl = "https://xys1-2-player.pages.dev/bitmovin/?id=$code"
                        resolved = true
                    }
                }
            }

            android.util.Log.d("EventProvider", "Resolved targetUrl: $targetUrl")

            // Jika targetUrl mengarah ke halaman player web pages.dev (seperti xys1-2-player.pages.dev/bitmovin/ atau pisionx.pages.dev/xplay/jwplayer)
            // Kita bypass dan decode DRM ClearKey-nya agar dapat dimainkan secara native di Cloudstream.
            if (targetUrl.contains(".pages.dev/") && (targetUrl.contains("bitmovin") || targetUrl.contains("shaka") || targetUrl.contains("jwplayer") || targetUrl.contains("clappr") || targetUrl.contains("nsplayer"))) {
                var idVal = targetUrl.substringAfter("id=").substringBefore("&").substringBefore("#")
                
                // Normalisasi ID kustom agar cocok dengan database backend worker
                idVal = when (idVal) {
                    "one1" -> "one_1"
                    "one2" -> "one_2"
                    "dazn3es" -> "dazn3_spain"
                    "dazn1es" -> "dsports"
                    "xssc2" -> "ssc2"
                    "vpl8" -> "tvri"
                    else -> idVal
                }
                
                android.util.Log.d("EventProvider", "Resolving bitmovin id: $idVal")
                
                var successDrm = false
                try {
                    val workerUrl = "https://bitmovin.03anutv.workers.dev/?id=$idVal&t=${System.currentTimeMillis()}"
                    val responseText = app.get(workerUrl, timeout = 10).text
                    if (!responseText.trim().equals("CHANNEL_NOT_FOUND", ignoreCase = true)) {
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
                                    
                                    android.util.Log.d("EventProvider", "Successfully decrypted DRM ClearKey: kid=$keyId key=$keyValue")
                                    
                                    val clearkeyKid = hexToBase64Url(keyId)
                                    val clearkeyKey = hexToBase64Url(keyValue)
                                    
                                    val headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                        "Referer" to "https://xys1-2-player.pages.dev/",
                                        "Origin" to "https://xys1-2-player.pages.dev",
                                        "Accept" to "application/dash+xml,video/mpd,application/xml;q=0.9,*/*;q=0.8",
                                        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
                                    )
                                    
                                    val streamUrl = if (dashUrl.contains(".mpd", ignoreCase = true) || dashUrl.contains("mpd", ignoreCase = true)) {
                                        getDrmDashManifestUrl(dashUrl, keyId, headers)
                                    } else {
                                        dashUrl
                                    }
                                    
                                    callback.invoke(
                                        newDrmExtractorLink(
                                            this.name,
                                            this.name,
                                            streamUrl,
                                            ExtractorLinkType.DASH,
                                            CLEARKEY_UUID
                                        ) {
                                            quality = Qualities.Unknown.value
                                            this.headers = headers
                                            kty = "oct"
                                            kid = clearkeyKid
                                            this.key = clearkeyKey
                                        }
                                    )
                                    successDrm = true
                                    return true
                                }
                            }
                        } else {
                            android.util.Log.d("EventProvider", "Response worker has empty iv or data: $responseText")
                        }
                    } else {
                        android.util.Log.d("EventProvider", "Worker returned CHANNEL_NOT_FOUND for id: $idVal")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EventProvider", "Failed to decrypt bitmovin source", e)
                }
                
                if (!successDrm) {
                    // Try NS Player worker fallback
                    android.util.Log.d("EventProvider", "Bitmovin failed/not found. Fallback to NS Player resolver for: $idVal")
                    try {
                        val nsWorkerUrl = "https://nsplayer.pisionpluss5a.workers.dev/?id=$idVal"
                        val nsResponseText = app.get(nsWorkerUrl, timeout = 15).text
                        android.util.Log.d("EventProvider", "NS Player worker raw response: $nsResponseText")
                        if (nsResponseText.trim().isNotEmpty()) {
                            val nsJson = JSONObject(nsResponseText.trim())
                             var encryptedPayload = nsJson.optString(idVal)
                             if (encryptedPayload.isNullOrEmpty()) {
                                 // Coba cari key numerik jika idVal bukan numerik (misal jika idVal = "one1" tetapi worker menggunakan key numerik "22", "55", dsb.)
                                 val keys = nsJson.keys()
                                 while (keys.hasNext() && encryptedPayload.isNullOrEmpty()) {
                                     val k = keys.next()
                                     if (k.all { it.isDigit() }) {
                                         val valStr = nsJson.optString(k)
                                         // Validasi base64 payload
                                         if (valStr.length > 200 && (valStr.startsWith("EA0") || valStr.startsWith("DBA"))) {
                                             encryptedPayload = valStr
                                             android.util.Log.d("EventProvider", "Found dynamic numeric channel payload at key: $k")
                                         }
                                     }
                                 }
                             }
                             if (!encryptedPayload.isNullOrEmpty()) {
                                 val key = "xys1-gh"
                                 val decodedBytes = android.util.Base64.decode(encryptedPayload, android.util.Base64.DEFAULT)
                                 val decryptedBytes = ByteArray(decodedBytes.size)
                                 for (i in decodedBytes.indices) {
                                     val cByte = decodedBytes[i].toInt() and 0xFF
                                     val kByte = key[i % key.length].code
                                     decryptedBytes[i] = (cByte xor kByte).toByte()
                                 }
                                 
                                 val decryptedUrl = String(decryptedBytes, Charsets.UTF_8)
                                    .replace("|", "%7C")
                                    .replace(" ", "%20")
                                
                                android.util.Log.d("EventProvider", "NS Player XOR decrypt success: $decryptedUrl")
                                
                                // Cek apakah URL hasil dekripsi mengandung ClearKey DRM (format: &drmScheme=clearkey&drmLicense=kid:key)
                                val decodedUrlParam = decryptedUrl.replace("%7C", "|").replace("%20", " ")
                                if (decodedUrlParam.contains("drmScheme=clearkey", ignoreCase = true) && decodedUrlParam.contains("drmLicense=", ignoreCase = true)) {
                                    val cleanUrl = decodedUrlParam.substringBefore("|").substringBefore("&drmScheme=")
                                    val licenseParam = decodedUrlParam.substringAfter("drmLicense=").substringBefore("&")
                                    val parts = licenseParam.split(":")
                                    if (parts.size == 2) {
                                        val keyId = parts[0].trim()
                                        val keyValue = parts[1].trim()
                                        val clearkeyKid = hexToBase64Url(keyId)
                                        val clearkeyKey = hexToBase64Url(keyValue)
                                        
                                        android.util.Log.d("EventProvider", "Successfully decrypted NS Player DRM ClearKey: kid=$keyId key=$keyValue for stream: $cleanUrl")
                                        
                                        val headers = mapOf(
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                            "Referer" to "https://xys1-2-player.pages.dev/",
                                            "Origin" to "https://xys1-2-player.pages.dev",
                                            "Accept" to "application/dash+xml,video/mpd,application/xml;q=0.9,*/*;q=0.8",
                                            "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
                                        )
                                        
                                        val streamUrl = if (cleanUrl.contains(".mpd", ignoreCase = true) || cleanUrl.contains("mpd", ignoreCase = true)) {
                                            getDrmDashManifestUrl(cleanUrl, keyId, headers)
                                        } else {
                                            cleanUrl
                                        }
                                        
                                        val isDash = cleanUrl.contains(".mpd", ignoreCase = true) || cleanUrl.contains("mpd", ignoreCase = true)
                                        val streamType = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                                        
                                        callback.invoke(
                                            newDrmExtractorLink(
                                                this.name,
                                                this.name,
                                                streamUrl,
                                                streamType,
                                                CLEARKEY_UUID
                                            ) {
                                                quality = Qualities.Unknown.value
                                                this.headers = headers
                                                kty = "oct"
                                                kid = clearkeyKid
                                                this.key = clearkeyKey
                                            }
                                        )
                                        successDrm = true
                                        return true
                                    }
                                }
                                
                                targetUrl = decryptedUrl
                            } else {
                                android.util.Log.d("EventProvider", "NS Player payload key $idVal not found in json")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("EventProvider", "Failed to decrypt NS Player source", e)
                    }
                }
                
                if (!successDrm && targetUrl.contains(".pages.dev/") && !targetUrl.contains("bitmovin") && !targetUrl.contains("nsplayer")) {
                    // Fallback default stream jika dua-duanya nihil dan bukan dynamic player
                    targetUrl = "https://stream.netxtv.id/live/$idVal/index.m3u8"
                    android.util.Log.d("EventProvider", "Decryption failed. Defaulting to stream CDN fallback: $targetUrl")
                }
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
            val isDash = url.contains(".mpd", ignoreCase = true) || url.contains("mpd", ignoreCase = true) || url.contains("dash", ignoreCase = true)
            val type = when {
                isDash -> ExtractorLinkType.DASH
                isM3u8 -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to "https://xys1-2-player.pages.dev/",
                "Origin" to "https://xys1-2-player.pages.dev"
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
