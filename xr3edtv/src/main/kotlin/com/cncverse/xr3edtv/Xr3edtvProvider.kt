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
    private val channelUrl = "https://api-tvnetx01.pages.dev/netxtv/channel.js"

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
    // Menampilkan Kategori (sebagai Baris) dan saluran TV di dalamnya (sebagai Item)
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // 1. Ambil kategori utama dari depan.js
        val depanResponse = app.get("$jsonUrl?v=${System.currentTimeMillis()}", timeout = 15)
        if (depanResponse.code != 200) return null
        
        val categoriesArray = JSONObject("{\"data\":${depanResponse.text}}").getJSONArray("data")
        
        // 2. Ambil data channels & groups dari menu.js
        val menuResponse = app.get("$menuUrl?t=${System.currentTimeMillis()}", timeout = 15)
        val menuData = if (menuResponse.code == 200) JSONObject(menuResponse.text) else null
        val menuGroups = menuData?.optJSONObject("groups") ?: JSONObject()
        val menuChannels = menuData?.optJSONObject("channels") ?: JSONObject()
        
        // 3. Ambil data channels & groups dari channel.js
        val channelResponse = app.get("$channelUrl?t=${System.currentTimeMillis()}", timeout = 15)
        val channelData = if (channelResponse.code == 200) JSONObject(channelResponse.text) else null
        val channelGroups = channelData?.optJSONObject("groups") ?: JSONObject()
        val channelChannels = channelData?.optJSONObject("channels") ?: JSONObject()

        // Gabungkan groups dan channels agar komprehensif
        val groupsObj = JSONObject()
        val channelsObj = JSONObject()

        // Gabungkan menuGroups & channelGroups
        menuGroups.keys().forEach { key ->
            groupsObj.put(key, menuGroups.get(key))
        }
        channelGroups.keys().forEach { key ->
            groupsObj.put(key, channelGroups.get(key))
        }

        // Gabungkan menuChannels & channelChannels
        menuChannels.keys().forEach { key ->
            channelsObj.put(key, menuChannels.get(key))
        }
        channelChannels.keys().forEach { key ->
            channelsObj.put(key, channelChannels.get(key))
        }
        
        val homePageLists = ArrayList<HomePageList>()

        // 4. Iterasi setiap kategori di depan.js untuk dijadikan baris/kategori utama di beranda Cloudstream
        for (i in 0 until categoriesArray.length()) {
            val catItem = categoriesArray.getJSONObject(i)
            val active = catItem.optBoolean("active", false)
            if (!active) continue

            val catName = catItem.getString("name")
            val catLink = catItem.getString("link")

            if (!catLink.startsWith("go:")) continue
            val rawGroupId = catLink.substring(3)

            // Lakukan normalisasi key groupId agar cocok dengan menu.js/channel.js
            val groupId = when (rawGroupId) {
                "tvnasional" -> "tvnasional"
                "tvsport" -> "sports"
                "race" -> "race"
                "lee" -> "eropa"
                "lea" -> "asia"
                "livefight" -> "fight"
                "schedule" -> "ucl1" // live today diarahkan ke event aktif/ucl1
                "ucl" -> "ucl8x" // fifa world cup diarahkan ke ucl8x
                "vpevent" -> "vplus" // vision+ event diarahkan ke vplus
                "indopride2" -> "indo" // live event indonesia diarahkan ke indo
                "tvmovie" -> "tvmovies" // tv movies
                "tvkidsent" -> "hiburan" // kids & entertainment
                "eredivisi5" -> "tennis" // live tennis
                "livegolf" -> "ucl17" // live golf
                "epl9" -> "epl9" // replay
                "bwf" -> "bwf" // us open / bwf
                else -> rawGroupId
            }

            if (!groupsObj.has(groupId)) continue
            val channelIds = groupsObj.getJSONArray(groupId)
            val searchResps = ArrayList<SearchResponse>()

            // Penuhi item saluran TV di dalam kategori ini
            for (j in 0 until channelIds.length()) {
                if (channelIds.isNull(j)) continue
                val chId = channelIds.getString(j)
                if (chId.isEmpty() || !channelsObj.has(chId)) continue

                val channel = channelsObj.getJSONObject(chId)
                val chName = channel.getString("name")
                val chImg = channel.optString("img", "")
                val chHref = channel.getString("href")

                // Cek apakah item ini mengarah ke subkategori (grup lain) atau stream langsung
                val targetGroupId = if (chHref.startsWith("go:")) chHref.substring(3) else ""
                val isSubcategory = targetGroupId.isNotEmpty() && groupsObj.has(targetGroupId)

                if (isSubcategory) {
                    // Jika subkategori/nested group, sajikan sebagai Folder/TvSeries agar memicu load() saat diklik
                    searchResps.add(
                        newTvSeriesSearchResponse(
                            chName,
                            chHref, // Membawa link go:<subgroup>
                            TvType.TvSeries
                        ) {
                            this.posterUrl = chImg
                        }
                    )
                } else {
                    // Jika streaming langsung, sajikan sebagai Live
                    searchResps.add(
                        newLiveSearchResponse(
                            chName,
                            chHref,
                            TvType.Live
                        ) {
                            this.posterUrl = chImg
                        }
                    )
                }
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
        val results = ArrayList<SearchResponse>()
        
        // Ambil data channels dari menu.js & channel.js
        val menuResponse = app.get("$menuUrl?t=${System.currentTimeMillis()}", timeout = 15)
        val channelResponse = app.get("$channelUrl?t=${System.currentTimeMillis()}", timeout = 15)
        
        val channelsObj = JSONObject()
        
        if (menuResponse.code == 200) {
            val menuData = JSONObject(menuResponse.text)
            val menuChannels = menuData.optJSONObject("channels") ?: JSONObject()
            menuChannels.keys().forEach { channelsObj.put(it, menuChannels.get(it)) }
        }
        
        if (channelResponse.code == 200) {
            val channelData = JSONObject(channelResponse.text)
            val channelChannels = channelData.optJSONObject("channels") ?: JSONObject()
            channelChannels.keys().forEach { channelsObj.put(it, channelChannels.get(it)) }
        }

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
        val rawName = if (url.startsWith("go:")) url.substring(3) else url
        
        // Cek apakah URL yang dipanggil adalah request untuk subkategori/nested group
        val menuResponse = app.get("$menuUrl?t=${System.currentTimeMillis()}", timeout = 15)
        val channelResponse = app.get("$channelUrl?t=${System.currentTimeMillis()}", timeout = 15)
        
        val groupsObj = JSONObject()
        val channelsObj = JSONObject()
        
        if (menuResponse.code == 200) {
            val menuData = JSONObject(menuResponse.text)
            val menuGroups = menuData.optJSONObject("groups") ?: JSONObject()
            val menuChannels = menuData.optJSONObject("channels") ?: JSONObject()
            menuGroups.keys().forEach { groupsObj.put(it, menuGroups.get(it)) }
            menuChannels.keys().forEach { channelsObj.put(it, menuChannels.get(it)) }
        }
        
        if (channelResponse.code == 200) {
            val channelData = JSONObject(channelResponse.text)
            val channelGroups = channelData.optJSONObject("groups") ?: JSONObject()
            val channelChannels = channelData.optJSONObject("channels") ?: JSONObject()
            channelGroups.keys().forEach { groupsObj.put(it, channelGroups.get(it)) }
            channelChannels.keys().forEach { channelsObj.put(it, channelChannels.get(it)) }
        }

        if (groupsObj.has(rawName)) {
            // Ini adalah halaman Subkategori (misal: go:formula1, go:motogp)
            val channelIds = groupsObj.getJSONArray(rawName)
            val subChannelsList = ArrayList<Episode>()

            for (i in 0 until channelIds.length()) {
                if (channelIds.isNull(i)) continue
                val chId = channelIds.getString(i)
                if (chId.isEmpty() || !channelsObj.has(chId)) continue

                val channel = channelsObj.getJSONObject(chId)
                val chName = channel.getString("name")
                val chImg = channel.optString("img", "")
                val chHref = channel.getString("href")

                subChannelsList.add(
                    newEpisode(chHref) {
                        this.name = chName
                        this.posterUrl = chImg
                        this.season = 1
                        this.episode = i + 1
                    }
                )
            }

            return newTvSeriesLoadResponse(
                name = rawName.uppercase(),
                url = url,
                type = TvType.TvSeries,
                episodes = subChannelsList
            ) {
                this.posterUrl = "https://pidio.pages.dev/img/allevent.png"
                this.plot = "Daftar saluran di subkategori $rawName"
            }
        }

        // Jika bukan subkategori, melainkan live stream utama biasa
        var displayName = rawName.uppercase()
        var poster: String? = null
        if (channelsObj.has(rawName)) {
            val ch = channelsObj.getJSONObject(rawName)
            displayName = ch.getString("name")
            poster = ch.optString("img", "")
        }

        return newLiveStreamLoadResponse(
            displayName,
            url,
            url
        ) {
            this.posterUrl = poster
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
            var channelKey = if (data.startsWith("go:")) data.substring(3) else data
            
            // Lakukan normalisasi key saluran TV Indonesia (R+ / V+ / dsb)
            // agar memetakan ke worker dekripsi payload yang benar
            channelKey = when (channelKey) {
                "rctirp" -> "rctirp"
                "mnctvrp" -> "mnctvrp"
                "gtvrp" -> "gtvrp"
                else -> channelKey
            }
            
            // 1. Dapatkan mapping dari ifmUrl
            val ifmResponse = app.get("$ifmUrl?v=${System.currentTimeMillis()}", timeout = 15)
            if (ifmResponse.code != 200) return false
            
            val mapping = JSONObject(ifmResponse.text)
            
            // Tentukan target player url, fallback jika key tidak ada langsung di ifm.js
            val targetUrl = if (mapping.has(channelKey)) {
                mapping.getString(channelKey)
            } else {
                // Gunakan mapping fallback default untuk RCTI / GTV / MNCTV / iNews
                when (channelKey) {
                    "rctirp", "rctivp", "rctifhd", "rcticub" -> "https://xys1-player.pages.dev/bitmovin?id=rctivp&ns=go:xrctivp"
                    "mnctvrp", "mnctvvp", "rctihd", "mnctvcub" -> "https://xys1-player.pages.dev/bitmovin?id=mnctvvp&ns=go:xmnctvvp"
                    "gtvrp", "gtvvp", "rctisd", "gtvcub" -> "https://xys1-player.pages.dev/bitmovin?id=gtvvp&ns=go:xgtvvp"
                    "inewsrp", "inewsitv", "inews", "inews_itv" -> "https://xys1-player.pages.dev/bitmovin?id=inews&ns=go:xinewsvp"
                    else -> ""
                }
            }
            
            if (targetUrl.isEmpty()) return false
            
            // Ambil id dari parameter query URL player (misal: ?id=rctirp)
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
            
            val streamUrl = if (decrypted.has("dash")) decrypted.getString("dash") else decrypted.getString("hls")
            val drmKeys = decrypted.optString("drm", "")

            // Cek tipe manifest link (m3u8 vs mpd/dash)
            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true) || streamUrl.contains("m3u8", ignoreCase = true)
            val isDash = streamUrl.contains(".mpd", ignoreCase = true) || streamUrl.contains("mpd", ignoreCase = true)
            val type = when {
                isM3u8 -> ExtractorLinkType.M3U8
                isDash -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            val headersMap = mapOf(
                "Referer" to "https://lola30es.mpipzni2naturally32kistomach.ru/",
                "Origin" to "https://lola30es.mpipzni2naturally32kistomach.ru",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            if (drmKeys.isNotEmpty()) {
                val parts = drmKeys.split(':')
                var clearkeyKid: String? = null
                var clearkeyKey: String? = null
                
                if (parts.size == 2) {
                    // Terjemahkan hex murni DRM key ke Base64Url format yang dimengerti ExoPlayer
                    val toBase64Url = { hex: String ->
                        try {
                            val clean = hex.replace(" ", "").trim()
                            val bytes = ByteArray(clean.length / 2)
                            for (i in bytes.indices) {
                                bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                            }
                            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                        } catch (e: Exception) {
                            hex
                        }
                    }
                    clearkeyKid = toBase64Url(parts[0].trim())
                    clearkeyKey = toBase64Url(parts[1].trim())
                }

                callback.invoke(
                    newDrmExtractorLink(
                        "XR3EDTV",
                        "Source Live",
                        streamUrl,
                        type,
                        CLEARKEY_UUID
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = headersMap
                        this.kty = "oct"
                        if (clearkeyKid != null) {
                            this.kid = clearkeyKid
                        }
                        if (clearkeyKey != null) {
                            this.key = clearkeyKey
                        }
                    }
                )
            } else {
                callback.invoke(
                    newExtractorLink(
                        "XR3EDTV",
                        "Source Live",
                        streamUrl,
                        type
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = headersMap
                    }
                )
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
