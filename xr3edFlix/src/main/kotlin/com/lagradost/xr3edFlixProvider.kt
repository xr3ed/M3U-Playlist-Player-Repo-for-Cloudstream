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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

class xr3edFlixProvider : MainAPI() {
    companion object {
        val addedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
                val text = app.get(url, timeout = 15).text
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
        MainPageData("Viu Series", "Viu Series"),
        MainPageData("HBO GO Movies", "HBO GO Movies"),
        MainPageData("HBO GO Series", "HBO GO Series"),
        MainPageData("Catchplay+ Movies", "Catchplay+ Movies"),
        MainPageData("Crunchyroll Series", "Crunchyroll Series"),
        MainPageData("Lionsgate Play Movies", "Lionsgate Play Movies"),
        MainPageData("Lionsgate Play Series", "Lionsgate Play Series")
    )

    private suspend fun fetchTmdbList(path: String, params: Map<String, String>): List<SearchResponse> {
        val mapWithLang = params + mapOf("language" to "en-US")
        val queryParams = mapWithLang.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = "$TMDB_API_BASE/$path?api_key=${getTmdbKey()}&$queryParams"
        val res = parsedGet<TMDBDiscoverResponse>(url)
        return res?.results?.map { media ->
            val title = if (media.originalLanguage == "id") {
                media.originalTitle ?: media.originalName ?: media.title ?: media.name ?: "Unknown"
            } else {
                media.title ?: media.name ?: "Unknown"
            }
            val isMovie = media.title != null || media.mediaType == "movie"
            val poster = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            newMovieSearchResponse(
                name = title,
                url = if (isMovie) "movie::${media.id}" else "tv::${media.id}",
                type = if (isMovie) TvType.Movie else TvType.TvSeries
            ) {
                this.posterUrl = poster
            }
        } ?: emptyList()
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


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (page > 1) return null

        val lists = coroutineScope {
            val trendingMovies = async { HomePageList("Film Trending", fetchTmdbList("trending/movie/day", emptyMap())) }
            val popularMovies = async { HomePageList("Film Populer", fetchTmdbList("discover/movie", mapOf("sort_by" to "popularity.desc"))) }
            
            val trendingSeries = async { HomePageList("Seri Trending", fetchTmdbList("trending/tv/day", emptyMap())) }
            val popularSeries = async { HomePageList("Seri Populer", fetchTmdbList("discover/tv", mapOf("sort_by" to "popularity.desc"))) }

            // Providers - Movies & Series (watch_region=ID)
            val netflixMovies = async { HomePageList("Netflix Movies", fetchRecentRegionalList("8", true)) }
            val netflixSeries = async { HomePageList("Netflix Series", fetchRecentRegionalList("8", false)) }
            
            val disneyMovies = async { HomePageList("Disney+ Movies", fetchRecentRegionalList("122", true)) }
            val disneySeries = async { HomePageList("Disney+ Series", fetchRecentRegionalList("122", false)) }
            
            val primeMovies = async { HomePageList("Prime Video Movies", fetchRecentRegionalList("119", true)) }
            val primeSeries = async { HomePageList("Prime Video Series", fetchRecentRegionalList("119", false)) }
            
            val appleMovies = async { HomePageList("Apple TV+ Movies", fetchRecentRegionalList("350", true)) }
            val appleSeries = async { HomePageList("Apple TV+ Series", fetchRecentRegionalList("350", false)) }
            
            val viuSeries = async {
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
            
            val hboMovies = async { HomePageList("HBO GO Movies", fetchRecentRegionalList("1899", true)) }
            val hboSeries = async { HomePageList("HBO GO Series", fetchRecentRegionalList("1899", false)) }
            
            val catchplayMovies = async { HomePageList("Catchplay+ Movies", fetchRecentRegionalList("159", true)) }
            
            val crunchyrollSeries = async { HomePageList("Crunchyroll Series", fetchRecentRegionalList("283", false)) }
            
            val lionsgateMovies = async { HomePageList("Lionsgate Play Movies", fetchRecentRegionalList("561", true)) }
            val lionsgateSeries = async { HomePageList("Lionsgate Play Series", fetchRecentRegionalList("561", false)) }
 
            listOf(
                trendingMovies.await(), popularMovies.await(),
                trendingSeries.await(), popularSeries.await(),
                netflixMovies.await(), netflixSeries.await(),
                disneyMovies.await(), disneySeries.await(),
                primeMovies.await(), primeSeries.await(),
                appleMovies.await(), appleSeries.await(),
                viuSeries.await(),
                hboMovies.await(), hboSeries.await(),
                catchplayMovies.await(),
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
                    url = "movie::${media.id}",
                    type = TvType.Movie
                ) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(
                    name = title,
                    url = "tv::${media.id}",
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = poster
                }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        addedUrls.clear()
        // Cloudstream prepend mainUrl, jadi url bisa "https://watch-v2.autoembed.app/movie::123"
        // Strip mainUrl prefix jika ada
        val cleanUrl = if (url.contains("://") && url.contains("::")) {
            // Ambil bagian "movie::123" atau "tv::123" dari URL
            val slashIdx = url.lastIndexOf('/', url.indexOf("::"))
            if (slashIdx != -1) url.substring(slashIdx + 1) else url
        } else url

        val parts = cleanUrl.split("::")
        if (parts.size < 2) return null
        val type = parts[0]
        val id = parts[1]
        Log.d("xr3edFlix", "load() url=$url cleanUrl=$cleanUrl type=$type id=$id")

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
            val cleanTitle = (res.title ?: "Unknown").replace("::", ":")

            return newMovieLoadResponse(
                name = res.title ?: "Unknown",
                url = "movie::$id",
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
            val cleanTitle = (res.name ?: "Unknown").replace("::", ":")

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
                url = "tv::$id",
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
        val id = parts[1]
        val imdbId = if (type == "movie") parts.getOrNull(2) else parts.getOrNull(4)
        val title = if (type == "movie") parts.getOrNull(3) else parts.getOrNull(5)

        val hasIndonesian = java.util.concurrent.atomic.AtomicBoolean(false)
        val subCallback = { subFile: SubtitleFile ->
            val lang = subFile.lang.lowercase()
            if (lang.contains("indonesia") || lang.contains("indo") || lang == "id" || lang == "in") {
                hasIndonesian.set(true)
                subtitleCallback.invoke(subFile)
            }
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
                                        callback.invoke(link)
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
                            resolved = loadExtractor(currentUrl, subCallback, callback)
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
                                                                         name = "VidsrcPM - $currentRes",
                                                                         source = "VidsrcPM",
                                                                         url = absoluteUrl,
                                                                         type = ExtractorLinkType.M3U8
                                                                     ) {
                                                                         this.quality = q
                                                                         this.headers = emptyMap()
                                                                     }
                                                                     callback.invoke(link)
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
                        kotlinx.coroutines.delay(2000)
                        if (hasIndonesian.get()) {
                            Log.d("xr3edFlix", "Indonesian subtitle already loaded, skipping OpenSubtitles search.")
                            return@async
                        }
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
                                    subtitleCallback.invoke(newSubtitleFile("Indonesia ${subCount + 1}", srtUrl))
                                    subCount++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("xr3edFlix", "OpenSubtitles query failed", e)
                    }
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
}
