package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
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
                        "• Resolved Link: $resolvedUrl\n\n" +
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
            // kita bisa coba bypass atau langsung putar jika ada URL stream didalamnya.
            // Di M3UPlaylistPlayer, kita memutar URL streaming secara direct.
            // Mari kita tambahkan handling jika URL adalah direct stream, atau link bitmovin.
            if (targetUrl.contains("xys1-player.pages.dev/bitmovin/")) {
                val idVal = targetUrl.substringAfter("?id=").substringBefore("&")
                android.util.Log.d("EventProvider", "Resolving bitmovin id: $idVal")
                // Seringkali stream URL-nya adalah http://domain/live/stream.m3u8
                // Kita coba panggil api-tvnetx01/get/ untuk mendapatkan link asli jika diperlukan, 
                // atau cukup gunakan fallback stream yang paling umum dari netxtv.
                // Disini kita akan buat resolver yang cerdas.
                val resolveApi = "https://api-tvnetx01.pages.dev/netxtv/get_stream.php?id=$idVal"
                try {
                    val streamRes = app.get(resolveApi, timeout = 8).text
                    val streamJson = JSONObject(streamRes)
                    val directUrl = streamJson.optString("url")
                    if (!directUrl.isNullOrBlank()) {
                        targetUrl = directUrl
                    }
                } catch (e: Exception) {
                    // fallback jika gagal, buat default URL stream netxtv
                    targetUrl = "https://stream.netxtv.id/live/$idVal/index.m3u8"
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
