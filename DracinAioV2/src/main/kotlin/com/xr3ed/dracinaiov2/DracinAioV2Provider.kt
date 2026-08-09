package com.xr3ed.dracinaiov2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import android.content.Context
import org.jsoup.Jsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import com.xr3ed.dracinaiov2.BuildConfig
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.HomePageList

class DracinAioV2Provider : MainAPI() {
    companion object {
        var appContext: Context? = null
        val BASE_URL = BuildConfig.DRACINAIO_V2_URL
        val customClient by lazy {
            app.baseClient.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .build()
        }
    }

    override var name = "#Dracin All in One v.2"
    override var mainUrl = BASE_URL
    override var lang = "id"
    override var supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val providers = listOf(
        Pair("bibishort", "BibiShort"),
        Pair("bilitv", "BiliTV"),
        Pair("cubetv", "CubeTV"),
        Pair("dotdrama", "DotDrama"),
        Pair("dramabite", "DramaBite"),
        Pair("dramabox", "DramaBox"),
        Pair("dramanova", "DramaNova"),
        Pair("dramawave", "DramaWave"),
        Pair("flareflow", "FlareFlow"),
        Pair("flextv", "FlexTV"),
        Pair("flickreels", "FlickReels"),
        Pair("freereels", "FreeReels"),
        Pair("fundrama", "FunDrama"),
        Pair("goodshort", "GoodShort"),
        Pair("happyshort", "HappyShort"),
        Pair("idrama", "iDrama"),
        Pair("joyreels", "JoyReels"),
        Pair("kalostv", "KalosTV"),
        Pair("melolo", "Melolo"),
        Pair("microdrama", "MicroDrama"),
        Pair("moboreels", "MoboReels"),
        Pair("netshort", "NetShort"),
        Pair("pinedrama", "PineDrama"),
        Pair("rapidtv", "RapidTV"),
        Pair("reelbuzz", "ReelBuzz"),
        Pair("reelife", "Reelife"),
        Pair("reelshort", "ReelShort"),
        Pair("serealplus", "Sereal+"),
        Pair("shortical", "Shortical"),
        Pair("shortmax", "ShortMax"),
        Pair("stardusttv", "StardustTV"),
        Pair("starshort", "StarShort"),
        Pair("velolo", "Velolo"),
        Pair("vigloo", "Vigloo"),
        Pair("vyntage", "Vyntage")
    )

    override val mainPage = providers.map { (key, title) ->
        MainPageData(title, key)
    }

    private val tabSectionsCache = java.util.concurrent.ConcurrentHashMap<String, List<HomePageList>>()
    private val seenTitlesHistory = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    private fun parseSections(res: String, providerCode: String): List<HomePageList> {
        val lists = ArrayList<HomePageList>()
        try {
            val jsonObj = org.json.JSONObject(res)
            val sections = jsonObj.optJSONArray("sections")
            if (sections != null) {
                val providerName = providers.find { it.first == providerCode }?.second ?: providerCode
                for (i in 0 until sections.length()) {
                    val sectionObj = sections.getJSONObject(i)
                    val sectionName = sectionObj.optString("tab_label").ifEmpty { "Rekomendasi" }
                    val formattedName = "$providerName - $sectionName"
                    val jsonItems = sectionObj.optJSONArray("items")
                    if (jsonItems != null) {
                        val items = ArrayList<SearchResponse>()
                        for (j in 0 until jsonItems.length()) {
                            val item = jsonItems.getJSONObject(j)
                            val title = item.optString("title")
                            val poster = item.optString("poster_url")
                            val watchUrl = item.optString("watch_url")
                            
                            val maskedUrl = if (watchUrl.contains("lynk.id")) watchUrl else "https://lynk.id/xr3ed#$watchUrl"
                            
                            items.add(newTvSeriesSearchResponse(title, maskedUrl) {
                                this.posterUrl = if (poster.startsWith("/")) "$mainUrl$poster" else poster
                            })
                        }
                        if (items.isNotEmpty()) {
                            lists.add(HomePageList(formattedName, items))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return lists
    }

    private fun fetchProviderPage1(providerCode: String): String {
        val url = "$mainUrl${BuildConfig.DRACINAIO_V2_PATH_SECTIONS.format(providerCode)}"
        val httpRequest = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/4.9.1")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        return try {
            customClient.newCall(httpRequest).execute().body?.string() ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun fetchProviderPagePCombined(providerCode: String, tabKeys: List<String>, page: Int): String {
        val url = StringBuilder("$mainUrl${BuildConfig.DRACINAIO_V2_PATH_SECTIONS.format(providerCode)}")
        for (tabKey in tabKeys) {
            url.append("&tab_pages[").append(tabKey).append("]=").append(page)
        }
        val urlStr = url.toString()
        android.util.Log.d("DracinAioV2", "fetchProviderPagePCombined - page: $page, url: '$urlStr', tabKeys: $tabKeys")
        val httpRequest = Request.Builder()
            .url(urlStr)
            .header("User-Agent", "okhttp/4.9.1")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        return try {
            val response = customClient.newCall(httpRequest).execute()
            val code = response.code
            val bodyStr = response.body?.string() ?: ""
            android.util.Log.d("DracinAioV2", "fetchProviderPagePCombined - response code: $code, body length: ${bodyStr.length}")
            bodyStr
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        android.util.Log.d("DracinAioV2", "getMainPage called - page: $page, provider: '${request.data}', name: '${request.name}'")
        
        val providerCode = request.data
        val cacheKey = "dracin_v2_cache_${providerCode}_v7"
        val context = appContext
        
        val seen = seenTitlesHistory.getOrPut(providerCode) {
            java.util.Collections.synchronizedSet(HashSet<String>())
        }
        if (page == 1) {
            seen.clear()
        }
        
        var page1Res = ""
        if (context != null) {
            val cached = context.getKey<String>(cacheKey) ?: ""
            if (cached.contains("\"sections\"")) {
                page1Res = cached
            }
        }
        if (page1Res.isEmpty()) {
            page1Res = fetchProviderPage1(providerCode)
            if (page1Res.contains("\"sections\"") && context != null) {
                context.setKey(cacheKey, page1Res)
                context.setKey("dracin_v2_cache_time_${providerCode}_v7", System.currentTimeMillis())
            }
        }
        
        if (page1Res.isEmpty()) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }
        
        val tabKeys = ArrayList<String>()
        try {
            val jsonObj = org.json.JSONObject(page1Res)
            val sections = jsonObj.optJSONArray("sections")
            if (sections != null) {
                for (i in 0 until sections.length()) {
                    val sectionObj = sections.getJSONObject(i)
                    val tabKey = sectionObj.optString("tab_key")
                    if (tabKey.isNotEmpty()) {
                        tabKeys.add(tabKey)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val items = ArrayList<SearchResponse>()
        var gotAnyItems = false
        var currentPage = page
        
        while (true) {
            val pageRes = if (currentPage == 1) {
                page1Res
            } else {
                fetchProviderPagePCombined(providerCode, tabKeys, currentPage)
            }
            
            if (pageRes.isEmpty()) {
                break
            }
            
            var pageGotItems = false
            try {
                val jsonObj = org.json.JSONObject(pageRes)
                val sections = jsonObj.optJSONArray("sections")
                if (sections != null) {
                    for (i in 0 until sections.length()) {
                        val sectionObj = sections.getJSONObject(i)
                        val jsonItems = sectionObj.optJSONArray("items")
                        if (jsonItems != null && jsonItems.length() > 0) {
                            pageGotItems = true
                            for (j in 0 until jsonItems.length()) {
                                val item = jsonItems.getJSONObject(j)
                                val title = item.optString("title")
                                val poster = item.optString("poster_url")
                                val watchUrl = item.optString("watch_url")
                                
                                if (seen.add(title)) {
                                    val maskedUrl = if (watchUrl.contains("lynk.id")) watchUrl else "https://lynk.id/xr3ed#$watchUrl"
                                    items.add(newTvSeriesSearchResponse(title, maskedUrl) {
                                        this.posterUrl = if (poster.startsWith("/")) "$mainUrl$poster" else poster
                                    })
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            if (pageGotItems) {
                gotAnyItems = true
            }
            
            // If we found some items, or if we reached safety limit, stop looping.
            if (items.isNotEmpty() || currentPage >= 30) {
                break
            }
            
            currentPage++
        }
        
        val homePageList = HomePageList(request.name, items)
        val hasNextPage = gotAnyItems
        
        val titlesList = items.map { it.name }
        android.util.Log.d("DracinAioV2", "getMainPage Option A return - items size: ${items.size}, titles: $titlesList, hasNextPage: $hasNextPage")
        return newHomePageResponse(listOf(homePageList), hasNext = hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?q=$encodedQuery&limit=50&lang=id-ID"
        
        val httpRequest = Request.Builder()
            .url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", "okhttp/4.9.1")
            .build()
            
        val res = try {
            customClient.newCall(httpRequest).execute().body?.string() ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
        
        val items = ArrayList<SearchResponse>()
        try {
            val jsonObj = org.json.JSONObject(res)
            val jsonItems = jsonObj.optJSONArray("items")
            if (jsonItems != null) {
                for (i in 0 until jsonItems.length()) {
                    val item = jsonItems.getJSONObject(i)
                    val title = item.optString("title")
                    val poster = item.optString("poster_url")
                    val watchUrl = item.optString("url")
                    
                    val maskedUrl = if (watchUrl.contains("lynk.id")) watchUrl else "https://lynk.id/xr3ed#$watchUrl"
                    
                    items.add(newTvSeriesSearchResponse(title, maskedUrl) {
                        this.posterUrl = if (poster.startsWith("/")) "$mainUrl$poster" else poster
                    })
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = if (url.contains("lynk.id")) url.substringAfterLast("#", "") else url
        val html = app.get(cleanUrl).text
        val document = Jsoup.parse(html)
        
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.removeSuffix(" - Free Streaming") ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        
        val episodes = document.select(".episode-item").mapNotNull {
            val href = it.attr("href")
            val titleText = it.attr("title").ifEmpty { it.text() }
            val epNum = titleText.replace(Regex("[^0-9]"), "").toIntOrNull()
            if (href.isNotBlank()) {
                val maskedHref = "https://lynk.id/xr3ed#$href"
                newEpisode(maskedHref) {
                    this.name = titleText
                    this.episode = epNum
                }
            } else null
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun updateCookieString(oldCookieString: String, setCookieHeaders: List<String>): String {
        val cookieMap = mutableMapOf<String, String>()
        if (oldCookieString.isNotEmpty()) {
            oldCookieString.split(";").forEach {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) {
                    cookieMap[parts[0].trim()] = parts[1].trim()
                }
            }
        }
        setCookieHeaders.forEach { header ->
            val cookiePart = header.substringBefore(";")
            val parts = cookiePart.split("=", limit = 2)
            if (parts.size == 2) {
                cookieMap[parts[0].trim()] = parts[1].trim()
            }
        }
        return cookieMap.map { "${it.key}=${it.value}" }.joinToString("; ")
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val cleanUrl = if (data.contains("lynk.id")) data.substringAfterLast("#", "") else data
        val response = app.get(cleanUrl)
        val html = response.text
        
        val allSubtitles = mutableMapOf<String, SubtitleFile>()
        try {
            val epNum = response.url.substringBefore("?").substringAfterLast("/").toIntOrNull() ?: 1
            val episodeItemsRawMatch = Regex("""const\s+episodeItemsRaw\s*=\s*(\[[\s\S]*?\]);""").find(html)
            if (episodeItemsRawMatch != null) {
                val rawJson = episodeItemsRawMatch.groupValues[1]
                val arr = org.json.JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val num = item.optInt("number", -1)
                    if (num == epNum) {
                        val multi = item.optJSONArray("multi_subtitles")
                        if (multi != null) {
                            val baseHost = java.net.URI(BASE_URL).host ?: ""
                            val defaultEdgeBase = "https://edge.$baseHost"
                            val edgeBaseMatch = Regex("""refreshSourceEdgeBase\s*=\s*["']([^"']+)["']""").find(html)
                            val edgeBase = edgeBaseMatch?.groupValues?.get(1)?.replace("\\", "") ?: defaultEdgeBase
                            
                            for (j in 0 until multi.length()) {
                                val subItem = multi.getJSONObject(j)
                                val subPath = subItem.optString("subtitle_url").replace("\\/", "/")
                                val lang = subItem.optString("language_code")
                                val label = subItem.optString("label")
                                
                                if (subPath.isNotEmpty()) {
                                    val fullSubUrl = if (subPath.startsWith("http://") || subPath.startsWith("https://")) {
                                        subPath
                                    } else {
                                        val cleanSubPath = if (subPath.startsWith("/")) subPath else "/$subPath"
                                        "${edgeBase.trimEnd('/')}$cleanSubPath"
                                    }
                                    val vttSubUrl = if (fullSubUrl.endsWith(".vtt", ignoreCase = true) || fullSubUrl.endsWith(".srt", ignoreCase = true)) {
                                        fullSubUrl
                                    } else {
                                        "$fullSubUrl#.vtt"
                                    }
                                    allSubtitles[lang.lowercase()] = SubtitleFile(
                                        lang = label.ifEmpty { lang },
                                        url = vttSubUrl
                                    )
                                }
                            }
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DracinAioV2", "Failed to parse episodeItemsRaw in loadLinks", e)
        }

        var cookieString = response.cookies.map { "${it.key}=${it.value}" }.joinToString("; ")
        android.util.Log.d("DracinAioV2", "loadLinks cleanUrl: $cleanUrl")
        android.util.Log.d("DracinAioV2", "loadLinks html length: ${html.length}, contains token: ${html.contains("refreshSourceContextToken")}")
        android.util.Log.d("DracinAioV2", "loadLinks cookieString: $cookieString")
        
        val tokenMatch = Regex("""refreshSourceContextToken\s*=\s*["']([^"']+)["']""").find(html)
        if (tokenMatch != null) {
            val token = tokenMatch.groupValues[1]
            val baseUrl = response.url.substringBefore("?")
            
            // Check if ad gate is already unlocked
            val remainingMsMatch = Regex("""adsGateRemainingMs\s*=\s*([0-9]+)""").find(html)
            val remainingMs = remainingMsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            if (remainingMs <= 0) {
                // Bypass Ad Gate & Consent
                val csrfTokenMatch = Regex("""adsCsrfToken\s*=\s*["']([^"']+)["']""").find(html)
                val csrfToken = csrfTokenMatch?.groupValues?.get(1) ?: ""
                
                val gateTokenMatch = Regex("""adsGateUnlockToken\s*=\s*["']([^"']+)["']""").find(html)
                val consentTokenMatch = Regex("""adsConsentTokens\s*=\s*\{[^:]+:\s*["']([^"']+)["']""").find(html)
                
                if (csrfToken.isNotEmpty()) {
                    val cookieLock = Any()
                    coroutineScope {
                        val jobs = mutableListOf<Deferred<*>>()
                        
                        if (gateTokenMatch != null) {
                            val gateToken = gateTokenMatch.groupValues[1]
                            jobs.add(async(Dispatchers.IO) {
                                val unlockReq = Request.Builder()
                                    .url("$BASE_URL${BuildConfig.DRACINAIO_V2_PATH_GATE_UNLOCK}")
                                    .post("""{"token":"$gateToken"}""".toRequestBody("application/json".toMediaTypeOrNull()))
                                    .header("X-CSRF-TOKEN", csrfToken)
                                    .header("X-Requested-With", "XMLHttpRequest")
                                    .header("Referer", response.url)
                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                    .header("Cookie", cookieString)
                                    .build()
                                try { 
                                    customClient.newCall(unlockReq).execute().use { res ->
                                        val setCookies = res.headers("Set-Cookie")
                                        synchronized(cookieLock) {
                                            cookieString = updateCookieString(cookieString, setCookies)
                                        }
                                    }
                                } catch (e: Exception) { 
                                    android.util.Log.e("DracinAioV2", "gate-unlock failed", e)
                                }
                            })
                        }
                        
                        if (consentTokenMatch != null) {
                            val consentToken = consentTokenMatch.groupValues[1]
                            jobs.add(async(Dispatchers.IO) {
                                val consentReq = Request.Builder()
                                    .url("$BASE_URL${BuildConfig.DRACINAIO_V2_PATH_CONSENT}")
                                    .post("""{"token":"$consentToken"}""".toRequestBody("application/json".toMediaTypeOrNull()))
                                    .header("X-CSRF-TOKEN", csrfToken)
                                    .header("X-Requested-With", "XMLHttpRequest")
                                    .header("Referer", response.url)
                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                    .header("Cookie", cookieString)
                                    .build()
                                try { 
                                    customClient.newCall(consentReq).execute().use { res ->
                                        val setCookies = res.headers("Set-Cookie")
                                        synchronized(cookieLock) {
                                            cookieString = updateCookieString(cookieString, setCookies)
                                        }
                                    }
                                } catch (e: Exception) { 
                                    android.util.Log.e("DracinAioV2", "consent failed", e)
                                }
                            })
                        }
                        
                        jobs.awaitAll()
                    }
                }
            }

            val baseHost = java.net.URI(BASE_URL).host ?: ""
            val defaultEdgeBase = "https://edge.$baseHost"

            val edgeBaseMatch = Regex("""refreshSourceEdgeBase\s*=\s*["']([^"']+)["']""").find(html)
            val edgeBase = edgeBaseMatch?.groupValues?.get(1)?.replace("\\", "") ?: defaultEdgeBase
            val edgePath = baseUrl.substringAfter(baseHost)
            
            val refreshUrl = "${edgeBase.trimEnd('/')}${BuildConfig.DRACINAIO_V2_PATH_EDGE}${edgePath}${BuildConfig.DRACINAIO_V2_PATH_REFRESH.format(token)}"
            android.util.Log.d("DracinAioV2", "loadLinks refreshUrl: $refreshUrl")
            
            val httpRequest = Request.Builder()
                .url(refreshUrl)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", response.url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Cookie", cookieString)
                .build()
                
            val res = try {
                customClient.newCall(httpRequest).execute().body?.string() ?: ""
            } catch (e: Exception) {
                android.util.Log.e("DracinAioV2", "loadLinks refresh-source fetch failed", e)
                e.printStackTrace()
                ""
            }
            
            android.util.Log.d("DracinAioV2", "loadLinks refresh-source response: $res")
            
            try {
                val jsonObj = org.json.JSONObject(res)
                val playUrl = jsonObj.optString("play_url").replace("\\/", "/")
                val isHls = jsonObj.optBoolean("direct_play_is_hls", false) || playUrl.contains(".m3u8") || playUrl.contains("stream.narto-drama.com")
                
                if (playUrl.isNotEmpty()) {
                    val cleanedPlayUrl = playUrl
                        .replace(Regex("""^https?://[^/]+\.(?:workers|pages)\.dev/.*?(https?://)"""), "$1")
                        .replace("https://lhr-nitro.workers.dev/proxify?url=", "")
                        .replace("https://pdx-nitro.workers.dev/proxify?url=", "")
                    
                    val linkType = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            name = this.name,
                            source = this.name,
                            url = cleanedPlayUrl,
                            type = linkType
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "Referer" to "$BASE_URL/",
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            )
                        }
                    )
                    
                    // Extract subtitles
                    val multiSubs = jsonObj.optJSONArray("multi_subtitles")
                    if (multiSubs != null && multiSubs.length() > 0) {
                        for (i in 0 until multiSubs.length()) {
                            val subItem = multiSubs.getJSONObject(i)
                            val subPath = subItem.optString("subtitle_url").replace("\\/", "/")
                            val lang = subItem.optString("language_code")
                            val label = subItem.optString("label")
                            
                            if (subPath.isNotEmpty()) {
                                val fullSubUrl = if (subPath.startsWith("http://") || subPath.startsWith("https://")) {
                                    subPath
                                } else {
                                    val cleanSubPath = if (subPath.startsWith("/")) subPath else "/$subPath"
                                    "${edgeBase.trimEnd('/')}$cleanSubPath"
                                }
                                val vttSubUrl = if (fullSubUrl.endsWith(".vtt", ignoreCase = true) || fullSubUrl.endsWith(".srt", ignoreCase = true)) {
                                    fullSubUrl
                                } else {
                                    "$fullSubUrl#.vtt"
                                }
                                allSubtitles[lang.lowercase()] = SubtitleFile(
                                    lang = label.ifEmpty { lang },
                                    url = vttSubUrl
                                )
                            }
                        }
                    } else {
                        val subPath = jsonObj.optString("subtitle_url").replace("\\/", "/")
                        if (subPath.isNotEmpty()) {
                            val fullSubUrl = if (subPath.startsWith("http://") || subPath.startsWith("https://")) {
                                subPath
                            } else {
                                val cleanSubPath = if (subPath.startsWith("/")) subPath else "/$subPath"
                                "${edgeBase.trimEnd('/')}$cleanSubPath"
                            }
                            val vttSubUrl = if (fullSubUrl.endsWith(".vtt", ignoreCase = true) || fullSubUrl.endsWith(".srt", ignoreCase = true)) {
                                fullSubUrl
                            } else {
                                "$fullSubUrl#.vtt"
                            }
                            allSubtitles["subtitle"] = SubtitleFile(
                                lang = "Subtitle",
                                url = vttSubUrl
                            )
                        }
                    }
                    
                    // Invoke callback for all collected subtitles (both HTML pre-rendered and API live)
                    allSubtitles.values.forEach {
                        subtitleCallback.invoke(it)
                    }
                    return true
                }
            } catch (e: Exception) {
                android.util.Log.e("DracinAioV2", "Failed to parse refresh-source response JSON", e)
            }
        }
        return false
    }
}
