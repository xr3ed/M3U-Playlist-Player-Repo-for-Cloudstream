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
        data class ProviderConfig(
            val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        )
        val configs = mapOf(
            "default" to ProviderConfig(),
            "fundrama" to ProviderConfig(
                userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            ),
            "dramabox" to ProviderConfig(
                userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            ),
            "idrama" to ProviderConfig(
                userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            ),
            "kalostv" to ProviderConfig(
                userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            ),
            "shortical" to ProviderConfig(
                userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            ),
            "starshort" to ProviderConfig(
                userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            ),
            "cubetv" to ProviderConfig(
                userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
        )
        val networkSemaphore = java.util.concurrent.Semaphore(15)
        var appContext: Context? = null
        val BASE_URL = BuildConfig.DRACINAIO_V2_URL
        val customClient by lazy {
            app.baseClient.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
        val customRequests by lazy {
            com.lagradost.nicehttp.Requests(customClient)
        }
        val http1Client by lazy {
            customClient.newBuilder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .build()
        }
        val http1Requests by lazy {
            com.lagradost.nicehttp.Requests(http1Client)
        }
        val fastClient by lazy {
            customClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        }
        
        private var localServerPort = 0
        private var serverSocket: java.net.ServerSocket? = null
        
        init {
            startLocalServer()
        }
        
        fun startLocalServer() {
            if (serverSocket != null) return
            kotlin.concurrent.thread {
                try {
                    val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
                    serverSocket = ss
                    localServerPort = ss.localPort
                    android.util.Log.d("DracinAioV2", "Local server started on port $localServerPort")
                    while (!ss.isClosed) {
                        val socket = ss.accept()
                        kotlin.concurrent.thread {
                            handleLocalRequest(socket)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DracinAioV2", "Local server error", e)
                }
            }
        }
        
        private fun isUrlExpired(url: String): Boolean {
            try {
                var targetUrl = url
                if (url.contains("/e/m/")) {
                    val base64Part = url.substringAfter("/e/m/").substringBefore("?").substringBefore("/")
                    val payload = base64Part.substringBefore(".")
                    val decodedBytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                    val jsonStr = String(decodedBytes, Charsets.UTF_8)
                    val jsonObj = org.json.JSONObject(jsonStr)
                    val exp = jsonObj.optLong("exp", 0)
                    val now = System.currentTimeMillis() / 1000
                    if (exp < (now + 300)) return true
                    targetUrl = jsonObj.optString("src", "")
                }
                
                if (targetUrl.isEmpty()) return true
                if (targetUrl.contains("-stream.narto-drama.com") || targetUrl.contains("shortical")) return true
                if (targetUrl.contains("cdn.narto-drama.com") && !targetUrl.contains("?")) return true
                
                val uri = java.net.URI(targetUrl)
                val query = uri.query ?: ""
                if (query.isNotEmpty()) {
                    val params = query.split("&").associate {
                        val p = it.split("=")
                        if (p.size == 2) p[0].lowercase() to p[1] else "" to ""
                    }
                    val keysToCheck = listOf("verify", "auth_key", "ts", "exp", "expires", "wstime")
                    val now = System.currentTimeMillis() / 1000
                    for (key in keysToCheck) {
                        val value = params[key] ?: continue
                        if (value.isNotEmpty()) {
                            val timestampStr = value.substringBefore("-").substringBefore("_")
                            val timestamp = timestampStr.toLongOrNull() ?: continue
                            if (timestamp > 0 && timestamp < (now + 300)) {
                                return true
                            }
                        }
                    }
                }
                return false
            } catch (e: Exception) {
                return true
            }
        }
        
        private fun handleLocalRequest(socket: java.net.Socket) {
            try {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return
                
                var rangeHeader: String? = null
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.isEmpty()) break
                    if (trimmed.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = trimmed.substringAfter(":").trim()
                    }
                }
                
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    sendError(socket, 400, "Bad Request")
                    return
                }
                val path = parts[1]
                if (path.startsWith("/m3u8?")) {
                    handleM3u8(socket, path)
                } else if (path.startsWith("/key?")) {
                    handleKey(socket, path)
                } else if (path.startsWith("/mp4?") || path.startsWith("/ts?")) {
                    handleMp4(socket, path, rangeHeader)
                } else {
                    sendError(socket, 404, "Not Found")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
        
        private fun handleKey(socket: java.net.Socket, path: String) {
            val query = path.substringAfter("?")
            val params = query.split("&").associate { 
                val p = it.split("=")
                if (p.size == 2) p[0] to p[1] else "" to ""
            }
            val base64Val = java.net.URLDecoder.decode(params["val"] ?: "", "UTF-8")
            val decoded = try {
                android.util.Base64.decode(base64Val, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                ByteArray(0)
            }
            
            val out = socket.getOutputStream()
            val header = "HTTP/1.1 200 OK\r\n" +
                         "Content-Type: application/octet-stream\r\n" +
                         "Content-Length: ${decoded.size}\r\n" +
                         "Access-Control-Allow-Origin: *\r\n" +
                         "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(decoded)
            out.flush()
        }

        private fun handleMp4(socket: java.net.Socket, path: String, rangeHeader: String?) {
            val query = path.substringAfter("?")
            val params = query.split("&").associate { 
                val p = it.split("=")
                if (p.size == 2) p[0] to java.net.URLDecoder.decode(p[1], "UTF-8") else "" to ""
            }
            val originalUrl = params["url"] ?: ""
            if (originalUrl.isEmpty()) {
                sendError(socket, 400, "Missing URL")
                return
            }
            
            val provider = params["provider"] ?: ""
            val cookies = params["cookies"] ?: ""
            val config = configs[provider] ?: configs["default"] ?: ProviderConfig()
            
            val builder = okhttp3.Request.Builder()
                .url(originalUrl)
                .header("User-Agent", config.userAgent)
            
            if (originalUrl.contains("narto-drama.com")) {
                builder.header("Referer", "$BASE_URL/")
                builder.header("Origin", BASE_URL)
            }
            
            if (cookies.isNotEmpty()) {
                builder.header("Cookie", cookies)
            }
            
            if (rangeHeader != null) {
                builder.header("Range", rangeHeader)
            }
            
            val req = builder.build()
            
            try {
                customClient.newCall(req).execute().use { res ->
                    val code = res.code
                    val out = socket.getOutputStream()
                    
                    val headerBuilder = StringBuilder()
                    headerBuilder.append("HTTP/1.1 $code ${res.message}\r\n")
                    for (i in 0 until res.headers.size) {
                        val name = res.headers.name(i)
                        val value = res.headers.value(i)
                        if (!name.equals("Connection", ignoreCase = true) && 
                            !name.equals("Transfer-Encoding", ignoreCase = true)) {
                            headerBuilder.append("$name: $value\r\n")
                        }
                    }
                    headerBuilder.append("Connection: close\r\n\r\n")
                    
                    out.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))
                    
                    val body = res.body
                    if (body != null) {
                        val buffer = ByteArray(8192)
                        val inputStream = body.byteStream()
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        private fun handleM3u8(socket: java.net.Socket, path: String) {
            val query = path.substringAfter("?")
            val params = query.split("&").associate { 
                val p = it.split("=")
                if (p.size == 2) p[0] to java.net.URLDecoder.decode(p[1], "UTF-8") else "" to ""
            }
            val originalUrl = params["url"] ?: ""
            if (originalUrl.isEmpty()) {
                sendError(socket, 400, "Missing URL")
                return
            }
            
            val provider = params["provider"] ?: ""
            val cookies = params["cookies"] ?: ""
            val config = configs[provider] ?: configs["default"] ?: ProviderConfig()
            
            val reqBuilder = okhttp3.Request.Builder()
                .url(originalUrl)
                .header("User-Agent", config.userAgent)
            
            if (originalUrl.contains("narto-drama.com")) {
                reqBuilder.header("Referer", "$BASE_URL/")
                reqBuilder.header("Origin", BASE_URL)
            }
            
            if (cookies.isNotEmpty()) {
                reqBuilder.header("Cookie", cookies)
            }
            val req = reqBuilder.build()
                
            val originalM3u8 = try {
                customClient.newCall(req).execute().body?.string() ?: ""
            } catch (e: Exception) {
                ""
            }
            
            if (originalM3u8.isEmpty()) {
                sendError(socket, 502, "Bad Gateway")
                return
            }
            
            val uri = java.net.URI(originalUrl)
            val baseScheme = uri.scheme
            val baseHost = uri.host
            val basePort = if (uri.port != -1) ":${uri.port}" else ""
            val basePrefix = "$baseScheme://$baseHost$basePort"
            
            val lines = originalM3u8.split("\n")
            val newM3u8 = StringBuilder()
            for (line in lines) {
                var processedLine = line.trim()
                if (processedLine.isEmpty()) continue
                
                if (processedLine.startsWith("#")) {
                    if (processedLine.startsWith("#EXT-X-KEY:")) {
                        val pattern = Regex("""URI="data:text/plain;base64,([^"]+)"""")
                        val match = pattern.find(processedLine)
                        if (match != null) {
                            val base64Key = match.groupValues[1]
                            val localKeyUrl = "http://127.0.0.1:$localServerPort/key?val=${java.net.URLEncoder.encode(base64Key, "UTF-8")}"
                            processedLine = processedLine.replace(match.value, """URI="$localKeyUrl"""")
                        }
                    }
                    
                    val uriPattern = Regex("""URI="([^"]+)"""")
                    processedLine = uriPattern.replace(processedLine) { matchResult ->
                        val rawUri = matchResult.groupValues[1]
                        if (rawUri.startsWith("data:") || rawUri.startsWith("http://127.0.0.1:") || rawUri.contains("/key?")) {
                            matchResult.value
                        } else {
                            val absoluteUrl = if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) {
                                rawUri
                            } else if (rawUri.startsWith("/")) {
                                "$basePrefix$rawUri"
                            } else {
                                val pathPrefix = originalUrl.substringBeforeLast("/")
                                "$pathPrefix/$rawUri"
                            }
                            val route = if (absoluteUrl.contains(".m3u8")) "m3u8" else "ts"
                            val proxiedUrl = "http://127.0.0.1:$localServerPort/$route?url=${java.net.URLEncoder.encode(absoluteUrl, "UTF-8")}${if (provider.isNotEmpty()) "&provider=$provider" else ""}${if (cookies.isNotEmpty()) "&cookies=${java.net.URLEncoder.encode(cookies, "UTF-8")}" else ""}"
                            """URI="$proxiedUrl""""
                        }
                    }
                    
                    newM3u8.append(processedLine).append("\n")
                } else {
                    val absoluteUrl = if (processedLine.startsWith("http://") || processedLine.startsWith("https://")) {
                        processedLine
                    } else if (processedLine.startsWith("/")) {
                        "$basePrefix$processedLine"
                    } else {
                        val pathPrefix = originalUrl.substringBeforeLast("/")
                        "$pathPrefix/$processedLine"
                    }
                    val route = if (absoluteUrl.contains(".m3u8")) "m3u8" else "ts"
                    val proxiedSegmentUrl = "http://127.0.0.1:$localServerPort/$route?url=${java.net.URLEncoder.encode(absoluteUrl, "UTF-8")}${if (provider.isNotEmpty()) "&provider=$provider" else ""}${if (cookies.isNotEmpty()) "&cookies=${java.net.URLEncoder.encode(cookies, "UTF-8")}" else ""}"
                    newM3u8.append(proxiedSegmentUrl).append("\n")
                }
            }
            
            val responseBody = newM3u8.toString()
            val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
            val out = socket.getOutputStream()
            val header = "HTTP/1.1 200 OK\r\n" +
                         "Content-Type: application/vnd.apple.mpegurl\r\n" +
                         "Content-Length: ${responseBytes.size}\r\n" +
                         "Access-Control-Allow-Origin: *\r\n" +
                         "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(responseBytes)
            out.flush()
        }
        
        private fun sendError(socket: java.net.Socket, code: Int, msg: String) {
            val response = "HTTP/1.1 $code $msg\r\n" +
                           "Content-Type: text/plain\r\n" +
                           "Content-Length: ${msg.length}\r\n" +
                           "Access-Control-Allow-Origin: *\r\n" +
                           "Connection: close\r\n\r\n" +
                           msg
            try {
                val out = socket.getOutputStream()
                out.write(response.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: Exception) {}
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
    private val providerParsedItems = java.util.concurrent.ConcurrentHashMap<String, ArrayList<SearchResponse>>()
    private val providerServerPages = java.util.concurrent.ConcurrentHashMap<String, Int>()

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
        networkSemaphore.acquire()
        return try {
            http1Client.newCall(httpRequest).execute().body?.string() ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        } finally {
            networkSemaphore.release()
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
        networkSemaphore.acquire()
        return try {
            val response = http1Client.newCall(httpRequest).execute()
            val code = response.code
            val bodyStr = response.body?.string() ?: ""
            android.util.Log.d("DracinAioV2", "fetchProviderPagePCombined - response code: $code, body length: ${bodyStr.length}")
            bodyStr
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        } finally {
            networkSemaphore.release()
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
            providerParsedItems[providerCode] = ArrayList()
            providerServerPages[providerCode] = 1
        }
        
        var page1Res = ""
        if (context != null) {
            val cached = context.getKey<String>(cacheKey) ?: ""
            val cacheTime = context.getKey<Long>("dracin_v2_cache_time_${providerCode}_v7") ?: 0L
            val now = System.currentTimeMillis()
            if (cached.contains("\"sections\"") && (now - cacheTime < 600000L)) {
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
        
        val parsedList = providerParsedItems.getOrPut(providerCode) { ArrayList() }
        var serverPage = providerServerPages.getOrPut(providerCode) { 1 }
        
        val chunkSize = 9
        val targetStart = (page - 1) * chunkSize
        val targetEnd = page * chunkSize
        
        val maxPagesToFetch = if (page == 1) 2 else 10
        while (parsedList.size < targetEnd && serverPage <= maxPagesToFetch) {
            val pageRes = if (serverPage == 1) {
                page1Res
            } else {
                fetchProviderPagePCombined(providerCode, tabKeys, serverPage)
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
                                    parsedList.add(newTvSeriesSearchResponse(title, maskedUrl) {
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
            
            if (!pageGotItems) {
                break
            }
            
            serverPage++
            providerServerPages[providerCode] = serverPage
        }
        
        val chunkItems = ArrayList<SearchResponse>()
        if (targetStart < parsedList.size) {
            val endIdx = minOf(targetEnd, parsedList.size)
            for (i in targetStart until endIdx) {
                chunkItems.add(parsedList[i])
            }
        }
        
        val homePageList = HomePageList(request.name, chunkItems)
        val hasNextPage = (parsedList.size > targetEnd) || (serverPage <= 30)
        
        val titlesList = chunkItems.map { it.name }
        android.util.Log.d("DracinAioV2", "getMainPage Option A return - items size: ${chunkItems.size}, total parsed size: ${parsedList.size}, titles: $titlesList, hasNextPage: $hasNextPage")
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
            http1Client.newCall(httpRequest).execute().body?.string() ?: ""
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
        val html = http1Requests.get(cleanUrl).text
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
        val separator = if (cleanUrl.contains("?")) "&" else "?"
        val cacheBusterUrl = "$cleanUrl${separator}_t=${System.currentTimeMillis()}"
        val response = http1Requests.get(
            cacheBusterUrl,
            headers = mapOf(
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
        )
        val html = response.text
        
        var episodePlayUrl = ""
        var episodeDirectPlayUrl = ""
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
                        episodePlayUrl = item.optString("play_url").replace("\\/", "/")
                        if (episodePlayUrl.isEmpty()) {
                            episodePlayUrl = item.optString("schema_content_url").replace("\\/", "/")
                        }
                        episodeDirectPlayUrl = item.optString("direct_play_url").replace("\\/", "/")
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
        
        val tokenMatchInit = Regex("""refreshSourceContextToken\s*=\s*["']([^"']+)["']""").find(html)
        val tokenInit = tokenMatchInit?.groupValues?.get(1) ?: ""
        val providerCode = try {
            if (tokenInit.isNotEmpty()) {
                val parts = tokenInit.split(".")
                var resolved = ""
                for (p in parts) {
                    if (p.startsWith("ey")) {
                        val decodedBytes = android.util.Base64.decode(p, android.util.Base64.DEFAULT)
                        val decodedStr = String(decodedBytes, Charsets.UTF_8)
                        val jsonObj = org.json.JSONObject(decodedStr)
                        resolved = jsonObj.optString("source_app_name", "")
                        if (resolved.isNotEmpty()) break
                    }
                }
                resolved
            } else ""
        } catch (e: Exception) {
            ""
        }
        android.util.Log.d("DracinAioV2", "loadLinks providerCode: $providerCode")

        val config = configs[providerCode] ?: configs["default"] ?: ProviderConfig()

        // 1. Bypass Ad Gate & Consent FIRST to unlock the session (crucial for both fast-path and slow-path)
        val csrfTokenMatch = Regex("""adsCsrfToken\s*=\s*["']([^"']+)["']""").find(html)
        val csrfToken = csrfTokenMatch?.groupValues?.get(1) ?: ""
        if (csrfToken.isNotEmpty()) {
            val gateTokenMatch = Regex("""adsGateUnlockToken\s*=\s*["']([^"']+)["']""").find(html)
            val consentTokenMatch = Regex("""adsConsentTokens\s*=\s*\{[^:]+:\s*["']([^"']+)["']""").find(html)
            
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
                            .header("User-Agent", config.userAgent)
                            .header("Cookie", cookieString)
                            .build()
                        try { 
                            http1Client.newCall(unlockReq).execute().use { res ->
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
                            .header("User-Agent", config.userAgent)
                            .header("Cookie", cookieString)
                            .build()
                        try { 
                            http1Client.newCall(consentReq).execute().use { res ->
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

        // Fast-path: extract video URL directly from JSON-LD schema (application/ld+json) if available
        val contentUrlMatch = Regex(""""contentUrl"\s*:\s*["']([^"']+)["']""").find(html)
        var schemaPlayUrl = contentUrlMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
        
        val candidateUrls = mutableListOf<Pair<String, String>>()
        
        if (schemaPlayUrl.isNotEmpty() && schemaPlayUrl != "null") {
            candidateUrls.add(Pair(this.name, schemaPlayUrl))
        }
        
        if (candidateUrls.isEmpty() && (episodePlayUrl.isNotEmpty() || episodeDirectPlayUrl.isNotEmpty())) {
            android.util.Log.d("DracinAioV2", "Fell back to episodeItemsRaw urls")
            if (episodeDirectPlayUrl.isNotEmpty() && episodeDirectPlayUrl != "null") {
                candidateUrls.add(Pair("${this.name} Direct", episodeDirectPlayUrl))
            }
            if (episodePlayUrl.isNotEmpty() && episodePlayUrl != "null") {
                candidateUrls.add(Pair("${this.name} Proxy", episodePlayUrl))
            }
        }
        for (cand in candidateUrls) {
            val exp = isUrlExpired(cand.second)
            android.util.Log.d("DracinAioV2", "Candidate URL: '${cand.second}' -> isExpired: $exp")
        }
        val hasValidCandidate = candidateUrls.isNotEmpty() && candidateUrls.any { !isUrlExpired(it.second) }
        if (hasValidCandidate) {
            android.util.Log.d("DracinAioV2", "Found valid unexpired candidate URLs, using fast-path")
            candidateUrls.forEach { (sourceName, rawUrl) ->
                if (isUrlExpired(rawUrl)) return@forEach
                val isHls = rawUrl.contains(".m3u8") || rawUrl.contains("narto-drama.com/e/m/") || Regex("""https?://stream(?:-[a-z0-9]+)?\.narto-drama\.com""").containsMatchIn(rawUrl)
                val cleanedPlayUrl = if (rawUrl.contains("otte.cache.aiv-cdn.net")) {
                    rawUrl
                        .replace(Regex("""^https?://[^/]+\.(?:workers|pages)\.dev/.*?(https?://)"""), "$1")
                        .replace("https://lhr-nitro.workers.dev/proxify?url=", "")
                        .replace("https://pdx-nitro.workers.dev/proxify?url=", "")
                } else {
                    rawUrl
                }
                
                val linkType = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val localPlayUrl = if (isHls && localServerPort > 0) {
                    "http://127.0.0.1:$localServerPort/m3u8?url=${java.net.URLEncoder.encode(cleanedPlayUrl, "UTF-8")}${if (providerCode.isNotEmpty()) "&provider=$providerCode" else ""}${if (cookieString.isNotEmpty()) "&cookies=${java.net.URLEncoder.encode(cookieString, "UTF-8")}" else ""}"
                } else {
                    cleanedPlayUrl
                }
                
                val isNartoHost = rawUrl.contains("narto-drama.com")
                val linkHeaders = if (isNartoHost) {
                    mapOf(
                        "Referer" to "$BASE_URL/",
                        "Origin" to BASE_URL,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    )
                } else {
                    mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    )
                }

                callback.invoke(
                    newExtractorLink(
                        name = sourceName,
                        source = this.name,
                        url = localPlayUrl,
                        type = linkType
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = linkHeaders
                    }
                )
            }
            
            allSubtitles.values.forEach {
                subtitleCallback.invoke(it)
            }
            return true
        }
        

        val tokenMatch = Regex("""refreshSourceContextToken\s*=\s*["']([^"']+)["']""").find(html)
        if (tokenMatch != null) {
            val token = tokenMatch.groupValues[1]
            val baseUrl = response.url.substringBefore("?")

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
                .header("User-Agent", config.userAgent)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Cookie", cookieString)
                .build()
                
            val res = try {
                http1Client.newCall(httpRequest).execute().body?.string() ?: ""
            } catch (e: Exception) {
                android.util.Log.e("DracinAioV2", "loadLinks refresh-source fetch failed", e)
                e.printStackTrace()
                ""
            }
            
            android.util.Log.d("DracinAioV2", "loadLinks refresh-source response: $res")
            
            try {
                val jsonObj = org.json.JSONObject(res)
                val playUrl = jsonObj.optString("play_url").replace("\\/", "/")
                val directPlayUrl = jsonObj.optString("direct_play_url").replace("\\/", "/")
                
                val refreshUrls = mutableListOf<Pair<String, String>>()
                if (directPlayUrl.isNotEmpty() && directPlayUrl != "null") {
                    refreshUrls.add(Pair("${this.name} Direct", directPlayUrl))
                }
                if (playUrl.isNotEmpty() && playUrl != "null") {
                    refreshUrls.add(Pair("${this.name} Proxy", playUrl))
                }
                
                if (refreshUrls.isNotEmpty()) {
                    refreshUrls.forEach { (sourceName, rawUrl) ->
                        val isHls = jsonObj.optBoolean("direct_play_is_hls", false) || rawUrl.contains(".m3u8") || rawUrl.contains("narto-drama.com/e/m/") || Regex("""https?://stream(?:-[a-z0-9]+)?\.narto-drama\.com""").containsMatchIn(rawUrl)
                        val cleanedPlayUrl = if (rawUrl.contains("otte.cache.aiv-cdn.net")) {
                            rawUrl
                                .replace(Regex("""^https?://[^/]+\.(?:workers|pages)\.dev/.*?(https?://)"""), "$1")
                                .replace("https://lhr-nitro.workers.dev/proxify?url=", "")
                                .replace("https://pdx-nitro.workers.dev/proxify?url=", "")
                        } else {
                            rawUrl
                        }
                        
                        val linkType = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        val localPlayUrl = if (localServerPort > 0) {
                            val route = if (isHls) "m3u8" else "mp4"
                            "http://127.0.0.1:$localServerPort/$route?url=${java.net.URLEncoder.encode(cleanedPlayUrl, "UTF-8")}${if (providerCode.isNotEmpty()) "&provider=$providerCode" else ""}${if (cookieString.isNotEmpty()) "&cookies=${java.net.URLEncoder.encode(cookieString, "UTF-8")}" else ""}"
                        } else {
                            cleanedPlayUrl
                        }
                        val isNartoHost = rawUrl.contains("narto-drama.com")
                        val linkHeaders = if (isNartoHost) {
                            mapOf(
                                "Referer" to "$BASE_URL/",
                                "Origin" to BASE_URL,
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            )
                        } else {
                            mapOf(
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            )
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                name = sourceName,
                                source = this.name,
                                url = localPlayUrl,
                                type = linkType
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.headers = linkHeaders
                            }
                        )
                    }
                    
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
        
        // Fallback to fast-path URLs
        if (candidateUrls.isNotEmpty()) {
            android.util.Log.d("DracinAioV2", "Fell back to fast-path URLs")
            candidateUrls.forEach { (sourceName, rawUrl) ->
                val isHls = rawUrl.contains(".m3u8") || rawUrl.contains("narto-drama.com/e/m/") || Regex("""stream.*?\.narto-drama\.com""").containsMatchIn(rawUrl)
                val cleanedPlayUrl = if (rawUrl.contains("otte.cache.aiv-cdn.net")) {
                    rawUrl
                        .replace(Regex("""^https?://[^/]+\.(?:workers|pages)\.dev/.*?(https?://)"""), "$1")
                        .replace("https://lhr-nitro.workers.dev/proxify?url=", "")
                        .replace("https://pdx-nitro.workers.dev/proxify?url=", "")
                } else {
                    rawUrl
                }
                
                val linkType = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val localPlayUrl = if (isHls && localServerPort > 0) {
                    "http://127.0.0.1:$localServerPort/m3u8?url=${java.net.URLEncoder.encode(cleanedPlayUrl, "UTF-8")}"
                } else {
                    cleanedPlayUrl
                }
                val isNartoHost = rawUrl.contains("narto-drama.com")
                val linkHeaders = if (isNartoHost) {
                    mapOf(
                        "Referer" to "$BASE_URL/",
                        "Origin" to BASE_URL,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    )
                } else {
                    mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    )
                }

                callback.invoke(
                    newExtractorLink(
                        name = sourceName,
                        source = this.name,
                        url = localPlayUrl,
                        type = linkType
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = linkHeaders
                    }
                )
            }
            
            allSubtitles.values.forEach {
                subtitleCallback.invoke(it)
            }
            return true
        }
        
        return false
    }
}
