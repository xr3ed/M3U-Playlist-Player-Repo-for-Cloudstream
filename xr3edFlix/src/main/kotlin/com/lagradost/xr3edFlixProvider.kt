package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.xr3edFlix.BuildConfig
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.INFER_TYPE
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.math.BigInteger
import java.security.MessageDigest

class xr3edFlixProvider : MainAPI() {
    companion object {
        val addedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val titleSearchCache = java.util.concurrent.ConcurrentHashMap<String, SearchResponse>()
        private val listCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()
        private const val CACHE_EXPIRY_MS = 60 * 60 * 1000L // 1 jam
        private val cleanClient = com.lagradost.cloudstream3.app.baseClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 120
                maxRequestsPerHost = 40
            })
            .cookieJar(object : okhttp3.CookieJar {
                private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, List<okhttp3.Cookie>>()
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                    cookieStore[url.host] = cookies
                }
                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            })
            .build()

        private val mapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        private const val TMDB_API_BASE = "https://api.themoviedb.org/3"
        private const val API_BASE = "https://new.vidnest.fun"
        private const val ALPHABET = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="

        // Fallback TMDB API Key if not set in BuildConfig
        private fun getTmdbKey(): String {
            return BuildConfig.XSTREAM_TMDB_API.ifEmpty { "8265bd1679663a7ea12ac168da84d2e8" }
        }

        // Custom Base64 Decryptor
        fun decrypt(cipherText: String): String {
            val charMap = IntArray(256) { 64 }
            for (i in ALPHABET.indices) {
                charMap[ALPHABET[i].code] = i
            }
            
            val decodedBytes = ByteArrayOutputStream()
            var i = 0
            val len = cipherText.length
            
            while (i < len) {
                val c1 = if (i < len) cipherText[i++].code else '='.code
                val c2 = if (i < len) cipherText[i++].code else '='.code
                val c3 = if (i < len) cipherText[i++].code else '='.code
                val c4 = if (i < len) cipherText[i++].code else '='.code
                
                val val0 = charMap[c1]
                val val1 = charMap[c2]
                val val2 = charMap[c3]
                val val3 = charMap[c4]
                
                val b1 = (val0 shl 2) or (val1 shr 4)
                decodedBytes.write(b1 and 0xFF)
                
                if (val2 != 64) {
                    val b2 = ((val1 and 15) shl 4) or (val2 shr 2)
                    decodedBytes.write(b2 and 0xFF)
                }
                
                if (val3 != 64) {
                    val b3 = ((val2 and 3) shl 6) or val3
                    decodedBytes.write(b3 and 0xFF)
                }
            }
            
            return decodedBytes.toString("UTF-8")
        }

        private suspend inline fun <reified T : Any> parsedGet(url: String): T? {
            return try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .build()
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cleanClient.newCall(request).execute().use { response ->
                        response.body?.string() ?: ""
                    }
                }
                Log.d("xr3edFlix", "parsedGet OK: $url -> ${text.take(120)}")
                mapper.readValue(text, T::class.java)
            } catch (e: Exception) {
                Log.e("xr3edFlix", "parsedGet ERROR: $url -> ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    override var name = "xr3edFlix"
    override var mainUrl = "https://watch-v2.autoembed.app"
    override var lang = "id"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("Beranda", "beranda"),
        MainPageData("Film Trending", "Film Trending"),
        MainPageData("Film Populer", "Film Populer"),
        MainPageData("Seri Trending", "Seri Trending"),
        MainPageData("Seri Populer", "Seri Populer"),
        MainPageData("Netflix Movies", "Netflix Movies"),
        MainPageData("Netflix Series", "Netflix Series"),
        MainPageData("Disney+ Movies", "Disney+ Movies"),
        MainPageData("Disney+ Series", "Disney+ Series"),
        MainPageData("Prime Video Movies", "Prime Video Movies"),
        MainPageData("Prime Video Series", "Prime Video Series"),
        MainPageData("Apple TV+ Movies", "Apple TV+ Movies"),
        MainPageData("Apple TV+ Series", "Apple TV+ Series"),
        MainPageData("iTunes Store Movies", "iTunes Store Movies"),
        MainPageData("Viu Series", "Viu Series"),
        MainPageData("Vidio Movies", "Vidio Movies"),
        MainPageData("Vidio Series", "Vidio Series"),
        MainPageData("HBO GO Movies", "HBO GO Movies"),
        MainPageData("HBO GO Series", "HBO GO Series"),
        MainPageData("Catchplay+ Movies", "Catchplay+ Movies"),
        MainPageData("Catchplay+ Series", "Catchplay+ Series"),
        MainPageData("Crunchyroll Series", "Crunchyroll Series"),
        MainPageData("Lionsgate Play Movies", "Lionsgate Play Movies"),
        MainPageData("Lionsgate Play Series", "Lionsgate Play Series")
    )

    private suspend fun fetchTmdbList(path: String, params: Map<String, String>): List<SearchResponse> {
        val mapWithLang = params + mapOf("language" to "en-US")
        val queryParams = mapWithLang.entries.joinToString("&") { "${it.key}=${it.value}" }
        val cacheKey = "$path?$queryParams"
        listCache[cacheKey]?.let { (timestamp, list) ->
            if (System.currentTimeMillis() - timestamp < CACHE_EXPIRY_MS) {
                return list
            }
        }
        val url = "$TMDB_API_BASE/$path?api_key=${getTmdbKey()}&$queryParams"
        val res = parsedGet<TMDBDiscoverResponse>(url)
        val result = res?.results?.map { media ->
            val title = if (media.originalLanguage == "id") {
                media.originalTitle ?: media.originalName ?: media.title ?: media.name ?: "Unknown"
            } else {
                media.title ?: media.name ?: "Unknown"
            }
            val isMovie = media.title != null || media.mediaType == "movie"
            val poster = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            newMovieSearchResponse(
                name = title,
                url = if (isMovie) "https://lynk.id/xr3ed#movie::${media.id}" else "https://lynk.id/xr3ed#tv::${media.id}",
                type = if (isMovie) TvType.Movie else TvType.TvSeries
            ) {
                this.posterUrl = poster
            }
        } ?: emptyList()
        if (result.isNotEmpty()) {
            listCache[cacheKey] = Pair(System.currentTimeMillis(), result)
        }
        return result
    }

    private suspend fun fetchFlixPatrolList(providerUrl: String, isMovie: Boolean, fallbackProviderId: String, fallbackPath: String): List<SearchResponse> {
        val cacheKey = "flixpatrol_${providerUrl}_${isMovie}"
        listCache[cacheKey]?.let { (timestamp, list) ->
            if (System.currentTimeMillis() - timestamp < CACHE_EXPIRY_MS) {
                return list
            }
        }
        val result = try {
            val html = app.get(
                providerUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
                timeout = 8
            ).text
            
            val movieIdx = if (providerUrl.contains("vidio") && isMovie) {
                val idx = html.indexOf("TOP 10 Movies (in Indonesian)")
                if (idx != -1) idx else html.indexOf("TOP 10 Movies")
            } else {
                html.indexOf("TOP 10 Movies")
            }
            val tvIdx = html.indexOf("TOP 10 TV Shows")
            
            val section = if (isMovie) {
                if (movieIdx != -1) {
                    if (tvIdx != -1 && tvIdx > movieIdx) html.substring(movieIdx, tvIdx) else html.substring(movieIdx)
                } else html
            } else {
                if (tvIdx != -1) html.substring(tvIdx) else html
            }
            
            val regex = Regex("""<a href="/title/([^"]+)/" class="hover:underline">([^<]+)</a>""")
            val rawTitles = regex.findAll(section).map { it.groupValues[2].trim() }.toList()
            
            val seen = mutableSetOf<String>()
            val titles = mutableListOf<String>()
            for (title in rawTitles) {
                val clean = title.replace("&amp;", "&")
                if (clean.isNotBlank() && seen.add(clean.lowercase())) {
                    titles.add(clean)
                }
            }
            
            val finalTitles = titles
            
            if (finalTitles.isEmpty()) {
                return fetchRecentRegionalList(fallbackProviderId, isMovie)
            }
            
            coroutineScope {
                finalTitles.map { title ->
                    async {
                        val searchCacheKey = "${title}_${if (isMovie) "movie" else "tv"}"
                        titleSearchCache[searchCacheKey]?.let { return@async it }

                        val encoded = java.net.URLEncoder.encode(title, "UTF-8")
                        val searchUrl = "$TMDB_API_BASE/search/multi?api_key=${getTmdbKey()}&query=$encoded&language=en-US"
                        val searchRes = parsedGet<TMDBDiscoverResponse>(searchUrl)
                        val media = searchRes?.results?.firstOrNull {
                            if (isMovie) it.mediaType == "movie"
                            else it.mediaType == "tv"
                        }
                        if (media != null && !media.posterPath.isNullOrEmpty()) {
                            val titleName = if (media.originalLanguage == "id") {
                                media.originalTitle ?: media.originalName ?: media.title ?: media.name ?: title
                            } else {
                                media.title ?: media.name ?: title
                            }
                            val poster = "https://image.tmdb.org/t/p/w500${media.posterPath}"
                            val res = if (isMovie) {
                                newMovieSearchResponse(
                                    name = titleName,
                                    url = "https://lynk.id/xr3ed#movie::${media.id}",
                                    type = TvType.Movie
                                ) {
                                    this.posterUrl = poster
                                }
                            } else {
                                newTvSeriesSearchResponse(
                                    name = titleName,
                                    url = "https://lynk.id/xr3ed#tv::${media.id}",
                                    type = TvType.TvSeries
                                ) {
                                    this.posterUrl = poster
                                }
                            }
                            titleSearchCache[searchCacheKey] = res
                            res
                        } else {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            fetchRecentRegionalList(fallbackProviderId, isMovie)
        }
        if (result.isNotEmpty()) {
            listCache[cacheKey] = Pair(System.currentTimeMillis(), result)
        }
        return result
    }



    private suspend fun fetchRecentRegionalList(providerId: String, isMovie: Boolean, lang: String? = null): List<SearchResponse> {
        val path = if (isMovie) "discover/movie" else "discover/tv"
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val params = mutableMapOf(
            "with_watch_providers" to providerId,
            "watch_region" to "ID",
            "sort_by" to "popularity.desc"
        )
        if (isMovie) {
            params["primary_release_date.gte"] = "2024-01-01"
            params["primary_release_date.lte"] = today
        } else {
            params["first_air_date.gte"] = "2024-01-01"
            params["first_air_date.lte"] = today
        }
        if (lang != null) {
            params["with_original_language"] = lang
        }
        return fetchTmdbList(path, params)
    }

    private suspend fun fetchRecentSimulatedList(langs: String, isMovie: Boolean): List<SearchResponse> {
        val path = if (isMovie) "discover/movie" else "discover/tv"
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val params = mapOf(
            "sort_by" to "popularity.desc",
            "with_original_language" to langs,
            "first_air_date.gte" to "2024-01-01",
            "first_air_date.lte" to today
        )
        return fetchTmdbList(path, params)
    }

    private suspend fun invokeXpass(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        try {
            val baseRef = "https://play.xpass.top/"
            val embedUrl = if (season == null) {
                "https://play.xpass.top/e/movie/$tmdbId"
            } else {
                "https://play.xpass.top/e/tv/$tmdbId/$season/$episode"
            }

            val htmlResponse = app.get(embedUrl, referer = baseRef, timeout = 8)
            if (htmlResponse.code != 200) return
            val html = htmlResponse.text
            
            val raw = Regex("""var backups=(\[.*?]);""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: return
            val array = org.json.JSONArray(raw)
            val backups = (0 until array.length()).mapNotNull { i ->
                val obj  = array.getJSONObject(i)
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url  = obj.optString("url").takeIf  { it.isNotBlank() } ?: return@mapNotNull null
                if (name.contains("VIP", ignoreCase = true)) {
                    Pair(name, url)
                } else {
                    null
                }
            }

            coroutineScope {
                backups.map { (name, url) ->
                    async {
                        try {
                            val fullUrl = if (url.startsWith("http")) url else "https://play.xpass.top" + url
                            val response = app.get(fullUrl, referer = baseRef, timeout = 8)
                            if (response.code == 200) {
                                val json = response.text
                                val root = org.json.JSONObject(json)
                                val playlist = root.optJSONArray("playlist")
                                val firstPlay = playlist?.optJSONObject(0)
                                val sources = firstPlay?.optJSONArray("sources")
                                if (sources != null) {
                                    for (j in 0 until sources.length()) {
                                        val src = sources.optJSONObject(j) ?: continue
                                        val fileUrl = src.optString("file")
                                        if (fileUrl.isNotEmpty() && fileUrl.startsWith("http")) {
                                            val type = src.optString("type")
                                            val isHls = type.contains("hls", true) || fileUrl.contains(".m3u8")
                                            if (isHls) {
                                                com.lagradost.cloudstream3.utils.M3u8Helper.generateM3u8(
                                                    source = "Xpass - $name",
                                                    streamUrl = fileUrl,
                                                    referer = baseRef
                                                ).forEach { link ->
                                                    callback.invoke(link)
                                                }
                                            } else {
                                                callback.invoke(
                                                    newExtractorLink(
                                                        name = "Xpass - $name",
                                                        source = "Xpass - $name",
                                                        url = fileUrl,
                                                        type = ExtractorLinkType.VIDEO
                                                    ) {
                                                        this.referer = baseRef
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun solvePowChallenge(challenge: String, difficulty: Int): String? {
        val md = MessageDigest.getInstance("SHA-256")
        var nonce = 0L
        val fullBytes = difficulty / 8
        val remainingBits = difficulty % 8
        val mask = (0xff shl (8 - remainingBits)) and 0xff

        while (true) {
            val input = "$challenge$nonce"
            val hash = md.digest(input.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            
            var match = true
            for (i in 0 until fullBytes) {
                if (hash[i].toInt() != 0) {
                    match = false
                    break
                }
            }
            if (match && remainingBits > 0) {
                val byteValue = hash[fullBytes].toInt() and 0xff
                if ((byteValue and mask) != 0) {
                    match = false
                }
            }
            
            if (match) {
                return nonce.toString()
            }
            nonce++
            md.reset()
            if (nonce > 10_000_000) return null
        }
    }

    private suspend fun invokeMapple(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        try {
            val base = "https://mapple.rip"
            val mediaType = if (season == null) "movie" else "tv"
            val tvSlug = if (season != null && episode != null) "$season-$episode" else ""
            val idStr = if (mediaType == "movie") tmdbId.toString() else "$tmdbId/$tvSlug"

            // 1. Let's request the home page first to set the session cookies
            val homeRequest = okhttp3.Request.Builder()
                .url(base)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()
            cleanClient.newCall(homeRequest).execute().close()

            val getHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "en-US,en;q=0.9",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-User" to "?1",
                "Sec-Ch-Ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                "Sec-Ch-Ua-Mobile" to "?0",
                "Sec-Ch-Ua-Platform" to "\"Windows\""
            )

            // 2. Let's request the embed page with exact Chrome headers to extract requestToken
            val embedUrl = "$base/embed/$mediaType/$idStr"
            val embedRequest = okhttp3.Request.Builder()
                .url(embedUrl)
                .headers(getHeaders.toHeaders())
                .build()
            val embedRes = cleanClient.newCall(embedRequest).execute()
            if (embedRes.code != 200) {
                embedRes.close()
                return
            }
            val embedHtml = embedRes.body?.string() ?: ""
            embedRes.close()

            val tokenRegex = Regex("""window\.__REQUEST_TOKEN__\s*=\s*\\?"([^"\\]+)\\?"""")
            val requestToken = tokenRegex.find(embedHtml)?.groupValues?.get(1) ?: return

            // 3. Setup postHeaders for API POST request
            val postHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to "$base/embed/$mediaType/$idStr",
                "Origin" to base,
                "Accept" to "application/json, text/plain, */*",
                "Content-Type" to "application/json",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "x-request-token" to requestToken
            )

            // 4. First POST to /api/playback-init to get PoW challenge
            val initBody = if (mediaType == "movie") {
                """
                {
                    "mediaId": $tmdbId,
                    "mediaType": "movie",
                    "requestToken": "$requestToken"
                }
                """.trimIndent()
            } else {
                """
                {
                    "mediaId": $tmdbId,
                    "mediaType": "tv",
                    "season": $season,
                    "episode": $episode,
                    "requestToken": "$requestToken"
                }
                """.trimIndent()
            }

            val postMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val initRequest = okhttp3.Request.Builder()
                .url("$base/api/playback-init")
                .post(initBody.toRequestBody(postMediaType))
                .headers(postHeaders.toHeaders())
                .build()

            val initResponse = cleanClient.newCall(initRequest).execute()
            if (initResponse.code != 200) {
                initResponse.close()
                return
            }
            val initResText = initResponse.body?.string() ?: ""
            initResponse.close()

            val initJson = org.json.JSONObject(initResText)
            if (!initJson.optBoolean("success")) return

            val finalToken = if (initJson.optBoolean("requiresPow")) {
                val pow = initJson.getJSONObject("pow")
                val challenge = pow.getString("challenge")
                val difficulty = pow.getInt("difficulty")
                val nonce = solvePowChallenge(challenge, difficulty) ?: return

                val solveBody = if (mediaType == "movie") {
                    """
                    {
                        "mediaId": $tmdbId,
                        "mediaType": "movie",
                        "requestToken": "$requestToken",
                        "pow": {
                            "challengeId": "${pow.getString("challengeId")}",
                            "nonce": "$nonce"
                        }
                    }
                    """.trimIndent()
                } else {
                    """
                    {
                        "mediaId": $tmdbId,
                        "mediaType": "tv",
                        "season": $season,
                        "episode": $episode,
                        "requestToken": "$requestToken",
                        "pow": {
                            "challengeId": "${pow.getString("challengeId")}",
                            "nonce": "$nonce"
                        }
                    }
                    """.trimIndent()
                }

                val solveRequest = okhttp3.Request.Builder()
                    .url("$base/api/playback-init")
                    .post(solveBody.toRequestBody(postMediaType))
                    .headers(postHeaders.toHeaders())
                    .build()

                val solveResponse = cleanClient.newCall(solveRequest).execute()
                if (solveResponse.code != 200) {
                    solveResponse.close()
                    return
                }
                val solveResText = solveResponse.body?.string() ?: ""
                solveResponse.close()

                val solveJson = org.json.JSONObject(solveResText)
                if (!solveJson.optBoolean("success")) return
                solveJson.getString("token")
            } else {
                initJson.getString("token")
            }

            // 5. GET streams for each source
            val sources = listOf(
                "mapple", "willow", "cherry", "pines", "oak", "sequoia", "sakura", "magnolia"
            )

            coroutineScope {
                sources.map { source ->
                    async {
                        try {
                            val streamUrl = "$base/api/stream?mediaId=$tmdbId&mediaType=$mediaType&tv_slug=$tvSlug" +
                                    "&source=$source&apikey=mptv_sk_a8f29c4e7b3d1f" +
                                    "&requestToken=$requestToken&token=$finalToken"

                            val streamRequest = okhttp3.Request.Builder()
                                .url(streamUrl)
                                .headers(postHeaders.toHeaders())
                                .build()

                            val streamResponse = cleanClient.newCall(streamRequest).execute()
                            if (streamResponse.code == 200) {
                                val streamResText = streamResponse.body?.string() ?: ""
                                streamResponse.close()
                                val streamRes = org.json.JSONObject(streamResText)
                                if (streamRes.optBoolean("success")) {
                                    val m3u8 = streamRes.getJSONObject("data").optString("stream_url")
                                    if (m3u8.isNotEmpty()) {
                                        com.lagradost.cloudstream3.utils.M3u8Helper.generateM3u8(
                                            source = "Mapple - ${source.uppercase()}",
                                            streamUrl = m3u8,
                                            referer = "$base/",
                                            headers = postHeaders
                                        ).forEach(callback)
                                    }
                                }
                            } else {
                                streamResponse.close()
                            }
                        } catch (ex: Exception) {
                            Log.e("xr3edFlix", "Mapple source=$source failed", ex)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("xr3edFlix", "Mapple error", e)
        }
    }

    private suspend fun fetchCategory(data: String): HomePageList? {
        return when (data) {
            "Film Trending" -> HomePageList("Film Trending", fetchTmdbList("trending/movie/day", emptyMap()))
            "Film Populer" -> HomePageList("Film Populer", fetchTmdbList("discover/movie", mapOf("sort_by" to "popularity.desc")))
            "Seri Trending" -> HomePageList("Seri Trending", fetchTmdbList("trending/tv/day", emptyMap()))
            "Seri Populer" -> HomePageList("Seri Populer", fetchTmdbList("discover/tv", mapOf("sort_by" to "popularity.desc")))
            "Netflix Movies" -> HomePageList("Netflix Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/netflix/indonesia/", true, "8", "discover/movie"))
            "Netflix Series" -> HomePageList("Netflix Series", fetchFlixPatrolList("https://flixpatrol.com/top10/netflix/indonesia/", false, "8", "discover/tv"))
            "Disney+ Movies" -> HomePageList("Disney+ Movies", fetchRecentRegionalList("122", true))
            "Disney+ Series" -> HomePageList("Disney+ Series", fetchRecentRegionalList("122", false))
            "Prime Video Movies" -> HomePageList("Prime Video Movies", fetchRecentRegionalList("119", true))
            "Prime Video Series" -> HomePageList("Prime Video Series", fetchRecentRegionalList("119", false))
            "Apple TV+ Movies" -> HomePageList("Apple TV+ Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/apple-tv/indonesia/", true, "350", "discover/movie"))
            "Apple TV+ Series" -> HomePageList("Apple TV+ Series", fetchFlixPatrolList("https://flixpatrol.com/top10/apple-tv/indonesia/", false, "350", "discover/tv"))
            "iTunes Store Movies" -> HomePageList("iTunes Store Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/itunes/indonesia/", true, "350", "discover/movie"))
            "Viu Series" -> {
                val viuKo = fetchRecentRegionalList("158", false, "ko")
                val viuId = fetchRecentRegionalList("158", false, "id")
                val combined = mutableListOf<SearchResponse>()
                val maxLen = maxOf(viuKo.size, viuId.size)
                for (i in 0 until maxLen) {
                    if (i < viuId.size) combined.add(viuId[i])
                    if (i < viuKo.size) combined.add(viuKo[i])
                }
                HomePageList("Viu Series", combined)
            }
            "Vidio Movies" -> HomePageList("Vidio Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/vidio/indonesia/", true, "489", "discover/movie"))
            "Vidio Series" -> HomePageList("Vidio Series", fetchFlixPatrolList("https://flixpatrol.com/top10/vidio/indonesia/", false, "489", "discover/tv"))
            "HBO GO Movies" -> HomePageList("HBO GO Movies", fetchRecentRegionalList("1899", true))
            "HBO GO Series" -> HomePageList("HBO GO Series", fetchRecentRegionalList("1899", false))
            "Catchplay+ Movies" -> HomePageList("Catchplay+ Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/catchplay/indonesia/", true, "159", "discover/movie"))
            "Catchplay+ Series" -> HomePageList("Catchplay+ Series", fetchFlixPatrolList("https://flixpatrol.com/top10/catchplay/indonesia/", false, "159", "discover/tv"))
            "Crunchyroll Series" -> HomePageList("Crunchyroll Series", fetchRecentRegionalList("283", false))
            "Lionsgate Play Movies" -> HomePageList("Lionsgate Play Movies", fetchRecentRegionalList("561", true))
            "Lionsgate Play Series" -> HomePageList("Lionsgate Play Series", fetchRecentRegionalList("561", false))
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val targetData = request.data
        
        if (targetData.isNotEmpty() && targetData != "beranda") {
            val single = fetchCategory(targetData)
            return if (single != null && single.list.isNotEmpty()) newHomePageResponse(listOf(single), false) else null
        }
        
        val lists = coroutineScope {
            val trendingMovies = async { fetchCategory("Film Trending") }
            val popularMovies = async { fetchCategory("Film Populer") }
            val trendingSeries = async { fetchCategory("Seri Trending") }
            val popularSeries = async { fetchCategory("Seri Populer") }
            val netflixMovies = async { fetchCategory("Netflix Movies") }
            val netflixSeries = async { fetchCategory("Netflix Series") }
            val disneyMovies = async { fetchCategory("Disney+ Movies") }
            val disneySeries = async { fetchCategory("Disney+ Series") }
            val primeMovies = async { fetchCategory("Prime Video Movies") }
            val primeSeries = async { fetchCategory("Prime Video Series") }
            val appleMovies = async { fetchCategory("Apple TV+ Movies") }
            val appleSeries = async { fetchCategory("Apple TV+ Series") }
            val itunesMovies = async { fetchCategory("iTunes Store Movies") }
            val viuSeries = async { fetchCategory("Viu Series") }
            val vidioMovies = async { fetchCategory("Vidio Movies") }
            val vidioSeries = async { fetchCategory("Vidio Series") }
            val hboMovies = async { fetchCategory("HBO GO Movies") }
            val hboSeries = async { fetchCategory("HBO GO Series") }
            val catchplayMovies = async { fetchCategory("Catchplay+ Movies") }
            val catchplaySeries = async { fetchCategory("Catchplay+ Series") }
            val crunchyrollSeries = async { fetchCategory("Crunchyroll Series") }
            val lionsgateMovies = async { fetchCategory("Lionsgate Play Movies") }
            val lionsgateSeries = async { fetchCategory("Lionsgate Play Series") }
 
            listOfNotNull(
                trendingMovies.await(), popularMovies.await(),
                trendingSeries.await(), popularSeries.await(),
                netflixMovies.await(), netflixSeries.await(),
                disneyMovies.await(), disneySeries.await(),
                primeMovies.await(), primeSeries.await(),
                appleMovies.await(), appleSeries.await(),
                itunesMovies.await(),
                viuSeries.await(),
                vidioMovies.await(), vidioSeries.await(),
                hboMovies.await(), hboSeries.await(),
                catchplayMovies.await(), catchplaySeries.await(),
                crunchyrollSeries.await(),
                lionsgateMovies.await(), lionsgateSeries.await()
            ).filter { it.list.isNotEmpty() }
        }
        
        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$TMDB_API_BASE/search/multi?api_key=${getTmdbKey()}&query=$encoded&language=en-US"
        val res = parsedGet<TMDBDiscoverResponse>(url)
        return res?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" }?.map { media ->
            val title = if (media.originalLanguage == "id") {
                media.originalTitle ?: media.originalName ?: media.title ?: media.name ?: "Unknown"
            } else {
                media.title ?: media.name ?: "Unknown"
            }
            val isMovie = media.mediaType == "movie"
            val poster = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            if (isMovie) {
                newMovieSearchResponse(
                    name = title,
                    url = "https://lynk.id/xr3ed#movie::${media.id}",
                    type = TvType.Movie
                ) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(
                    name = title,
                    url = "https://lynk.id/xr3ed#tv::${media.id}",
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = poster
                }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        addedUrls.clear()
        
        var targetUrl = url
        if (targetUrl.contains("lynk.id")) {
            targetUrl = targetUrl.substringAfterLast("#", "")
        }

        // Cloudstream prepend mainUrl, jadi url bisa "https://watch-v2.autoembed.app/movie::123"
        // Strip mainUrl prefix jika ada
        val cleanUrl = if (targetUrl.contains("://") && targetUrl.contains("::")) {
            // Ambil bagian "movie::123" atau "tv::123" dari URL
            val slashIdx = targetUrl.lastIndexOf('/', targetUrl.indexOf("::"))
            if (slashIdx != -1) targetUrl.substring(slashIdx + 1) else targetUrl
        } else targetUrl

        val parts = cleanUrl.split("::")
        if (parts.size < 2) return null
        val type = parts[0]
        val id = parts[1]
        Log.d("xr3edFlix", "load() url=$url cleanUrl=$cleanUrl type=$type id=$id")

        if (type == "fake-movie" || type == "fake-tv") {
            val title = parts.getOrNull(2) ?: "Unknown"
            return if (type == "fake-movie") {
                newMovieLoadResponse(
                    name = title,
                    url = url,
                    type = TvType.Movie,
                    dataUrl = url
                ) {
                    this.plot = "Konten tidak terdaftar di database TMDB."
                }
            } else {
                val dummyEpisodes = listOf(
                    newEpisode("fake-tv::0::$title::1::1") {
                        this.name = "Episode 1"
                        this.episode = 1
                        this.season = 1
                    }
                )
                newTvSeriesLoadResponse(
                    name = title,
                    url = url,
                    type = TvType.TvSeries,
                    episodes = dummyEpisodes
                ) {
                    this.plot = "Konten tidak terdaftar di database TMDB."
                }
            }
        }

        if (type == "movie") {
            // Fetch dengan credits sekaligus
            val detailUrlId = "$TMDB_API_BASE/movie/$id?api_key=${getTmdbKey()}&language=id&append_to_response=credits"
            val resId = parsedGet<TMDBDetailResponse>(detailUrlId)
            
            // Cek apakah original_language bukan 'id'/'en' dan title sama dengan original_title (artinya tidak ada terjemahan id/en)
            val needsEnglishFallback = resId?.let {
                val originalLang = it.originalLanguage ?: "en"
                originalLang != "id" && originalLang != "en" && it.title == it.originalTitle
            } ?: false

            val res = if (resId?.title.isNullOrEmpty() || needsEnglishFallback) {
                val detailUrlEn = "$TMDB_API_BASE/movie/$id?api_key=${getTmdbKey()}&language=en-US&append_to_response=credits"
                parsedGet<TMDBDetailResponse>(detailUrlEn) ?: resId
            } else resId
            res ?: return null

            // Fallback plot ke en-US jika overview kosong di id
            val plot = if (res.overview.isNullOrEmpty()) {
                val enRes = parsedGet<TMDBDetailResponse>("$TMDB_API_BASE/movie/$id?api_key=${getTmdbKey()}&language=en-US")
                enRes?.overview?.ifEmpty { null }
            } else res.overview

            val poster = res.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdrop = res.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
            val actors = res.credits?.cast?.take(10)?.mapNotNull { cast ->
                if (cast.name != null) ActorData(
                    actor = Actor(
                        name = cast.name,
                        image = cast.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                    ),
                    roleString = cast.character
                ) else null
            }

            val imdbId = res.imdbId ?: ""
            val cleanTitle = (res.originalTitle ?: res.title ?: "Unknown").replace("::", ":")

            return newMovieLoadResponse(
                name = res.title ?: "Unknown",
                url = "https://lynk.id/xr3ed#movie::$id",
                type = TvType.Movie,
                dataUrl = "movie::$id::$imdbId::$cleanTitle"
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = res.releaseDate?.take(4)?.toIntOrNull()
                this.actors = actors
            }
        } else {
            val detailUrlId = "$TMDB_API_BASE/tv/$id?api_key=${getTmdbKey()}&language=id&append_to_response=credits,external_ids"
            val resId = parsedGet<TMDBDetailResponse>(detailUrlId)
            
            // Cek apakah original_language bukan 'id'/'en' dan name sama dengan original_name (artinya tidak ada terjemahan id/en)
            val needsEnglishFallback = resId?.let {
                val originalLang = it.originalLanguage ?: "en"
                originalLang != "id" && originalLang != "en" && it.name == it.originalName
            } ?: false

            val res = if (resId?.name.isNullOrEmpty() || needsEnglishFallback) {
                val detailUrlEn = "$TMDB_API_BASE/tv/$id?api_key=${getTmdbKey()}&language=en-US&append_to_response=credits,external_ids"
                parsedGet<TMDBDetailResponse>(detailUrlEn) ?: resId
            } else resId
            res ?: return null

            val plot = if (res.overview.isNullOrEmpty()) {
                val enRes = parsedGet<TMDBDetailResponse>("$TMDB_API_BASE/tv/$id?api_key=${getTmdbKey()}&language=en-US")
                enRes?.overview?.ifEmpty { null }
            } else res.overview

            val poster = res.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdrop = res.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
            val actors = res.credits?.cast?.take(10)?.mapNotNull { cast ->
                if (cast.name != null) ActorData(
                    actor = Actor(
                        name = cast.name,
                        image = cast.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                    ),
                    roleString = cast.character
                ) else null
            }

            val imdbId = res.imdbId ?: res.externalIds?.imdbId ?: ""
            val cleanTitle = (res.originalName ?: res.name ?: "Unknown").replace("::", ":")

            val episodes = coroutineScope {
                res.seasons?.map { season ->
                    async {
                        val seasonNum = season.seasonNumber ?: 1
                        // Coba bahasa id dulu, fallback ke en-US
                        val seasonUrlId = "$TMDB_API_BASE/tv/$id/season/$seasonNum?api_key=${getTmdbKey()}&language=id"
                        val seasonResId = parsedGet<TMDBSeasonDetailResponse>(seasonUrlId)
                        val seasonRes = if (seasonResId?.episodes.isNullOrEmpty()) {
                            val seasonUrlEn = "$TMDB_API_BASE/tv/$id/season/$seasonNum?api_key=${getTmdbKey()}&language=en-US"
                            parsedGet<TMDBSeasonDetailResponse>(seasonUrlEn) ?: seasonResId
                        } else seasonResId
                        seasonRes?.episodes?.map { ep ->
                            newEpisode("tv::$id::${ep.seasonNumber}::${ep.episodeNumber}::$imdbId::$cleanTitle") {
                                this.name = ep.name ?: "Episode ${ep.episodeNumber}"
                                this.episode = ep.episodeNumber
                                this.season = ep.seasonNumber
                                this.description = ep.overview
                            }
                        } ?: emptyList()
                    }
                }?.awaitAll()?.flatten() ?: emptyList()
            }

            return newTvSeriesLoadResponse(
                name = res.name ?: "Unknown",
                url = "https://lynk.id/xr3ed#tv::$id",
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.actors = actors
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("::")
        if (parts.size < 2) return false
        val type = parts[0]
        if (type.startsWith("fake-")) return false
        val id = parts[1]
        val imdbId = if (type == "movie") parts.getOrNull(2) else parts.getOrNull(4)
        val title = if (type == "movie") parts.getOrNull(3) else parts.getOrNull(5)

        val indonesianCount = java.util.concurrent.atomic.AtomicInteger(0)
        val hasIndonesian = java.util.concurrent.atomic.AtomicBoolean(false)
        val subCallback = { subFile: SubtitleFile ->
            val lang = subFile.lang.lowercase().trim()
            if (lang.contains("indonesia") || lang.contains("indo") || lang == "ind" || lang == "id" || lang == "in" || lang.startsWith("ind-") || lang.startsWith("id-") || lang.startsWith("in-")) {
                hasIndonesian.set(true)
                val count = indonesianCount.getAndIncrement()
                val label = if (count == 0) "Indonesia" else "Indonesia ${count + 1}"
                subtitleCallback.invoke(subFile.copy(lang = label))
            }
        }
        val wrappedCallback = { link: ExtractorLink ->
            val updatedLink = if (link.quality == Qualities.Unknown.value || link.quality == 0) {
                val inferredQuality = when {
                    link.name.contains("1080") || link.url.contains("1080") -> Qualities.P1080.value
                    link.name.contains("720") || link.url.contains("720") -> Qualities.P720.value
                    link.name.contains("480") || link.url.contains("480") -> Qualities.P480.value
                    link.name.contains("360") || link.url.contains("360") -> Qualities.P360.value
                    else -> Qualities.P1080.value
                }
                
                val qualityLabel = when (inferredQuality) {
                    Qualities.P1080.value -> "1080p"
                    Qualities.P720.value -> "720p"
                    Qualities.P480.value -> "480p"
                    Qualities.P360.value -> "360p"
                    else -> "1080p"
                }
                val newName = if (!link.name.contains("p", ignoreCase = true) && !link.name.contains("1080") && !link.name.contains("720")) {
                    "${link.name} - $qualityLabel"
                } else {
                    link.name
                }
                ExtractorLink(
                    source = link.source,
                    name = newName,
                    url = link.url,
                    referer = link.referer,
                    quality = inferredQuality,
                    type = link.type,
                    headers = link.headers
                )
            } else {
                link
            }
            callback.invoke(updatedLink)
        }


        val subpath = if (type == "movie") {
            "movie/$id"
        } else {
            val season = parts.getOrNull(2) ?: "1"
            val episode = parts.getOrNull(3) ?: "1"
            "tv/$id/$season/$episode"
        }

        // List of all endpoints to query
        val endpoints = listOf(
            "Catflix" to "$API_BASE/movies5f/$subpath",
            "Prime" to "$API_BASE/hollymoviehd/$subpath",
            "Gama" to "$API_BASE/moviebox/$subpath"
        )

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://vidnest.fun/",
            "Origin" to "https://vidnest.fun"
        )

        var foundAny = false

        val season = parts.getOrNull(2) ?: "1"
        val episode = parts.getOrNull(3) ?: "1"

        val extraExtractors = if (type == "movie") {
            listOf(
                "https://vidsrc.net/embed/movie/$id",
                "https://vidsrc.pm/embed/movie/$id",
                "https://autoembed.to/movie/$id",
                "https://vidsrc.to/embed/movie/$id",
                "https://vidsrc.me/embed/$id",
                "https://vidsrc.in/embed/movie/$id",
                "https://vidlink.pro/embed/movie/$id",
                "https://vidsrc.cc/v2/embed/movie/$id",
                "https://vidsrc.rip/embed/movie/$id",
                "https://vidsrc.vip/embed/movie/$id",
                "https://www.2embed.cc/embed/$id"
            )
        } else {
            listOf(
                "https://vidsrc.net/embed/tv/$id/$season/$episode",
                "https://vidsrc.pm/embed/tv/$id/$season/$episode",
                "https://autoembed.to/tv/$id/$season/$episode",
                "https://vidsrc.to/embed/tv/$id/$season/$episode",
                "https://vidsrc.me/embed/$id/$season-$episode",
                "https://vidsrc.in/embed/tv/$id/$season/$episode",
                "https://vidlink.pro/embed/tv/$id/$season/$episode",
                "https://vidsrc.cc/v2/embed/tv/$id/$season/$episode",
                "https://vidsrc.rip/embed/tv/$id/$season/$episode",
                "https://vidsrc.vip/embed/tv/$id/$season/$episode",
                "https://www.2embed.cc/embed/$id?s=$season&e=$episode"
            )
        }

        coroutineScope {
            val jobs = endpoints.map { (serverName, url) ->
                async {
                    try {
                        val response = app.get(url, headers = headers, timeout = 8)
                        if (response.code == 200) {
                            val text = response.text
                            if (text.contains("encrypted")) {
                                val encryptedObj = mapper.readValue(text, EncryptedResponse::class.java)
                                val cipher = encryptedObj.data
                                if (!cipher.isNullOrEmpty()) {
                                    val decrypted = decrypt(cipher)
                                    Log.d("xr3edFlix", "$serverName decrypted: ${decrypted.take(200)}")

                                    // Parse fleksibel dengan JsonNode
                                    val root = mapper.readTree(decrypted)
                                    val dataNode = if (root.has("data") && root.get("data").isObject) root.get("data") else root

                                    suspend fun addLink(
                                        linkUrl: String, 
                                        name: String, 
                                        quality: Int, 
                                        isHls: Boolean,
                                        customHeaders: Map<String, String>? = null
                                    ) {
                                        if (!addedUrls.add(linkUrl)) return
                                        val link = newExtractorLink(
                                            name = name,
                                            source = serverName,
                                            url = linkUrl,
                                            type = if (isHls || linkUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) { 
                                            this.quality = quality 
                                             val defaultHeaders = when (serverName) {
                                                 "Catflix" -> mapOf(
                                                     "Origin" to "https://fmoviesunblocked.net",
                                                     "Referer" to "https://fmoviesunblocked.net/"
                                                 )
                                                 else -> emptyMap()
                                             }
                                             this.headers = if (serverName == "Gama" || serverName == "Prime") {
                                                 emptyMap()
                                             } else {
                                                 if (!customHeaders.isNullOrEmpty()) customHeaders else defaultHeaders
                                             }
                                        }
                                        wrappedCallback.invoke(link)
                                        foundAny = true
                                    }

                                    // Format 1: url array (MovieBox: [{lang,link,resolution,type}])
                                    val urlNode = dataNode.get("url")
                                    if (urlNode != null && urlNode.isArray) {
                                        urlNode.forEach { item ->
                                            val link = item.get("link")?.asText()
                                            val res = item.get("resolution")?.asText() ?: ""
                                            val typ = item.get("type")?.asText() ?: ""
                                            val lang = item.get("lang")?.asText() ?: ""
                                            if (!link.isNullOrEmpty()) {
                                                val q = when { res.contains("1080") -> Qualities.P1080.value; res.contains("720") -> Qualities.P720.value; res.contains("480") -> Qualities.P480.value; res.contains("360") -> Qualities.P360.value; else -> Qualities.Unknown.value }
                                                addLink(link, "$serverName - $res ${if(lang.isNotEmpty()) "[$lang]" else ""}", q, typ == "hls" || link.contains("m3u8"), emptyMap())
                                            }
                                        }
                                    } else if (urlNode != null && urlNode.isTextual) {
                                        // Format url: string langsung
                                        val u = urlNode.asText()
                                        if (u.isNotEmpty()) addLink(u, "$serverName - HD", Qualities.P1080.value, u.contains("m3u8"))
                                    }

                                     // Format 2: sources[] (klikxxi: [{url,type,quality}])
                                     val sourcesNode = dataNode.get("sources")
                                     if (sourcesNode != null && sourcesNode.isArray) {
                                         sourcesNode.forEach { item ->
                                             val u = item.get("url")?.asText()
                                             val typ = item.get("type")?.asText() ?: ""
                                             val qual = item.get("quality")?.asText() ?: "auto"
                                             if (!u.isNullOrEmpty()) {
                                                 if (u.contains("multimovies.rpmhub.site")) {
                                                     try {
                                                         val hash = u.substringAfter("#")
                                                         if (hash.isNotEmpty() && hash != u) {
                                                             val playerUrl = "https://multimovies.rpmhub.site/api/v1/video?id=$hash&w=1920&h=1080&r="
                                                             val ophimHeaders = mapOf(
                                                                 "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                                                 "Referer" to "https://multimovies.rpmhub.site/",
                                                                 "Origin" to "https://multimovies.rpmhub.site"
                                                             )
                                                             val apiRes = app.get(playerUrl, headers = ophimHeaders, timeout = 8)
                                                             if (apiRes.code == 200) {
                                                                 val decodedText = decodeDecimalString(apiRes.text)
                                                                 if (decodedText.isNotEmpty() && !decodedText.contains("error")) {
                                                                     val decrypted = decryptOphimAes(decodedText)
                                                                     val innerRoot = mapper.readTree(decrypted)
                                                                     val innerData = if (innerRoot.has("data")) innerRoot.get("data") else innerRoot
                                                                     val innerSources = innerData.get("sources")
                                                                     if (innerSources != null && innerSources.isArray) {
                                                                         innerSources.forEach { innerItem ->
                                                                             val streamUrl = innerItem.get("url")?.asText()
                                                                             val streamType = innerItem.get("type")?.asText() ?: ""
                                                                             val streamQual = innerItem.get("quality")?.asText() ?: "auto"
                                                                             if (!streamUrl.isNullOrEmpty()) {
                                                                                 val q = when { streamQual.contains("1080") -> Qualities.P1080.value; streamQual.contains("720") -> Qualities.P720.value; streamQual.contains("480") -> Qualities.P480.value; streamQual.contains("360") -> Qualities.P360.value; else -> Qualities.Unknown.value }
                                                                                 addLink(streamUrl, "$serverName - $streamQual", q, streamType == "hls" || streamUrl.contains("m3u8"), emptyMap())
                                                                             }
                                                                         }
                                                                     }
                                                                 } else {
                                                                     Log.w("xr3edFlix", "Ophim token/decode error: $decodedText")
                                                                 }
                                                             }
                                                         }
                                                     } catch (e: Exception) {
                                                         Log.e("xr3edFlix", "Ophim decrypt failed", e)
                                                     }
                                                 } else {
                                                     val q = when { qual.contains("1080") -> Qualities.P1080.value; qual.contains("720") -> Qualities.P720.value; qual.contains("480") -> Qualities.P480.value; qual.contains("360") -> Qualities.P360.value; else -> Qualities.Unknown.value }
                                                     addLink(u, "$serverName - $qual", q, typ == "hls" || u.contains("m3u8"))
                                                 }
                                             }
                                         }
                                     }

                                    // Format 3: StreamResponse standard (vidnest)
                                    val streamRes = try { mapper.readValue(decrypted, StreamResponse::class.java) } catch(e: Exception) { null }
                                    val streamData = streamRes?.data ?: streamRes
                                    val headersMap = streamData?.headers

                                    for (dl in streamData?.downloads ?: emptyList()) {
                                        if (dl.url != null) addLink(dl.url, "$serverName - ${dl.resolution}p", dl.resolution ?: Qualities.Unknown.value, false, headersMap)
                                    }
                                    for (u in streamData?.urlList ?: emptyList()) {
                                        if (u.link != null) {
                                            val q = u.resolution?.replace("p","")?.toIntOrNull() ?: Qualities.Unknown.value
                                            addLink(u.link, "$serverName - ${u.resolution ?: "HLS"}", q, u.type == "hls" || u.link.contains("m3u8"), headersMap)
                                        }
                                    }
                                     for (str in streamData?.streams ?: emptyList()) {
                                         if (str.url != null) {
                                              if (serverName != "Prime" || str.language == "MAIN") {
                                                  val isPrimeMain = serverName == "Prime" && str.language == "MAIN"
                                                  val nameLabel = if (isPrimeMain) "$serverName - 1440p" else "$serverName - ${str.language ?: "HLS"}"
                                                  val q = if (isPrimeMain) 1440 else Qualities.Unknown.value
                                                  addLink(str.url, nameLabel, q, str.type == "hls" || str.url.contains("m3u8"), headersMap)
                                              }
                                         }
                                     }

                                     // Captions
                                     for (sub in streamData?.captions ?: emptyList()) {
                                         if (sub.url != null) {
                                             val subFile = newSubtitleFile(sub.lanName ?: sub.lan ?: "Unknown", sub.url)
                                             subCallback.invoke(subFile)
                                         }
                                     }
                                }
                            }
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } + extraExtractors.map { embedUrl ->
                async {
                    try {
                        var currentUrl = embedUrl
                        var resolved = false
                        var hops = 0
                        while (hops < 3 && !resolved) {
                            resolved = loadExtractor(currentUrl, subCallback, wrappedCallback)
                            if (resolved) break
                            
                            // Fetch HTML to find next iframe
                            val response = app.get(currentUrl, timeout = 8)
                            if (response.code != 200) break
                            
                            val html = response.text
                            val iframeRegex = """iframe[^>]+src=["']([^"']+)["']""".toRegex()
                            val iframeSrc = iframeRegex.find(html)?.groups?.get(1)?.value
                            if (iframeSrc.isNullOrEmpty()) break
                            
                            currentUrl = when {
                                iframeSrc.startsWith("//") -> "https:$iframeSrc"
                                iframeSrc.startsWith("/") -> {
                                    val uri = java.net.URI(currentUrl)
                                    "${uri.scheme}://${uri.host}$iframeSrc"
                                }
                                else -> iframeSrc
                            }
                            hops++
                        }
                        if (resolved) {
                            foundAny = true
                        }
                    } catch (e: Exception) {
                        Log.e("xr3edFlix", "loadExtractor failed for: $embedUrl - ${e.message}")
                    }
                }
            } + listOf(
                async {
                    try {
                        val vaplayerUrl = if (type == "movie") {
                            "https://streamdata.vaplayer.ru/api.php?tmdb=$id&type=movie"
                        } else {
                            "https://streamdata.vaplayer.ru/api.php?tmdb=$id&type=tv&season=$season&episode=$episode"
                        }
                        val apiHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer" to "https://nextgencloudfabric.com/",
                            "Origin" to "https://nextgencloudfabric.com"
                        )
                        val apiRes = app.get(vaplayerUrl, headers = apiHeaders, timeout = 10)
                        if (apiRes.code == 200) {
                            val root = mapper.readTree(apiRes.text)
                            
                            // Load subtitles
                            val defaultSubs = root.get("default_subs")
                            if (defaultSubs != null && defaultSubs.isArray) {
                                defaultSubs.forEach { subNode ->
                                    val lang = subNode.get("lang")?.asText() ?: subNode.get("code")?.asText() ?: "Unknown"
                                    val subUrl = subNode.get("url")?.asText()
                                    if (!subUrl.isNullOrEmpty()) {
                                        subCallback.invoke(newSubtitleFile(lang, subUrl))
                                    }
                                }
                            }

                            val dataNode = root.get("data")
                            if (dataNode != null) {
                                val streamUrls = dataNode.get("stream_urls")
                                if (streamUrls != null && streamUrls.isArray) {
                                    streamUrls.forEach { uNode ->
                                        val streamUrl = uNode.asText()
                                        if (!streamUrl.isNullOrEmpty()) {
                                            try {
                                                val manifestRes = app.get(streamUrl, headers = apiHeaders, timeout = 6)
                                                if (manifestRes.code == 200 && manifestRes.text.contains("#EXT-X-STREAM-INF")) {
                                                    val uri = java.net.URI(streamUrl)
                                                    val hostUrl = "${uri.scheme}://${uri.host}"
                                                    val lines = manifestRes.text.split("\n")
                                                    var currentRes = ""
                                                    var parsedAny = false
                                                    for (line in lines) {
                                                        val trimmed = line.trim()
                                                        if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                                                            val resRegex = """RESOLUTION=(\d+)x(\d+)""".toRegex()
                                                            val match = resRegex.find(trimmed)
                                                            if (match != null) {
                                                                val height = match.groupValues[2].toInt()
                                                                val standardHeight = when {
                                                                    height >= 800 -> 1080
                                                                    height >= 500 -> 720
                                                                    height >= 350 -> 480
                                                                    else -> 360
                                                                }
                                                                currentRes = "${standardHeight}p"
                                                            }
                                                        } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                                            if (currentRes.isNotEmpty()) {
                                                                val absoluteUrl = if (trimmed.startsWith("/")) {
                                                                    hostUrl + trimmed
                                                                } else if (trimmed.startsWith("http")) {
                                                                    trimmed
                                                                } else {
                                                                    val parentPath = streamUrl.substring(0, streamUrl.lastIndexOf('/') + 1)
                                                                    parentPath + trimmed
                                                                }
                                                                 val dedupKey = if (absoluteUrl.contains("/index.m3u8")) {
                                                                     absoluteUrl.substringBefore("/index.m3u8").substringAfterLast("/")
                                                                 } else {
                                                                     absoluteUrl
                                                                 }
                                                                 if (addedUrls.add(dedupKey)) {
                                                                     val q = currentRes.replace("p","").toIntOrNull() ?: Qualities.Unknown.value
                                                                     val link = newExtractorLink(
                                                                         name = "Vaplayer - $currentRes",
                                                                         source = "Vaplayer",
                                                                         url = absoluteUrl,
                                                                         type = ExtractorLinkType.M3U8
                                                                     ) {
                                                                         this.quality = q
                                                                         this.headers = emptyMap()
                                                                     }
                                                                     wrappedCallback.invoke(link)
                                                                     parsedAny = true
                                                                 }
                                                                 currentRes = ""
                                                            }
                                                        }
                                                    }
                                                    if (parsedAny) {
                                                        foundAny = true
                                                        return@forEach
                                                    }
                                                }
                                            } catch (manifestEx: Exception) {
                                                Log.e("xr3edFlix", "manifest parse failed", manifestEx)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("xr3edFlix", "vaplayer query failed", e)
                    }
                },
                async {
                    try {
                        val seasonNum = if (type == "movie") null else (season.toIntOrNull() ?: 1)
                        val episodeNum = if (type == "movie") null else (episode.toIntOrNull() ?: 1)
                        invokeMovieBox(title, seasonNum, episodeNum, subCallback, wrappedCallback)
                    } catch (e: Exception) {
                        Log.e("xr3edFlix", "MovieBox query failed", e)
                    }
                },
                async {
                    try {
                        val seasonNum = if (type == "movie") null else (season.toIntOrNull() ?: 1)
                        val episodeNum = if (type == "movie") null else (episode.toIntOrNull() ?: 1)
                        invokeMultimovies(title, seasonNum, episodeNum, subCallback, wrappedCallback)
                    } catch (e: Exception) {
                        Log.e("xr3edFlix", "Multimovies query failed", e)
                    }
                },
                async {
                    try {
                        val cleanImdb = imdbId?.removePrefix("tt") ?: ""
                        val subHeaders = mapOf("User-Agent" to "TemporaryUserAgent")
                        
                        var subResponseText: String? = null
                        var subResponseCode: Int? = null
                        
                        if (cleanImdb.isNotEmpty()) {
                            val subUrl = if (type == "movie") {
                                "https://rest.opensubtitles.org/search/imdbid-$cleanImdb/sublanguageid-ind"
                            } else {
                                "https://rest.opensubtitles.org/search/episode-$episode/imdbid-$cleanImdb/season-$season/sublanguageid-ind"
                            }
                            Log.d("xr3edFlix", "OpenSubtitles querying by IMDb ID: $subUrl")
                            val subResponse = app.get(subUrl, headers = subHeaders, timeout = 10)
                            subResponseText = subResponse.text
                            subResponseCode = subResponse.code
                        }
                        
                        var array: com.fasterxml.jackson.databind.JsonNode? = if (subResponseText != null && subResponseCode == 200) {
                            mapper.readTree(subResponseText)
                        } else null
                        
                        if (array == null || !array.isArray || array.size() == 0) {
                            if (!title.isNullOrEmpty()) {
                                val encodedTitle = URLEncoder.encode(title, "UTF-8")
                                val queryUrl = if (type == "movie") {
                                    "https://rest.opensubtitles.org/search/query-$encodedTitle/sublanguageid-ind"
                                } else {
                                    "https://rest.opensubtitles.org/search/episode-$episode/query-$encodedTitle/season-$season/sublanguageid-ind"
                                }
                                Log.d("xr3edFlix", "OpenSubtitles falling back to query search: $queryUrl")
                                val queryResponse = app.get(queryUrl, headers = subHeaders, timeout = 10)
                                if (queryResponse.code == 200) {
                                    array = mapper.readTree(queryResponse.text)
                                }
                            }
                        }
                        
                        if (array != null && array.isArray && array.size() > 0) {
                            var subCount = 0
                            for (i in 0 until array.size()) {
                                if (subCount >= 3) break
                                val item = array.get(i)
                                val downloadLink = item?.get("SubDownloadLink")?.asText()
                                val fileName = item?.get("SubFileName")?.asText() ?: "Indonesian ${subCount + 1}"
                                if (!downloadLink.isNullOrEmpty()) {
                                    val srtUrl = downloadLink.replace(".gz", ".srt")
                                    val count = indonesianCount.getAndIncrement()
                                    val label = if (count == 0) "Indonesia" else "Indonesia ${count + 1}"
                                    subtitleCallback.invoke(newSubtitleFile(label, srtUrl))
                                    subCount++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("xr3edFlix", "OpenSubtitles query failed", e)
                    }
                },
                async {
                    val tmdbId = id.toIntOrNull()
                    val s = if (type == "movie") null else parts.getOrNull(2)?.toIntOrNull()
                    val e = if (type == "movie") null else parts.getOrNull(3)?.toIntOrNull()
                    invokeXpass(tmdbId, s, e, wrappedCallback)
                },
                async {
                    val tmdbId = id.toIntOrNull()
                    val s = if (type == "movie") null else parts.getOrNull(2)?.toIntOrNull()
                    val e = if (type == "movie") null else parts.getOrNull(3)?.toIntOrNull()
                    invokeMapple(tmdbId, s, e, wrappedCallback)
                }
            )
            jobs.awaitAll()
        }

        return foundAny
    }

    // JSON Data classes for TMDB API
    data class TMDBDiscoverResponse(val results: List<TMDBMedia>? = null)
    data class TMDBMedia(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null
    )

    data class TMDBCastMember(
        val name: String? = null,
        val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null
    )

    data class TMDBCredits(
        val cast: List<TMDBCastMember>? = null
    )

    data class TMDBExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null
    )

    data class TMDBDetailResponse(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        val overview: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        val seasons: List<TMDBSeason>? = null,
        val credits: TMDBCredits? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("external_ids") val externalIds: TMDBExternalIds? = null
    )

    data class TMDBSeason(
        @JsonProperty("season_number") val seasonNumber: Int? = null
    )

    data class TMDBSeasonDetailResponse(
        val episodes: List<TMDBEpisode>? = null
    )

    data class TMDBEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null
    )

    // Decryption API data classes
    data class EncryptedResponse(
        val encrypted: Boolean? = null,
        val data: String? = null
    )

    data class StreamResponse(
        val data: StreamResponse? = null,
        val url: String? = null,
        val headers: Map<String, String>? = null,
        val downloads: List<DownloadItem>? = null,
        @JsonProperty("playlist") val urlList: List<UrlItem>? = null,
        val streams: List<StreamItem>? = null,
        val captions: List<CaptionItem>? = null,
        // klikxxi format: {sources:[{quality,type,url}]}
        val sources: List<SourceItem>? = null
    )

    data class DownloadItem(
        val resolution: Int? = null,
        val url: String? = null
    )

    data class UrlItem(
        val link: String? = null,
        val resolution: String? = null,
        val type: String? = null
    )

    data class StreamItem(
        val url: String? = null,
        val type: String? = null,
        val language: String? = null
    )

    data class SourceItem(
        val url: String? = null,
        val type: String? = null,
        val quality: String? = null
    )

    data class CaptionItem(
        val url: String? = null,
        val lan: String? = null,
        val lanName: String? = null
    )

    private fun decryptOphimAes(cipherHex: String): String {
        val keySpec = SecretKeySpec("klgmtlgnmua911ca".toByteArray(Charsets.UTF_8), "AES")
        val ivBytes = byteArrayOf(
            0xc9.toByte(), 0x80.toByte(), 0xc9.toByte(), 0x81.toByte(),
            0xc9.toByte(), 0x82.toByte(), 0xc9.toByte(), 0x83.toByte(),
            0xc9.toByte(), 0x84.toByte(), 0xc9.toByte(), 0x85.toByte(),
            0xc9.toByte(), 0x86.toByte(), 0xc9.toByte(), 0x87.toByte()
        )
        val ivSpec = IvParameterSpec(ivBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decryptedBytes = cipher.doFinal(hexToByteArray(cipherHex))
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val l = hex.length
        val out = ByteArray(l / 2)
        for (i in 0 until l step 2) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return out
    }

    private fun decodeDecimalString(decimalStr: String): String {
        return try {
            val parts = decimalStr.trim().split("\\s+".toRegex())
            val chars = parts.filter { it.isNotEmpty() && it.all { c -> c.isDigit() } }
                .map { it.toInt().toChar() }
                .toCharArray()
            String(chars)
        } catch (e: Exception) {
            Log.e("xr3edFlix", "decodeDecimalString error: ${e.message}")
            ""
        }
    }

    // ==========================================
    // MovieBox Extractor Helpers & Implementation
    // ==========================================
    
    private val movieBox = "https://api.inmoviebox.com"
    private val MOVIEBOX_TOKEN_B64 = "ZXlKaGJHY2lPaUpJVXpJMU5pSXNJblI1Y0NJNklrcFhWQ0o5LmV5SjFhV1FpT2pJME1UUTFOak0yTkRneU9USTJOelEzTnpZc0ltVjRjQ0k2TVRjNU1ESTFNemc0T1N3aWFXRjBJam94TnpneU5EYzNOVGc1ZlEuUUFLR1Z4SGd6VDItQjVnRWhUT2NCREVwM0Rla0RKcmdnVFBteVViVXJ1QQ=="
    private val MOVIEBOX_BEARER_TOKEN: String get() = try {
        String(android.util.Base64.decode(MOVIEBOX_TOKEN_B64, android.util.Base64.DEFAULT), Charsets.UTF_8)
    } catch(e: Exception) { "" }

    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    private fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateXClientToken(): String {
        val timestamp = System.currentTimeMillis().toString()
        val reversed = timestamp.reversed()
        val hash = md5(reversed.toByteArray())
        return "$timestamp,$hash"
    }

    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val parsed = android.net.Uri.parse(url)
        val path = parsed.path ?: ""
        
        val query = if (parsed.queryParameterNames.isNotEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key ->
                parsed.getQueryParameters(key).joinToString("&") { value ->
                    "$key=$value"
                }
            }
        } else ""

        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n" +
                "${accept ?: ""}\n" +
                "${contentType ?: ""}\n" +
                "$bodyLength\n" +
                "$timestamp\n" +
                "$bodyHash\n" +
                canonicalUrl
    }

    private fun generateXTrSignature(
        method: String,
        accept: String? = "application/json",
        contentType: String? = "application/json",
        url: String,
        body: String? = null,
        useAltKey: Boolean = false
    ): String {
        val timestamp = System.currentTimeMillis()
        val canonical = buildCanonicalString(
            method = method,
            accept = accept,
            contentType = contentType,
            url = url,
            body = body,
            timestamp = timestamp
        )
        val secretKey = if (useAltKey) {
            BuildConfig.MOVIEBOX_SECRET_KEY_ALT
        } else {
            BuildConfig.MOVIEBOX_SECRET_KEY_DEFAULT
        }
        val secretBytes = try {
            if (secretKey.isEmpty() || secretKey == "dummy") {
                ByteArray(0)
            } else {
                val decodedOnce = android.util.Base64.decode(secretKey, android.util.Base64.DEFAULT)
                val decodedOnceStr = String(decodedOnce, Charsets.UTF_8)
                if (decodedOnceStr.matches(Regex("^[A-Za-z0-9+/=_-]{30,80}$"))) {
                    android.util.Base64.decode(decodedOnceStr, android.util.Base64.DEFAULT)
                } else {
                    decodedOnce
                }
            }
        } catch(e: Exception) {
            ByteArray(0)
        }
        if (secretBytes.isEmpty()) {
            Log.w("xr3edFlix", "MovieBox secretBytes is empty for key=$secretKey")
            return "$timestamp|2|dummy_signature"
        }
        val mac = javax.crypto.Mac.getInstance("HmacMD5").apply {
            init(SecretKeySpec(secretBytes, "HmacMD5"))
        }
        val rawSignature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureBase64 = android.util.Base64.encodeToString(rawSignature, android.util.Base64.NO_WRAP)
        return "$timestamp|2|$signatureBase64"
    }

    private fun getQualityFromName(qualityName: String?): Int {
        if (qualityName.isNullOrEmpty()) return Qualities.Unknown.value
        val clean = qualityName.lowercase()
        return when {
            clean.contains("1080") -> Qualities.P1080.value
            clean.contains("720") -> Qualities.P720.value
            clean.contains("480") -> Qualities.P480.value
            clean.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun isTitleMatch(name: String, title: String): Boolean {
        val cleanName = name.lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
        val cleanTitle = title.lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
        if (cleanName.contains(cleanTitle) || cleanTitle.contains(cleanName)) return true
        
        if (title.contains(":")) {
            val part = title.substringBefore(":").trim().lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
            if (part.isNotEmpty() && (cleanName.contains(part) || part.contains(cleanName))) return true
        }
        if (title.contains("-")) {
            val part = title.substringBefore("-").trim().lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
            if (part.isNotEmpty() && (cleanName.contains(part) || part.contains(cleanName))) return true
        }
        return false
    }

    suspend fun invokeMovieBox(
        title: String?,
        season: Int? = 0,
        episode: Int? = 0,
        subCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            if (title.isNullOrBlank()) return false
            Log.d("xr3edFlix", "MovieBox Hybrid query starting: title=$title, season=$season, episode=$episode")

            val searchUrl = "https://api.inmoviebox.com/wefeed-mobile-bff/subject-api/search/v2"
            val jsonBody = """{"page":1,"perPage":10,"keyword":"$title"}"""
            val xClientToken = generateXClientToken()
            val xTrSignature = generateXTrSignature(
                "POST", "application/json", "application/json; charset=utf-8", searchUrl, jsonBody
            )
            val devId = generateDeviceId()
            val searchHeaders = mapOf(
                "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Subsystem for Android(TM); Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
                "accept" to "application/json",
                "content-type" to "application/json; charset=utf-8",
                "x-client-token" to xClientToken,
                "x-tr-signature" to xTrSignature,
                "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","install_ch":"ps","device_id":"$devId","install_store":"ps","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Windows","model":"Subsystem for Android(TM)","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}""",
                "x-client-status" to "0",
                "Authorization" to "Bearer $MOVIEBOX_BEARER_TOKEN"
            )

            val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            val response = app.post(searchUrl, headers = searchHeaders, requestBody = requestBody)
            Log.d("xr3edFlix", "MovieBox search response: code=${response.code}")
            if (response.code != 200) return false

            val root = mapper.readTree(response.text)
            val results = root["data"]?.get("results") ?: return false

            val matchingIds = mutableListOf<String>()
            for (result in results) {
                val subjects = result["subjects"] ?: continue
                for (subject in subjects) {
                    val name = subject["title"]?.asText() ?: continue
                    val subjectId = subject["subjectId"]?.asText() ?: continue
                    val type = subject["subjectType"]?.asInt() ?: 0
                    if (isTitleMatch(name, title) && (type == 1 || type == 2)) {
                        matchingIds.add(subjectId)
                    }
                }
            }

            Log.d("xr3edFlix", "MovieBox matchingIds: $matchingIds")
            if (matchingIds.isEmpty()) return false

            var foundLinks = false
            val targetSeason = season ?: 0
            val targetEpisode = episode ?: 0

            for (subjectId in matchingIds) {
                try {
                    val downloadUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/download" +
                            "?subjectId=${java.net.URLEncoder.encode(subjectId, "UTF-8")}" +
                            "&se=$targetSeason&ep=$targetEpisode&detailPath="

                    val downloadHeaders = mapOf(
                        "accept" to "*/*",
                        "accept-language" to "en-US,en;q=0.5",
                        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
                        "origin" to "https://videodownloader.site",
                        "referer" to "https://videodownloader.site/"
                    )

                    val dlRes = app.get(downloadUrl, headers = downloadHeaders)
                    Log.d("xr3edFlix", "MovieBox download response: code=${dlRes.code} for subjectId=$subjectId")
                    if (dlRes.code != 200) continue

                    val dlRoot = mapper.readTree(dlRes.text)
                    if (dlRoot["code"]?.asInt() != 0) continue
                    val dlData = dlRoot["data"] ?: continue

                    val downloads = dlData["downloads"]
                    val captions = dlData["captions"]

                    if (downloads != null && downloads.isArray) {
                        for (download in downloads) {
                            val streamUrl = download["url"]?.asText() ?: continue
                            if (streamUrl.isBlank()) continue
                            val resolution = download["resolution"]?.asInt()
                            val quality = when (resolution) {
                                2160 -> Qualities.P2160.value
                                1440 -> Qualities.P1440.value
                                1080 -> Qualities.P1080.value
                                720 -> Qualities.P720.value
                                480 -> Qualities.P480.value
                                360 -> Qualities.P360.value
                                240 -> Qualities.P240.value
                                else -> resolution ?: Qualities.Unknown.value
                            }

                            val linkType = when {
                                streamUrl.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
                                streamUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                                streamUrl.endsWith(".torrent", ignoreCase = true) -> ExtractorLinkType.TORRENT
                                streamUrl.endsWith(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                                else -> ExtractorLinkType.VIDEO
                            }

                            callback.invoke(
                                newExtractorLink(
                                    source = "MovieBox",
                                    name = "MovieBox ${resolution ?: ""}".trim(),
                                    url = streamUrl,
                                    type = linkType
                                ) {
                                    this.quality = quality
                                    this.referer = "https://videodownloader.site/"
                                    this.headers = mapOf(
                                        "Referer" to "https://videodownloader.site/",
                                        "Origin" to "https://videodownloader.site"
                                    )
                                }
                            )
                            Log.d("xr3edFlix", "MovieBox Hybrid added link: $streamUrl")
                            foundLinks = true
                        }
                    }

                    if (captions != null && captions.isArray) {
                        for (caption in captions) {
                            val captionUrl = caption["url"]?.asText() ?: continue
                            if (captionUrl.isBlank()) continue
                            val lang = caption["lanName"]?.asText() ?: caption["lan"]?.asText() ?: "Unknown"
                            subCallback.invoke(
                                newSubtitleFile(
                                    url = captionUrl,
                                    lang = lang
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("xr3edFlix", "MovieBox Hybrid download parse error", e)
                }
            }

            return foundLinks
        } catch (e: Exception) {
            Log.e("xr3edFlix", "MovieBox Hybrid main error", e)
            return false
        }
    }

    private fun String.createSlug(): String {
        return this.filter { it.isWhitespace() || it.isLetterOrDigit() }
            .trim()
            .replace("\\s+".toRegex(), "-")
            .lowercase()
    }

    suspend fun invokeMultimovies(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val multimoviesApi = "https://multimovies.study"
        if (title.isNullOrBlank()) return

        val fixTitle = title.createSlug()

        val url = if (season == null) {
            "$multimoviesApi/movies/$fixTitle/"
        } else {
            "$multimoviesApi/episodes/$fixTitle-${season}x${episode}/"
        }

        android.util.Log.d("xr3edFlix", "Multimovies requesting: $url")
        var actualBaseUrl = multimoviesApi
        try {
            val response = app.get(url, timeout = 10)
            android.util.Log.d("xr3edFlix", "Multimovies response code: ${response.code} (final url: ${response.url})")
            if (response.code != 200) return
            val finalUrl = response.url
            val uri = java.net.URI(finalUrl)
            actualBaseUrl = "${uri.scheme}://${uri.host}"

            val html = response.text
            if (html.contains("Just a moment", ignoreCase = true)) {
                android.util.Log.w("xr3edFlix", "Multimovies CF block detected")
                return
            }
            val document = org.jsoup.Jsoup.parse(html)

            val playerOptions = document.select("ul#playeroptionsul li").map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }
            android.util.Log.d("xr3edFlix", "Multimovies found player options: ${playerOptions.size}")

            coroutineScope {
                playerOptions.map { (postId, nume, type) ->
                    async {
                        if (nume.contains("trailer", ignoreCase = true)) return@async

                        try {
                            android.util.Log.d("xr3edFlix", "Multimovies option $nume posting to: $actualBaseUrl/wp-admin/admin-ajax.php (post=$postId, type=$type)")
                            val postResponse = app.post(
                                url = "$actualBaseUrl/wp-admin/admin-ajax.php",
                                data = mapOf(
                                    "action" to "doo_player_ajax",
                                    "post" to postId,
                                    "nume" to nume,
                                    "type" to type
                                ),
                                referer = finalUrl,
                                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                            )
                            android.util.Log.d("xr3edFlix", "Multimovies option $nume response code: ${postResponse.code}")
                            if (postResponse.code != 200) return@async

                            val responseText = postResponse.text
                            val rootJson = org.json.JSONObject(responseText)
                            val embedUrl = rootJson.optString("embed_url")

                            val link = embedUrl
                                .trim()
                                .removeSurrounding("\"")
                                .takeIf { it.startsWith("http") }
                                ?: return@async

                            android.util.Log.d("xr3edFlix", "Multimovies option $nume embedUrl parsed: $link")
                            if (!link.contains("youtube", ignoreCase = true)) {
                                android.util.Log.d("xr3edFlix", "Multimovies option $nume loading extractor for $link")
                                loadExtractor(link, "$actualBaseUrl/", subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            Log.e("xr3edFlix", "doo_player_ajax request failed for nume $nume", e)
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.e("xr3edFlix", "Multimovies invocation failed", e)
        }
    }
}

class AllinoneDownloader : Filesim() {
    override var name = "MultiMovies API"
    override var mainUrl = "https://allinonedownloader.fun"
}

open class Ridoo : ExtractorApi() {
    override val name = "Ridoo"
    override var mainUrl = "https://ridoo.net"
    override val requiresReferer = true
    open val defaulQuality = Qualities.P1080.value

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        }
        val m3u8 = Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script ?: return)?.groupValues?.getOrNull(1)
        val quality = "qualityLabels.*\"(\\d{3,4})[pP]\"".toRegex().find(script)?.groupValues?.get(1)
        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                url = m3u8 ?: return,
                INFER_TYPE
            ) {
                this.referer = mainUrl
                this.quality = quality?.toIntOrNull() ?: defaulQuality
            }
        )
    }
}

class Multimovies : Ridoo() {
    override val name = "Multimovies"
    override var mainUrl = "https://multimovies.cloud"
}

class MultimoviesSB : StreamSB() {
    override var name = "Multimovies"
    override var mainUrl = "https://multimovies.website"
}

class MultimoviesAIO : StreamWishExtractor() {
    override var name = "Multimovies Cloud AIO"
    override var mainUrl = "https://allinonedownloader.fun"
}

class Animezia : VidhideExtractor() {
    override var name = "MultiMovies API"
    override var mainUrl = "https://animezia.cloud"
}

class Servertwo : VidhideExtractor() {
    override var name = "MultiMovies Vidhide"
    override var mainUrl = "https://server2.shop"
}

class Cinemaos : ExtractorApi() {
    override val name = "CinemaOS"
    override var mainUrl = "https://cinemaos.tech"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val uri = java.net.URI(url)
        val path = uri.path
        val parts = path.split("/").filter { it.isNotEmpty() }
        
        val tmdbId = parts.getOrNull(1) ?: return
        val season = parts.getOrNull(2)
        val episode = parts.getOrNull(3)

        val embedUrls = if (season != null && episode != null) {
            listOf(
                "https://player.autoembed.cc/embed/tv/$tmdbId/$season/$episode",
                "https://vidlink.pro/tv/$tmdbId/$season/$episode",
                "https://vidsrc.in/embed/tv/$tmdbId/$season/$episode",
                "https://vidsrc.cc/v3/embed/tv/$tmdbId/$season/$episode",
                "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode",
                "https://vidsrc.rip/embed/tv/$tmdbId/$season/$episode",
                "https://vidsrc.su/embed/tv/$tmdbId/$season/$episode",
                "https://vidsrc.xyz/embed/tv/$tmdbId/$season-$episode",
                "https://vidsrc.vip/embed/tv/$tmdbId/$season/$episode"
            )
        } else {
            listOf(
                "https://player.autoembed.cc/embed/movie/$tmdbId",
                "https://vidlink.pro/movie/$tmdbId",
                "https://vidsrc.in/embed/movie/$tmdbId",
                "https://vidsrc.cc/v3/embed/movie/$tmdbId",
                "https://vidsrc.to/embed/movie/$tmdbId",
                "https://vidsrc.rip/embed/movie/$tmdbId",
                "https://vidsrc.su/embed/movie/$tmdbId",
                "https://vidsrc.xyz/embed/movie/$tmdbId",
                "https://vidsrc.vip/embed/movie/$tmdbId"
            )
        }

        coroutineScope {
            embedUrls.map { embedUrl ->
                async {
                    try {
                        loadExtractor(embedUrl, "https://cinemaos.tech/", subtitleCallback, callback)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }.awaitAll()
        }
    }
}
