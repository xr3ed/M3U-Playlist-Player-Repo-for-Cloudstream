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

    override var name = "#Dracin All in One [Backup]"
    override var mainUrl = BASE_URL
    override var lang = "id"
    override var supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val providers = listOf(
        Pair("bilitv", "BiliTV"),
        Pair("bibishort", "BibiShort"),
        Pair("freereels", "FreeReels"),
        Pair("reelshort", "ReelShort"),
        Pair("dramabox", "DramaBox"),
        Pair("shortmax", "ShortMax"),
        Pair("dramabite", "DramaBite"),
        Pair("dramawave", "DramaWave"),
        Pair("vyntage", "Vyntage"),
        Pair("dramanova", "DramaNova"),
        Pair("dotdrama", "DotDrama"),
        Pair("rapidtv", "RapidTV"),
        Pair("cubetv", "CubeTV"),
        Pair("happyshort", "HappyShort"),
        Pair("reelbuzz", "ReelBuzz"),
        Pair("flareflow", "FlareFlow"),
        Pair("pinedrama", "PineDrama"),
        Pair("serealplus", "Sereal+"),
        Pair("netshort", "NetShort"),
        Pair("idrama", "iDrama"),
        Pair("melolo", "Melolo"),
        Pair("starshort", "StarShort"),
        Pair("goodshort", "GoodShort"),
        Pair("flextv", "FlexTV"),
        Pair("kalostv", "KalosTV"),
        Pair("fundrama", "FunDrama"),
        Pair("microdrama", "MicroDrama"),
        Pair("moboreels", "MoboReels"),
        Pair("reelife", "Reelife"),
        Pair("reelala", "Reelala"),
        Pair("stardusttv", "StardustTV"),
        Pair("velolo", "Velolo"),
        Pair("vigloo", "Vigloo"),
        Pair("flickreels", "FlickReels"),
        Pair("joyreels", "JoyReels"),
        Pair("shortical", "Shortical")
    )

    override val mainPage = providers.map { (key, title) ->
        MainPageData(title, key)
    }

    private fun parseSectionsItems(res: String): List<SearchResponse> {
        val items = ArrayList<SearchResponse>()
        try {
            val jsonObj = org.json.JSONObject(res)
            val sections = jsonObj.optJSONArray("sections")
            if (sections != null && sections.length() > 0) {
                val section = sections.getJSONObject(0)
                val jsonItems = section.optJSONArray("items")
                if (jsonItems != null) {
                    for (i in 0 until jsonItems.length()) {
                        val item = jsonItems.getJSONObject(i)
                        val title = item.optString("title")
                        val poster = item.optString("poster_url")
                        val watchUrl = item.optString("watch_url")
                        
                        val maskedUrl = if (watchUrl.contains("lynk.id")) watchUrl else "https://lynk.id/xr3ed#$watchUrl"
                        
                        items.add(newTvSeriesSearchResponse(title, maskedUrl) {
                            this.posterUrl = if (poster.startsWith("/")) "$mainUrl$poster" else poster
                        })
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cacheKey = "dracin_v2_cache_${request.data}_$page"
        val cacheTimeKey = "dracin_v2_cache_time_${request.data}_$page"
        
        val context = appContext
        if (context != null) {
            val cachedJson = context.getKey<String>(cacheKey)
            val cachedTime = context.getKey<Long>(cacheTimeKey) ?: 0L
            val now = System.currentTimeMillis()
            if (cachedJson != null && now - cachedTime < 10800000) { // 3 hours cache TTL (3 * 60 * 60 * 1000)
                val parsedItems = parseSectionsItems(cachedJson)
                if (parsedItems.isNotEmpty()) {
                    android.util.Log.d("DracinAioV2", "getMainPage loaded from local cache for ${request.data}")
                    return newHomePageResponse(request.name, parsedItems)
                }
            }
        }

        val url = "$mainUrl${BuildConfig.DRACINAIO_V2_PATH_SECTIONS.format(request.data)}"
        
        val httpRequest = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/4.9.1")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
            
        val res = try {
            customClient.newCall(httpRequest).execute().body?.string() ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
        
        if (res.isNotEmpty() && context != null) {
            context.setKey(cacheKey, res)
            context.setKey(cacheTimeKey, System.currentTimeMillis())
        }
        
        val items = parseSectionsItems(res)
        return newHomePageResponse(request.name, items)
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
        
        var cookieString = response.cookies.map { "${it.key}=${it.value}" }.joinToString("; ")
        android.util.Log.d("DracinAioV2", "loadLinks cleanUrl: $cleanUrl")
        android.util.Log.d("DracinAioV2", "loadLinks html length: ${html.length}, contains token: ${html.contains("refreshSourceContextToken")}")
        android.util.Log.d("DracinAioV2", "loadLinks cookieString: $cookieString")
        
        val tokenMatch = Regex("""refreshSourceContextToken\s*=\s*["']([^"']+)["']""").find(html)
        if (tokenMatch != null) {
            val token = tokenMatch.groupValues[1]
            val baseUrl = cleanUrl.substringBefore("?")
            
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
                            for (i in 0..5) {
                                jobs.add(async(Dispatchers.IO) {
                                    val unlockReq = Request.Builder()
                                        .url("$BASE_URL${BuildConfig.DRACINAIO_V2_PATH_GATE_UNLOCK}")
                                        .post("""{"token":"$gateToken"}""".toRequestBody("application/json".toMediaTypeOrNull()))
                                        .header("X-CSRF-TOKEN", csrfToken)
                                        .header("X-Requested-With", "XMLHttpRequest")
                                        .header("Referer", cleanUrl)
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
                                        android.util.Log.e("DracinAioV2", "gate-unlock $i failed", e)
                                    }
                                })
                            }
                        }
                        
                        if (consentTokenMatch != null) {
                            val consentToken = consentTokenMatch.groupValues[1]
                            jobs.add(async(Dispatchers.IO) {
                                val consentReq = Request.Builder()
                                    .url("$BASE_URL${BuildConfig.DRACINAIO_V2_PATH_CONSENT}")
                                    .post("""{"token":"$consentToken"}""".toRequestBody("application/json".toMediaTypeOrNull()))
                                    .header("X-CSRF-TOKEN", csrfToken)
                                    .header("X-Requested-With", "XMLHttpRequest")
                                    .header("Referer", cleanUrl)
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
                .header("Referer", cleanUrl)
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
                val isHls = jsonObj.optBoolean("direct_play_is_hls", false) || playUrl.contains(".m3u8")
                
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
                                val fullSubUrl = if (subPath.startsWith("/")) {
                                    "${edgeBase.trimEnd('/')}$subPath"
                                } else {
                                    subPath
                                }
                                subtitleCallback.invoke(
                                    SubtitleFile(
                                        lang = label.ifEmpty { lang },
                                        url = fullSubUrl
                                    )
                                )
                            }
                        }
                    } else {
                        val subPath = jsonObj.optString("subtitle_url").replace("\\/", "/")
                        if (subPath.isNotEmpty()) {
                            val fullSubUrl = if (subPath.startsWith("/")) {
                                "${edgeBase.trimEnd('/')}$subPath"
                            } else {
                                subPath
                            }
                            subtitleCallback.invoke(
                                SubtitleFile(
                                    lang = "Subtitle",
                                    url = fullSubUrl
                                )
                            )
                        }
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
