package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URL

class RBTVPlusProvider : MainAPI() {
    override var mainUrl = "https://www.rbtvplus18.mom"
    override var name = "RBTV+"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "id"

    // Helper untuk mem-parse Nuxt state window.__NUXT__ dari HTML
    private fun parseNuxtState(html: String): JSONObject? {
        try {
            // 1. Ekstrak isi script window.__NUXT__
            val nuxtScript = Regex("""window\.__NUXT__\s*=\s*(.*?)\s*;""").find(html)?.groupValues?.get(1) ?: return null

            // 2. Dapatkan params (daftar parameter fungsi IIFE)
            val paramMatch = Regex("""^\(function\(([^)]+)\)""").find(nuxtScript) ?: return null
            val params = paramMatch.groupValues[1].split(",").map { it.trim() }

            // 3. Dapatkan args (daftar nilai literal di bagian akhir IIFE)
            val idx = nuxtScript.lastIndexOf("}(")
            if (idx == -1) return null
            var argsStr = nuxtScript.substring(idx + 2).trim()
            while (argsStr.endsWith(")") || argsStr.endsWith(";") || argsStr.endsWith(" ")) {
                argsStr = argsStr.dropLast(1).trim()
            }

            val argsArray = JSONArray("[$argsStr]")
            val paramMap = HashMap<String, Any?>()
            for (i in 0 until minOf(params.size, argsArray.length())) {
                paramMap[params[i]] = argsArray.opt(i)
            }

            // 4. Cari blok state
            val stateStart = nuxtScript.indexOf("state:{")
            if (stateStart == -1) return null
            val stateJs = nuxtScript.substring(stateStart, idx).trim()

            // 5. Konversi objek JavaScript literal menjadi JSON valid menggunakan regex substitution map
            val jsonRegex = Regex("""([a-zA-Z0-9_$]+)\s*:\s*([a-zA-Z0-9_$]+|"[^"]*"|'[^']*'|\{\}|\[\])""")
            val convertedJson = jsonRegex.replace(stateJs) { matchResult ->
                val key = matchResult.groupValues[1]
                val valName = matchResult.groupValues[2]

                val mappedVal = paramMap[valName]
                val valJson = when {
                    paramMap.containsKey(valName) -> {
                        when (mappedVal) {
                            null -> "null"
                            is String -> JSONObject.quote(mappedVal)
                            else -> mappedVal.toString()
                        }
                    }
                    valName.startsWith("'") && valName.endsWith("'") -> {
                        JSONObject.quote(valName.substring(1, valName.length - 1))
                    }
                    else -> valName
                }
                "\"$key\":$valJson"
            }

            val fullJson = "{$convertedJson}"
            return JSONObject(fullJson).getJSONObject("state")
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val categories = listOf(
            Pair("Sepakbola", "$mainUrl/id/football.html"),
            Pair("Basket", "$mainUrl/id/basketball.html"),
            Pair("Olahraga Lainnya", "$mainUrl/id/others.html")
        )

        val homePages = categories.mapNotNull { (title, url) ->
            try {
                val response = app.get(url)
                val html = response.text
                val state = parseNuxtState(html) ?: return@mapNotNull null
                
                val listData = state.optJSONObject("data")
                    ?.optJSONObject("home")
                    ?.optJSONArray("list") ?: return@mapNotNull null

                val matches = ArrayList<SearchResponse>()
                for (i in 0 until listData.length()) {
                    val m = listData.getJSONObject(i)
                    val id = m.optString("id")
                    val streamId = m.optString("stream_id")
                    val hasStream = m.optBoolean("hasStream", false)
                    val live = m.optBoolean("live", false)
                    
                    if (!hasStream && streamId.isEmpty()) continue

                    val homeName = m.optString("homeName")
                    val awayName = m.optString("awayName")
                    val leagueName = m.optString("leagueName")

                    val matchTitle = "$homeName vs $awayName ($leagueName)"
                    val detailUrl = "$mainUrl/id/match/detail.html?id=$id&stream_id=$streamId"

                    val searchResp = newLiveSearchResponse(
                        matchTitle,
                        detailUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = null
                    }
                    matches.add(searchResp)
                }

                if (matches.isNotEmpty()) {
                    HomePageList(title, matches)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        return if (homePages.isNotEmpty()) newHomePageResponse(homePages, hasNext = false) else null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val categories = listOf(
            "$mainUrl/id/football.html",
            "$mainUrl/id/basketball.html",
            "$mainUrl/id/others.html"
        )

        val results = ArrayList<SearchResponse>()
        for (url in categories) {
            try {
                val html = app.get(url).text
                val state = parseNuxtState(html) ?: continue
                val listData = state.optJSONObject("data")
                    ?.optJSONObject("home")
                    ?.optJSONArray("list") ?: continue

                for (i in 0 until listData.length()) {
                    val m = listData.getJSONObject(i)
                    val homeName = m.optString("homeName")
                    val awayName = m.optString("awayName")
                    val leagueName = m.optString("leagueName")
                    
                    if (homeName.contains(query, ignoreCase = true) || 
                        awayName.contains(query, ignoreCase = true) || 
                        leagueName.contains(query, ignoreCase = true)) {
                        
                        val id = m.optString("id")
                        val streamId = m.optString("stream_id")
                        
                        val matchTitle = "$homeName vs $awayName ($leagueName)"
                        val detailUrl = "$mainUrl/id/match/detail.html?id=$id&stream_id=$streamId"

                        val searchResp = newLiveSearchResponse(
                            matchTitle,
                            detailUrl,
                            TvType.Live
                        ) {
                            this.posterUrl = null
                        }
                        results.add(searchResp)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = URI(url)
        val queryMap = uri.query?.split("&")?.associate {
            val parts = it.split("=")
            parts[0] to parts.getOrNull(1)
        } ?: emptyMap()
        
        var streamId = queryMap["stream_id"]
        val matchId = queryMap["id"]

        var matchTitle = "RBTV+ Live Match"
        
        try {
            val html = app.get(url).text
            val state = parseNuxtState(html)
            if (state != null) {
                val matchObj = state.optJSONObject("data")
                    ?.optJSONObject("match")
                    ?.optJSONObject("match")
                
                if (matchObj != null) {
                    val homeName = matchObj.optJSONObject("home")?.optString("name") ?: ""
                    val awayName = matchObj.optJSONObject("away")?.optString("name") ?: ""
                    val leagueName = matchObj.optJSONObject("league")?.optString("name") ?: ""
                    if (homeName.isNotEmpty() && awayName.isNotEmpty()) {
                        matchTitle = "$homeName vs $awayName ($leagueName)"
                    }
                }
                
                if (streamId.isNullOrEmpty()) {
                    streamId = state.optJSONObject("player")
                        ?.optJSONObject("cStreamItem")
                        ?.optString("stream_id")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (streamId.isNullOrEmpty() && !matchId.isNullOrEmpty()) {
            streamId = matchId
        }

        val playerUrl = "https://roastoup.com/4/$streamId"

        return newLiveStreamLoadResponse(
            matchTitle,
            url,
            playerUrl
        ) {
            this.posterUrl = null
            this.plot = "Tonton siaran langsung pertandingan olahraga di RBTV+"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) return false
        
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/"
            )

            // 1. Fetch halaman pertama player
            val response1 = app.get(data, headers = headers)
            val html1 = response1.text

            // 2. Dapatkan URL redirect kuki / fingerprint
            val redirectMatch = Regex("""href=['"]([^'"]+)['"]""").find(html1) ?: return false
            var redirectUrl = redirectMatch.groupValues[1]

            if (!redirectUrl.startsWith("http")) {
                redirectUrl = URL(URL(data), redirectUrl).toString()
            }

            // 3. Fetch halaman kedua (redirect player)
            val response2 = app.get(redirectUrl, headers = headers)
            val html2 = response2.text

            // 4. Ekstrak JWT path dari script inline fetch
            val jwtMatch = Regex("""fetch\(\s*['"](/[^'"]+eyJhbGciOi[^'"]*)['"]\s*\)""").find(html2)
                ?: Regex("""fetch\(\s*['"](/[^'"]+JWT_atau_token[^'"]*)['"]\s*\)""").find(html2)
                ?: Regex("""fetch\(\s*['"](/[^'"]+)['"]\s*\)""").find(html2)

            if (jwtMatch == null) return false
            val jwtPath = jwtMatch.groupValues[1]

            val playerUri = URI(data)
            val apiHost = "${playerUri.scheme}://${playerUri.host}"
            val apiUrl = "$apiHost$jwtPath"

            // 5. Panggil endpoint API internal untuk mendapatkan stream location asli
            val apiHeaders = headers + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            )

            val apiResponse = app.get(apiUrl, headers = apiHeaders)
            val apiText = apiResponse.text

            if (apiResponse.code == 200 && apiText.isNotEmpty()) {
                val json = JSONObject(apiText)
                val streamUrl = json.optString("location")
                
                if (streamUrl.isNotEmpty() && streamUrl.startsWith("http")) {
                    val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true) || streamUrl.contains("m3u8", ignoreCase = true)
                    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            name = "RBTV+ Live Player",
                            source = "RBTV+ Stream",
                            url = streamUrl,
                            type = linkType
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("Referer" to "https://roastoup.com/")
                        }
                    )
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
