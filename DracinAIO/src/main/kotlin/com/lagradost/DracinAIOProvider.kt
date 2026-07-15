package com.lagradost

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.DracinAIO.BuildConfig
import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers.Companion.toHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.min
import java.net.ServerSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class DracinAIOProvider : MainAPI() {
    companion object {
        var appContext: Context? = null
        private val BASE_URL = String(Base64.decode(BuildConfig.MELOLO_URL, Base64.DEFAULT), Charsets.UTF_8)
        private val API_URL = "$BASE_URL/api"

        private var sessionCookie: String? = null
        private val cookieMutex = Mutex()

        private val cache = HashMap<String, Pair<Long, String>>()
        private val cacheMutex = Mutex()

        private val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$BASE_URL/"
        )

        private val cleanClient = com.lagradost.cloudstream3.app.baseClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        private val unsafeClient: OkHttpClient by lazy {
            try {
                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                com.lagradost.cloudstream3.app.baseClient.newBuilder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                    .hostnameVerifier { _, _ -> true }
                    .build()
            } catch (e: Exception) {
                cleanClient
            }
        }

        data class ProviderInfo(val code: String, val name: String)

        val providers = listOf(
            ProviderInfo("reelshort", "ReelShort"),
            ProviderInfo("shortmax", "ShortMax"),
            ProviderInfo("dramabox", "DramaBox"),
            ProviderInfo("goodshort", "GoodShort"),
            ProviderInfo("pinedrama", "PineDrama"),
            ProviderInfo("dramarush", "DramaRush"),
            ProviderInfo("dramabite", "DramaBite"),
            ProviderInfo("meloshort", "MeloShort"),
            ProviderInfo("microdrama", "MicroDrama"),
            ProviderInfo("melolo", "Melolo"),
            ProviderInfo("shortsky", "ShortSky"),
            ProviderInfo("freereels", "FreeReels"),
            ProviderInfo("starshort", "StarShort"),
            ProviderInfo("stardusttv", "StardustTV"),
            ProviderInfo("dramawave", "DramaWave"),
            ProviderInfo("dramanova", "DramaNova"),
            ProviderInfo("moviebox", "MovieBox"),
            ProviderInfo("dotdrama", "DotDrama"),
            ProviderInfo("netshort", "NetShort"),
            ProviderInfo("velolo", "Velolo"),
            ProviderInfo("cubetv", "CubeTV"),
            ProviderInfo("bilitv", "BiliTV")
        )

        val providersWithDub = setOf("reelshort", "shortmax", "dramabox", "dramarush", "melolo", "starshort", "netshort", "cubetv")

        private var serverSocket: ServerSocket? = null
        private var serverPort: Int = 0
        private val playlistCache = ConcurrentHashMap<String, String>()

        fun startLocalServer(): Int {
            if (serverSocket != null) return serverPort
            try {
                val socket = ServerSocket(0)
                serverSocket = socket
                serverPort = socket.localPort
                thread(start = true, isDaemon = true) {
                    while (!socket.isClosed) {
                        try {
                            val client = socket.accept()
                            thread(start = true, isDaemon = true) {
                                try {
                                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                                    val line = reader.readLine() ?: ""
                                    val path = line.split(" ").getOrNull(1) ?: ""
                                    if (path.startsWith("/play_") && path.endsWith(".m3u8")) {
                                        val streamId = path.substringAfter("/play_").substringBefore(".m3u8")
                                        val m3u8 = playlistCache[streamId]
                                        val os = client.getOutputStream()
                                        if (m3u8 != null) {
                                            val response = "HTTP/1.1 200 OK\r\n" +
                                                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                                    "Content-Length: ${m3u8.toByteArray(Charsets.UTF_8).size}\r\n" +
                                                    "Connection: close\r\n\r\n" +
                                                    m3u8
                                            os.write(response.toByteArray(Charsets.UTF_8))
                                        } else {
                                            val response = "HTTP/1.1 404 Not Found\r\n" +
                                                    "Content-Length: 9\r\n" +
                                                    "Connection: close\r\n\r\n" +
                                                    "Not Found"
                                            os.write(response.toByteArray(Charsets.UTF_8))
                                        }
                                        os.flush()
                                    } else if (path.startsWith("/img_")) {
                                        val encodedUrl = path.substringAfter("/img_")
                                        val decodedUrl = try {
                                            val cleanBase64 = encodedUrl.replace('_', '/').replace('-', '+')
                                            val pad = (4 - cleanBase64.length % 4) % 4
                                            val finalBase64 = if (pad > 0) cleanBase64 + "=".repeat(pad) else cleanBase64
                                            String(Base64.decode(finalBase64, Base64.DEFAULT), Charsets.UTF_8)
                                        } catch(e: Exception) { "" }
                                        
                                        val os = client.getOutputStream()
                                        if (decodedUrl.startsWith("http")) {
                                            try {
                                                val request = Request.Builder().url(decodedUrl).build()
                                                val response = unsafeClient.newCall(request).execute()
                                                val mime = response.header("Content-Type", "image/jpeg")
                                                val body = response.body
                                                val contentLength = body.contentLength()
                                                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                                                        "Content-Type: $mime\r\n" +
                                                        "Content-Length: $contentLength\r\n" +
                                                        "Connection: close\r\n\r\n"
                                                os.write(responseHeaders.toByteArray(Charsets.UTF_8))
                                                body.byteStream().use { input ->
                                                    input.copyTo(os)
                                                }
                                            } catch (e: Exception) {
                                                val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                                os.write(response.toByteArray(Charsets.UTF_8))
                                            }
                                        } else {
                                            val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                            os.write(response.toByteArray(Charsets.UTF_8))
                                        }
                                        os.flush()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    try { client.close() } catch (e: Exception) {}
                                }
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return serverPort
        }
    }

    private fun buildDetailUrl(provider: String, id: String, title: String, cover: String): String {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedCover = URLEncoder.encode(cover, "UTF-8")
            "https://lynk.id/xr3ed#$provider-$id?title=$encodedTitle&cover=$encodedCover"
        } catch (e: Exception) {
            "https://lynk.id/xr3ed#$provider-$id"
        }
    }

    private fun rewritePlaylistLine(line: String): String {
        var newLine = line
        val m3u8ProxyRegex = """/api/m3u8-proxy\?url=([^"\r\n\s]+)""".toRegex()
        newLine = m3u8ProxyRegex.replace(newLine) { matchResult ->
            val encoded = matchResult.groupValues[1]
            try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (e: Exception) {
                matchResult.value
            }
        }
        val proxyRegex = """/api/proxy\?u=([A-Za-z0-9+/=_-]+)""".toRegex()
        newLine = proxyRegex.replace(newLine) { matchResult ->
            val encoded = matchResult.groupValues[1]
            try {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                matchResult.value
            }
        }
        return newLine
    }

    override var name = "#Dracin All in One"
    override var mainUrl = BASE_URL
    override var lang = "id"
    override var supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("Beranda", "beranda"),
        MainPageData("Top 10 Minggu Ini", "global|top10"),
        MainPageData("Lagi Trending", "global|trending"),
        MainPageData("Baru Tayang", "global|barutayang")
    ) + providers.flatMap { prov ->
        val list = ArrayList<MainPageData>()
        list.add(MainPageData("[${prov.name}] - Semua", "${prov.code}|semua"))
        if (providersWithDub.contains(prov.code)) {
            list.add(MainPageData("[${prov.name}] - Dub Indo", "${prov.code}|dubindo"))
        }
        list
    }



    private suspend fun getHeaders(forceRefresh: Boolean = false): Map<String, String> {
        cookieMutex.withLock {
            if (sessionCookie == null || forceRefresh) {
                try {
                    val req = Request.Builder()
                        .url(BASE_URL)
                        .headers(headers.toHeaders())
                        .build()
                    val response = withContext(Dispatchers.IO) {
                        cleanClient.newCall(req).execute()
                    }
                    val setCookie = response.header("set-cookie")
                    if (setCookie != null) {
                        sessionCookie = setCookie.substringBefore(";")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        val currentCookie = sessionCookie
        return if (currentCookie != null) {
            headers + mapOf("Cookie" to currentCookie)
        } else {
            headers
        }
    }

    private suspend fun httpGet(url: String): String {
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            val cached = cache[url]
            if (cached != null && now - cached.first < 10000) {
                return cached.second
            }
        }

        val result = try {
            val reqHeaders = getHeaders()
            val req = Request.Builder()
                .url(url)
                .headers(reqHeaders.toHeaders())
                .build()
            val response = withContext(Dispatchers.IO) {
                cleanClient.newCall(req).execute()
            }
            val bodyText = response.body.string()
            Log.d("DracinAIO", "HTTP GET response: $url -> code = ${response.code}, len = ${bodyText.length}, snippet = ${if (bodyText.length > 100) bodyText.substring(0, 100).replace('\n', ' ') else bodyText.replace('\n', ' ')}")
            if (response.code == 401 || bodyText.contains("Unauthorized")) {
                val retryHeaders = getHeaders(forceRefresh = true)
                val retryReq = Request.Builder()
                    .url(url)
                    .headers(retryHeaders.toHeaders())
                    .build()
                val retryResponse = withContext(Dispatchers.IO) {
                    cleanClient.newCall(retryReq).execute()
                }
                val retryText = retryResponse.body.string()
                Log.d("DracinAIO", "HTTP GET retry: $url -> code = ${retryResponse.code}, len = ${retryText.length}, snippet = ${if (retryText.length > 100) retryText.substring(0, 100).replace('\n', ' ') else retryText.replace('\n', ' ')}")
                retryText
            } else {
                bodyText
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }

        if (result.isNotEmpty() && !result.contains("502 Bad Gateway") && !result.contains("Unauthorized")) {
            cacheMutex.withLock {
                cache[url] = Pair(System.currentTimeMillis(), result)
            }
        }
        return result
    }

    private fun isDubOrIndo(title: String): Boolean {
        val titleLower = title.lowercase()
        if (titleLower.contains("dubbed")) return false
        return titleLower.contains("dub") || 
               titleLower.contains("indo") || 
               titleLower.contains("sulih") || 
               titleLower.contains("dijuluki") || 
               titleLower.contains("di juluki") || 
               titleLower.contains("juluk")
    }

    data class AioItem(
        val id: String,
        val title: String,
        val cover: String,
        val episodes: Int
    )

    private fun parseRankItems(responseText: String): List<AioItem> {
        if (responseText.isEmpty()) return emptyList()
        try {
            val root = JSONObject(responseText)
            // 1. Check if "items" or "dramas" array is at root
            val itemsArray = root.optJSONArray("items") ?: root.optJSONArray("dramas")
            if (itemsArray != null) {
                val list = ArrayList<AioItem>()
                for (i in 0 until itemsArray.length()) {
                    val obj = itemsArray.optJSONObject(i) ?: continue
                    list.add(parseAioItem(obj))
                }
                return list
            }
            // 2. Check if "data" is in root
            val dataObj = root.opt("data")
            if (dataObj is JSONObject) {
                val sectionsArray = dataObj.optJSONArray("sections")
                if (sectionsArray != null && sectionsArray.length() > 0) {
                    val section0 = sectionsArray.optJSONObject(0)
                    val secItems = section0?.optJSONArray("items") ?: section0?.optJSONArray("dramas")
                    if (secItems != null) {
                        val list = ArrayList<AioItem>()
                        for (i in 0 until secItems.length()) {
                            val obj = secItems.optJSONObject(i) ?: continue
                            list.add(parseAioItem(obj))
                        }
                        return list
                    }
                }
                val dataItems = dataObj.optJSONArray("items") ?: dataObj.optJSONArray("dramas")
                if (dataItems != null) {
                    val list = ArrayList<AioItem>()
                    for (i in 0 until dataItems.length()) {
                        val obj = dataItems.optJSONObject(i) ?: continue
                        list.add(parseAioItem(obj))
                    }
                    return list
                }
            } else if (dataObj is JSONArray) {
                val list = ArrayList<AioItem>()
                for (i in 0 until dataObj.length()) {
                    val obj = dataObj.optJSONObject(i) ?: continue
                    list.add(parseAioItem(obj))
                }
                return list
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    private fun parseAioItem(obj: JSONObject): AioItem {
        val code = obj.optString("code")
        val id = if (code.isNotEmpty() && obj.has("id")) code else obj.optString("id").ifEmpty { obj.optString("fakeId").ifEmpty { obj.optString("key").ifEmpty { obj.optString("code") } } }
        val title = obj.optString("title").ifEmpty { obj.optString("name") }
        val cover = obj.optString("cover").ifEmpty { obj.optString("poster").ifEmpty { obj.optString("coverImgUrl").ifEmpty { obj.optString("icon") } } }
        val episodes = obj.optInt("totalEpisodes", 0).let { if (it > 0) it else obj.optInt("episodes", 0).let { if (it > 0) it else obj.optInt("chapters", 0) } }
        return AioItem(id, title, cover, episodes)
    }

    private fun getDirectImageUrl(url: String): String {
        if (url.isEmpty()) return ""
        try {
            if (url.contains("proxy?u=") || url.contains("img?u=")) {
                var base64Part = url.substringAfter("u=").substringBefore("&")
                base64Part = URLDecoder.decode(base64Part, "UTF-8")
                val pad = (4 - base64Part.length % 4) % 4
                if (pad > 0) {
                    base64Part += "=".repeat(pad)
                }
                val cleanBase64 = base64Part.replace('_', '/').replace('-', '+')
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val decodedUrl = String(decodedBytes, Charsets.UTF_8)
                if (decodedUrl.contains("awscover.netshort.com")) {
                    val port = startLocalServer()
                    if (port > 0) {
                        val enc = Base64.encodeToString(decodedUrl.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                        return "http://127.0.0.1:$port/img_$enc"
                    }
                }
                if (decodedUrl.startsWith("http")) {
                    return decodedUrl
                }
            }
            if (url.contains("awscover.netshort.com")) {
                val port = startLocalServer()
                if (port > 0) {
                    val enc = Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                    return "http://127.0.0.1:$port/img_$enc"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return if (url.startsWith("/")) "$mainUrl$url" else url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val filterData = request.data.ifEmpty { "beranda" }

        if (filterData == "beranda") {
            if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)

            val context = appContext
            if (context != null) {
                val cachedJson = context.getKey<String>("dracin_aio_home_cache_v4")
                val cachedTime = context.getKey<Long>("dracin_aio_home_cache_time_v4") ?: 0L
                val now = System.currentTimeMillis()
                // Cache TTL: 10 minutes (600.000 ms)
                if (cachedJson != null && now - cachedTime < 600000) {
                    val cachedLists = deserializeHomeCache(cachedJson)
                    if (cachedLists.isNotEmpty()) {
                        Log.d("DracinAIO", "Loading homepage from local DataStore cache (Age: ${(now - cachedTime)/1000}s)")
                        return newHomePageResponse(cachedLists, hasNext = false)
                    }
                }
            }

            val homePageLists = ArrayList<HomePageList>()

            // 1. Load Global Lists (Top 10, Trending, Baru Tayang)
            try {
                val responseText = httpGet(mainUrl)
                if (responseText.isNotEmpty()) {
                    val sections = listOf(
                        "Top 10 Minggu Ini" to "Top 10 Minggu Ini",
                        "Lagi Trending" to "Lagi Trending",
                        "Baru Tayang" to "Baru Tayang"
                    )
                    for ((title, term) in sections) {
                        val startIndex = responseText.indexOf(term)
                        if (startIndex != -1) {
                            val block = responseText.substring(startIndex, kotlin.math.min(startIndex + 25000, responseText.length))
                            val anchorRegex = """<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                            val matches = anchorRegex.findAll(block)
                            val list = ArrayList<SearchResponse>()
                            for (m in matches) {
                                val href = m.groupValues[1]
                                val inner = m.groupValues[2]
                                val watchPart = href.substringAfter("/watch/")
                                val providerCode = watchPart.substringBefore("/")
                                val id = watchPart.substringAfter("--")
                                if (providerCode.isEmpty() || id.isEmpty()) continue
                                val srcRegex = """src="([^"]+)"""".toRegex()
                                val altRegex = """alt="([^"]+)"""".toRegex()
                                val src = srcRegex.find(inner)?.groupValues?.get(1) ?: ""
                                val alt = altRegex.find(inner)?.groupValues?.get(1) ?: ""
                                val coverUrl = getDirectImageUrl(src)
                                list.add(
                                    newMovieSearchResponse(alt, buildDetailUrl(providerCode, id, alt, src), TvType.TvSeries) {
                                        this.posterUrl = coverUrl
                                    }
                                )
                            }
                            if (list.isNotEmpty()) {
                                homePageLists.add(HomePageList(title, list))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Load All Provider Lists in Parallel
            val deferredLists = withContext(Dispatchers.IO) {
                providers.map { prov ->
                    async {
                        try {
                            val action = when (prov.code) {
                                "dotdrama" -> "list"
                                "netshort" -> "home"
                                else -> "rank"
                            }
                            val url = "$API_URL/${prov.code}?action=$action"
                            val responseText = httpGet(url)
                            val rawItems = parseRankItems(responseText)
                            
                            val dubItems = rawItems.filter { isDubOrIndo(it.title) }
                            val nonDubItems = rawItems.filter { !isDubOrIndo(it.title) }
                            
                            val lists = ArrayList<HomePageList>()
                            
                            suspend fun buildList(items: List<AioItem>, titleName: String) {
                                val pageItems = items.take(24)
                                val searchResponses = pageItems.map {
                                    val coverUrl = getDirectImageUrl(it.cover)
                                    newMovieSearchResponse(it.title, buildDetailUrl(prov.code, it.id, it.title, it.cover), TvType.TvSeries) {
                                        this.posterUrl = coverUrl
                                    }
                                }
                                if (searchResponses.isNotEmpty()) {
                                    lists.add(HomePageList(titleName, searchResponses))
                                }
                            }
                            
                            buildList(nonDubItems, "[${prov.name}] - Semua")
                            if (providersWithDub.contains(prov.code)) {
                                buildList(dubItems, "[${prov.name}] - Dub Indo")
                            }
                            
                            lists
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }

            val providerLists = deferredLists.awaitAll().filterNotNull().flatten()
            homePageLists.addAll(providerLists)

            if (homePageLists.isNotEmpty() && context != null) {
                try {
                    val jsonStr = serializeHomeCache(homePageLists)
                    if (jsonStr.isNotEmpty()) {
                        context.setKey("dracin_aio_home_cache_v4", jsonStr)
                        context.setKey("dracin_aio_home_cache_time_v4", System.currentTimeMillis())
                        Log.d("DracinAIO", "Saved homepage data to local DataStore cache")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            return newHomePageResponse(homePageLists, hasNext = false)
        } else if (filterData.startsWith("global|")) {
            val type = filterData.substringAfter("global|")
            val term = when (type) {
                "top10" -> "Top 10 Minggu Ini"
                "trending" -> "Lagi Trending"
                else -> "Baru Tayang"
            }

            val responseText = httpGet(mainUrl)
            val list = ArrayList<SearchResponse>()
            if (responseText.isNotEmpty()) {
                val startIndex = responseText.indexOf(term)
                if (startIndex != -1) {
                    val block = responseText.substring(startIndex, kotlin.math.min(startIndex + 25000, responseText.length))
                    val anchorRegex = """<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    val matches = anchorRegex.findAll(block)
                    for (m in matches) {
                        val href = m.groupValues[1]
                        val inner = m.groupValues[2]
                        val watchPart = href.substringAfter("/watch/")
                        val providerCode = watchPart.substringBefore("/")
                        val id = watchPart.substringAfter("--")
                        if (providerCode.isEmpty() || id.isEmpty()) continue
                        val srcRegex = """src="([^"]+)"""".toRegex()
                        val altRegex = """alt="([^"]+)"""".toRegex()
                        val src = srcRegex.find(inner)?.groupValues?.get(1) ?: ""
                        val alt = altRegex.find(inner)?.groupValues?.get(1) ?: ""
                        val coverUrl = getDirectImageUrl(src)
                        list.add(
                            newMovieSearchResponse(alt, buildDetailUrl(providerCode, id, alt, src), TvType.TvSeries) {
                                this.posterUrl = coverUrl
                            }
                        )
                    }
                }
            }

            val pageSize = 24
            val start = (page - 1) * pageSize
            if (start >= list.size) return newHomePageResponse(request, emptyList(), hasNext = false)
            val end = kotlin.math.min(start + pageSize, list.size)
            val pageItems = list.subList(start, end)

            return newHomePageResponse(request, pageItems, hasNext = end < list.size)
        } else {
            val parts = filterData.split("|")
            if (parts.size < 2) return null
            val provider = parts[0]
            val filter = parts[1]

            val action = when (provider) {
                "dotdrama" -> "list"
                "netshort" -> "home"
                else -> "rank"
            }
            val url = "$API_URL/$provider?action=$action"
            val responseText = httpGet(url)
            val rawItems = parseRankItems(responseText)

            val filteredItems = if (filter == "dubindo") {
                rawItems.filter { isDubOrIndo(it.title) }
            } else {
                rawItems.filter { !isDubOrIndo(it.title) }
            }

            val pageSize = 24
            val start = (page - 1) * pageSize
            if (start >= filteredItems.size) return newHomePageResponse(request, emptyList(), hasNext = false)
            val end = kotlin.math.min(start + pageSize, filteredItems.size)
            val pageItems = filteredItems.subList(start, end)
            val hasNext = end < filteredItems.size

            val searchResponses = pageItems.map {
                val coverUrl = getDirectImageUrl(it.cover)
                newMovieSearchResponse(it.title, buildDetailUrl(provider, it.id, it.title, it.cover), TvType.TvSeries) {
                    this.posterUrl = coverUrl
                }
            }

            return newHomePageResponse(request, searchResponses, hasNext)
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        // Parallel search across all providers
        val deferredResults = withContext(Dispatchers.IO) {
            providers.map { prov ->
                async {
                    try {
                        val searchUrl = "$API_URL/${prov.code}?action=search&q=$encodedQuery"
                        val resText = httpGet(searchUrl)
                        val items = parseRankItems(resText)
                        items.map { item ->
                            val coverUrl = getDirectImageUrl(item.cover)
                            newMovieSearchResponse(item.title, buildDetailUrl(prov.code, item.id, item.title, item.cover), TvType.TvSeries) {
                                this.posterUrl = coverUrl
                            }
                        }
                    } catch (e: Exception) {
                        emptyList<SearchResponse>()
                    }
                }
            }
        }
        
        val results = deferredResults.awaitAll().flatten()
        return if (results.isEmpty()) null else results
    }

    override suspend fun load(url: String): LoadResponse? {
        val rawProvider: String
        val id: String
        var passedTitle = ""
        var passedCover = ""
        
        if (url.contains("#")) {
            val hashPart = url.substringAfter("#")
            rawProvider = hashPart.substringBefore("-")
            val idAndParams = hashPart.substringAfter("-")
            if (idAndParams.contains("?")) {
                id = idAndParams.substringBefore("?")
                val query = idAndParams.substringAfter("?")
                val pairs = query.split("&")
                for (pair in pairs) {
                    val idx = pair.indexOf("=")
                    if (idx != -1) {
                        val key = pair.substring(0, idx)
                        val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                        if (key == "title") passedTitle = value
                        if (key == "cover") passedCover = value
                    }
                }
            } else {
                id = idAndParams
            }
        } else {
            val parts = url.split("|")
            if (parts.size < 2) return null
            rawProvider = parts[0]
            id = parts[1]
        }
        val provider = rawProvider.substringAfterLast("/")

        val detailUrl = if (provider == "freereels") "$API_URL/$provider?action=detail&seriesKey=$id" else "$API_URL/$provider?action=detail&id=$id"
        val responseText = httpGet(detailUrl)
        if (responseText.isEmpty()) return null

        try {
            val root = JSONObject(responseText)
            var targetObj = root
            if (root.has("data")) {
                val dataVal = root.opt("data")
                if (dataVal is JSONObject) {
                    if (!dataVal.has("chapters") && !dataVal.has("chapterList")) {
                        targetObj = dataVal
                    }
                }
            }

            var title = ""
            var cover = ""
            var plot = ""
            val episodesList = ArrayList<Episode>()

            // Format 1: Reelshort (has "data" object containing "chapters")
            if (targetObj === root && root.has("data")) {
                val dataObj = root.getJSONObject("data")
                title = dataObj.optString("title").ifEmpty { dataObj.optString("bookName").ifEmpty { passedTitle.ifEmpty { id } } }
                cover = dataObj.optString("cover").ifEmpty { dataObj.optString("poster").ifEmpty { dataObj.optString("icon").ifEmpty { passedCover } } }
                plot = dataObj.optString("description").ifEmpty { dataObj.optString("introduction") }
                val bookId = dataObj.optString("bookId").ifEmpty { id }

                val chapters = dataObj.optJSONArray("chapters")
                if (chapters != null) {
                    for (i in 0 until chapters.length()) {
                        val ch = chapters.optJSONObject(i) ?: continue
                        val epNo = ch.optInt("episode", i + 1)
                        val epTitle = ch.optString("chapter_name").ifEmpty { ch.optString("title").ifEmpty { "Episode $epNo" } }
                        val chapterId = ch.optString("chapter_id")
                        
                        val videoFakeId = "$id::$bookId::$chapterId"
                        episodesList.add(
                            newEpisode("$provider|watch|$videoFakeId") {
                                this.name = epTitle
                                this.episode = epNo
                                this.season = 1
                            }
                        )
                    }
                }
            }
            // Format 2: Dramabox (has "bookInfo" and "chapterList")
            else if (targetObj.has("bookInfo")) {
                val bookInfo = targetObj.getJSONObject("bookInfo")
                title = bookInfo.optString("bookName").ifEmpty { bookInfo.optString("title").ifEmpty { passedTitle.ifEmpty { id } } }
                cover = bookInfo.optString("cover").ifEmpty { bookInfo.optString("poster").ifEmpty { bookInfo.optString("icon").ifEmpty { passedCover } } }
                plot = bookInfo.optString("introduction").ifEmpty { bookInfo.optString("description") }
                val bookId = bookInfo.optString("bookId").ifEmpty { id }

                val chapterList = targetObj.optJSONArray("chapterList")
                if (chapterList != null) {
                    for (i in 0 until chapterList.length()) {
                        val ch = chapterList.optJSONObject(i) ?: continue
                        val indexStr = ch.optString("indexStr")
                        val epNo = indexStr.toIntOrNull() ?: (i + 1)
                        val epTitle = ch.optString("title").ifEmpty { "Episode $epNo" }
                        
                        episodesList.add(
                            newEpisode("$provider|stream|$bookId|$epNo") {
                                this.name = epTitle
                                this.episode = epNo
                                this.season = 1
                            }
                        )
                    }
                }
            }
            // Format 3: Others (has keys "title", "cover", "episodes" / "list")
            else {
                val infoObj = if (root.has("drama")) root.getJSONObject("drama") else targetObj
                title = infoObj.optString("title").ifEmpty { infoObj.optString("name").ifEmpty { passedTitle.ifEmpty { id } } }
                cover = infoObj.optString("cover").ifEmpty { infoObj.optString("poster").ifEmpty { infoObj.optString("coverImgUrl").ifEmpty { infoObj.optString("icon").ifEmpty { passedCover } } } }
                plot = infoObj.optString("description").ifEmpty { infoObj.optString("summary").ifEmpty { infoObj.optString("introduce") } }

                val episodes = root.optJSONArray("episodes") ?: targetObj.optJSONArray("episodes") ?: targetObj.optJSONArray("list")
                if (episodes != null) {
                    for (i in 0 until episodes.length()) {
                        val ch = episodes.optJSONObject(i) ?: continue
                        val epNo = ch.optInt("episodeNo", 0).let { if (it > 0) it else ch.optInt("index", i + 1) }
                        val epTitle = ch.optString("title").ifEmpty { ch.optString("name").ifEmpty { "Episode $epNo" } }
                        
                        val directUrl = ch.optString("_h264").ifEmpty { ch.optString("_h265").ifEmpty { ch.optString("url").ifEmpty { ch.optString("videoUrl") } } }
                        val epData = if (directUrl.isNotEmpty()) {
                            "$provider|direct|$directUrl"
                        } else {
                            val videoFakeId = ch.optString("videoFakeId").ifEmpty { "$id::$epNo" }
                            val actionType = if (provider == "meloshort") "episode_video_melo" else "episode_video"
                            "$provider|$actionType|$videoFakeId"
                        }
                        
                        episodesList.add(
                            newEpisode(epData) {
                                this.name = epTitle
                                this.episode = epNo
                                this.season = 1
                            }
                        )
                    }
                }
            }

            val coverUrl = getDirectImageUrl(cover)
            return newTvSeriesLoadResponse(
                title,
                buildDetailUrl(provider, id, title, cover),
                TvType.TvSeries,
                episodesList
            ) {
                this.posterUrl = coverUrl
                this.plot = plot
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 3) return false
        val provider = parts[0].substringAfterLast("/")
        val actionType = parts[1]
        val param = parts[2]

        val videoUrl = when (actionType) {
            "direct" -> {
                val directUrl = parts.drop(2).joinToString("|")
                val isM3u8 = directUrl.substringBefore("?").contains(".m3u8", ignoreCase = true)
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val linkHeaders = getHeaders()
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = directUrl,
                        type = linkType
                    ) {
                        this.quality = Qualities.P720.value
                        this.headers = linkHeaders
                    }
                )
                return true
            }
            "watch" -> {
                // Reelshort watch format: param = filteredTitle::bookId::chapterId
                val subParts = param.split("::")
                if (subParts.size < 3) return false
                val filteredTitle = subParts[0]
                val bookId = subParts[1]
                val chapterId = subParts[2]
                "$API_URL/$provider?action=watch&bookId=$bookId&chapterId=$chapterId&episode=1&filteredTitle=$filteredTitle"
            }
            "stream" -> {
                // Dramabox stream format: param = bookId|episodeNo (we split param by | if it contains it)
                val subParts = param.split("|")
                val bookId = subParts[0]
                val epNo = if (subParts.size > 1) subParts[1] else "1"
                // For dramabox, the stream endpoint returns HLS/m3u8 directly. We can bypass parsing and register directly!
                val playUrl = "$API_URL/$provider?action=stream&bookId=$bookId&episode=$epNo"
                val linkHeaders = getHeaders()
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = playUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P720.value
                        this.headers = linkHeaders
                    }
                )
                return true
            }
            "episode_video_melo" -> {
                // Meloshort stream format: param = dramaId::intId::chapter
                val subParts = param.split("::")
                if (subParts.size < 2) return false
                val dramaId = subParts[0]
                val chapterId = if (subParts.size > 2) subParts[2] else subParts[1]
                "$API_URL/$provider?action=episode_video&dramaId=$dramaId&chapterId=$chapterId"
            }
            else -> {
                if (provider == "freereels") {
                    "$API_URL/$provider?action=stream&videoFakeId=$param"
                } else {
                    // Standard episode_video format: param = id::ep
                    val subParts = param.split("::")
                    val id = subParts[0]
                    val ep = if (subParts.size > 1) subParts[1] else "1"
                    "$API_URL/$provider?action=episode_video&id=$id&ep=$ep"
                }
            }
        }

        val responseText = httpGet(videoUrl)
        if (responseText.isEmpty()) return false

        try {
            val root = JSONObject(responseText)
            var streamUrl = ""
            if (root.has("data")) {
                streamUrl = root.getJSONObject("data").optString("url")
            } else {
                streamUrl = root.optString("url")
            }

            if (streamUrl.isEmpty()) return false

            var finalUrl = if (streamUrl.startsWith("/")) "$mainUrl$streamUrl" else streamUrl
            if (finalUrl.contains("url=") && !finalUrl.contains("/api/m3u8-proxy")) {
                val extracted = URLDecoder.decode(finalUrl.substringAfter("url="), "UTF-8")
                if (extracted.startsWith("http")) {
                    finalUrl = extracted
                }
            }

            if (finalUrl.contains("/api/m3u8-proxy?url=")) {
                try {
                    val res = httpGet(finalUrl)
                    if (res.contains("#EXTM3U")) {
                        val lines = res.split("\n")
                        val newLines = lines.map { line ->
                            rewritePlaylistLine(line)
                        }
                        val newM3u8 = newLines.joinToString("\n")
                        val streamId = System.currentTimeMillis().toString() + "_" + (1000..9999).random()
                        playlistCache[streamId] = newM3u8
                        val port = startLocalServer()
                        finalUrl = "http://127.0.0.1:$port/play_$streamId.m3u8"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val isM3u8 = finalUrl.substringBefore("?").contains(".m3u8", ignoreCase = true) || finalUrl.contains("http://127.0.0.1:")
            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            val subArray = root.optJSONArray("subtitles") ?: root.optJSONObject("data")?.optJSONArray("subtitles")
            if (subArray != null) {
                for (i in 0 until subArray.length()) {
                    val subObj = subArray.optJSONObject(i) ?: continue
                    var label = subObj.optString("label").ifEmpty { subObj.optString("lang") }
                    if (label.lowercase() == "in" || label.lowercase() == "id") {
                        label = "Indonesia"
                    }
                    val subUrl = subObj.optString("url")
                    if (subUrl.isNotEmpty()) {
                        var finalSubUrl = if (subUrl.startsWith("/")) "$mainUrl$subUrl" else subUrl
                        if (finalSubUrl.contains("url=")) {
                            try {
                                val extracted = URLDecoder.decode(finalSubUrl.substringAfter("url="), "UTF-8")
                                if (extracted.startsWith("http")) {
                                    finalSubUrl = extracted
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                label,
                                finalSubUrl
                            )
                        )
                    }
                }
            }

            val linkHeaders = getHeaders()
            callback.invoke(
                newExtractorLink(
                    name = name,
                    source = name,
                    url = finalUrl,
                    type = linkType
                ) {
                    this.quality = Qualities.P720.value
                    this.headers = linkHeaders
                }
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun serializeHomeCache(lists: List<HomePageList>): String {
        try {
            val root = JSONArray()
            for (list in lists) {
                val listObj = JSONObject()
                listObj.put("name", list.name)
                val itemsArr = JSONArray()
                for (item in list.list) {
                    val itemObj = JSONObject()
                    itemObj.put("title", item.name)
                    itemObj.put("url", item.url)
                    itemObj.put("poster", item.posterUrl)
                    itemsArr.put(itemObj)
                }
                listObj.put("items", itemsArr)
                root.put(listObj)
            }
            return root.toString()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            return ""
        }
    }

    private fun deserializeHomeCache(jsonStr: String): List<HomePageList> {
        val result = ArrayList<HomePageList>()
        try {
            val root = JSONArray(jsonStr)
            for (i in 0 until root.length()) {
                val listObj = root.getJSONObject(i)
                val name = listObj.getString("name")
                val itemsArr = listObj.getJSONArray("items")
                val searchResponses = ArrayList<SearchResponse>()
                for (j in 0 until itemsArr.length()) {
                    val itemObj = itemsArr.getJSONObject(j)
                    val title = itemObj.getString("title")
                    val url = itemObj.getString("url")
                    val poster = itemObj.optString("poster", "")
                    
                    searchResponses.add(
                        newMovieSearchResponse(title, url, TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    )
                }
                if (searchResponses.isNotEmpty()) {
                    result.add(HomePageList(name, searchResponses))
                }
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return result
    }
}
