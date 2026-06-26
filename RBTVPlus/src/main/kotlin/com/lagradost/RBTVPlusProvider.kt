package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

// Helper data class untuk data pertandingan live
data class LiveMatchInfo(
    val matchId: Long,
    val streamId: String,
    val matchTitle: String,
    val homeName: String?,
    val awayName: String?,
    val leagueName: String?
)

// ProtoParser ringan untuk membaca Protobuf biner
class ProtoParser(val data: ByteArray) {
    var idx = 0

    fun readVarint(): Long {
        var valL: Long = 0
        var shift = 0
        while (idx < data.size) {
            val b = data[idx].toInt() and 0xFF
            idx++
            valL = valL or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) {
                break
            }
            shift += 7
        }
        return valL
    }

    fun skipField(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> idx += 8
            2 -> {
                val length = readVarint().toInt()
                idx += length
            }
            5 -> idx += 4
            else -> {
                // skip wire group jika ada
            }
        }
    }
}

class RBTVPlusProvider : MainAPI() {
    override var mainUrl = "https://www.rbtvplus18.mom"
    override var name = "RBTV+"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "id"
    override val hasMainPage = true

    private val otherSports = listOf(4, 6, 7, 8, 10, 12, 13, 14, 15, 16, 90)

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

    private suspend fun getApiHost(): String {
        val fallback = "https://apis-data10.tcore131ybdf.ru"
        try {
            val response = app.get("$mainUrl/id/", timeout = 10)
            val html = response.text
            val jsUrls = Regex("""https://statics1\.tcore131ybdf\.ru/statics/[a-f0-9]+\.js""").findAll(html)
                .map { it.value }.toList()
            
            for (jsUrl in jsUrls) {
                try {
                    val jsContent = app.get(jsUrl, timeout = 5).text
                    val match = Regex("""['"]CF_DA_API['"]\s*:\s*['"](https://apis-data[0-9]*\.[a-zA-Z0-9.-]+\.[a-zA-Z]+)['"]""").find(jsContent)
                    if (match != null) {
                        return match.groupValues[1]
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return fallback
    }

    private suspend fun getBsToken(apiHost: String, sportType: Int): String? {
        val bsUrl = "$apiHost/api/common/bs?code=100&code=101&stream=true&sportType=$sportType&language=34"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "application/json, text/plain, */*"
        )
        try {
            val response = app.get(bsUrl, headers = headers, timeout = 10)
            val bytes = response.body.bytes()
            // Cari token 100 (\x08\x64\x12\x20)
            val marker = byteArrayOf(8, 100, 18, 32)
            var index = -1
            for (i in 0..bytes.size - marker.size) {
                var found = true
                for (j in marker.indices) {
                    if (bytes[i + j] != marker[j]) {
                        found = false
                        break
                    }
                }
                if (found) {
                    index = i
                    break
                }
            }
            if (index != -1 && index + 4 + 32 <= bytes.size) {
                val tokenBytes = bytes.copyOfRange(index + 4, index + 4 + 32)
                return String(tokenBytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private suspend fun fetchLiveMatchesRaw(apiHost: String, sportType: Int): ByteArray? {
        val token = getBsToken(apiHost, sportType) ?: return null
        val jsonParams = """{"sportType":$sportType,"language":34,"stream":true}"""
        val md5Params = md5(jsonParams)
        val sliceMd5 = md5Params.substring(0, 6)
        val sfver = "sfver$sliceMd5$token"

        val query = "sportType=$sportType&language=34&stream=true"
        val url = "$apiHost/$sfver/api/match/live?$query"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "application/json, text/plain, */*"
        )
        try {
            val response = app.get(url, headers = headers, timeout = 15)
            if (response.code == 200) {
                return response.body.bytes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun parseNameFromTag10(blockData: ByteArray): String? {
        val parser = ProtoParser(blockData)
        var name: String? = null
        while (parser.idx < blockData.size) {
            val b = parser.data[parser.idx].toInt() and 0xFF
            val tag = b shr 3
            val wire = b and 7
            parser.idx++

            if (tag == 3 && wire == 2) {
                val length = parser.readVarint().toInt()
                if (parser.idx + length <= blockData.size) {
                    val subData = blockData.copyOfRange(parser.idx, parser.idx + length)
                    parser.idx += length

                    val subParser = ProtoParser(subData)
                    while (subParser.idx < subData.size) {
                        val sb = subParser.data[subParser.idx].toInt() and 0xFF
                        val stag = sb shr 3
                        val swire = sb and 7
                        subParser.idx++
                        if (stag == 2 && swire == 2) {
                            val sLen = subParser.readVarint().toInt()
                            if (subParser.idx + sLen <= subData.size) {
                                name = String(subParser.data, subParser.idx, sLen, Charsets.UTF_8)
                            }
                            break
                        } else {
                            subParser.skipField(swire)
                        }
                    }
                }
                break
            } else {
                parser.skipField(wire)
            }
        }
        return name
    }

    private fun parseLiveMatches(data: ByteArray): List<LiveMatchInfo> {
        val parser = ProtoParser(data)
        val matches = ArrayList<LiveMatchInfo>()

        while (parser.idx < data.size) {
            val b = parser.data[parser.idx].toInt() and 0xFF
            val tag = b shr 3
            val wire = b and 7
            parser.idx++

            if (tag == 10 && wire == 2) {
                val length = parser.readVarint().toInt()
                if (parser.idx + length <= data.size) {
                    val dataBlock = data.copyOfRange(parser.idx, parser.idx + length)
                    parser.idx += length

                    val subParser = ProtoParser(dataBlock)
                    while (subParser.idx < dataBlock.size) {
                        val subB = dataBlock[subParser.idx].toInt() and 0xFF
                        val subTag = subB shr 3
                        val subWire = subB and 7
                        subParser.idx++

                        if ((subTag == 1 || subTag == 2) && subWire == 2) {
                            val mLen = subParser.readVarint().toInt()
                            if (subParser.idx + mLen <= dataBlock.size) {
                                val mData = dataBlock.copyOfRange(subParser.idx, subParser.idx + mLen)
                                subParser.idx += mLen

                                val mParser = ProtoParser(mData)
                                var matchId: Long = 0
                                var streamId: String? = null
                                var rawTitle: String? = null
                                var tag10Count = 0
                                var leagueName: String? = null
                                var homeName: String? = null
                                var awayName: String? = null
                                var matchStatus: Long = 0

                                while (mParser.idx < mData.size) {
                                    val mb = mData[mParser.idx].toInt() and 0xFF
                                    val mtag = mb shr 3
                                    val mwire = mb and 7
                                    mParser.idx++

                                    if (mtag == 1 && mwire == 0) {
                                        val tempId = mParser.readVarint()
                                        if (matchId == 0L) {
                                            matchId = tempId
                                        }
                                    } else if (mtag == 4 && mwire == 0) {
                                        matchStatus = mParser.readVarint()
                                    } else if (mtag == 2 && mwire == 2) {
                                        val sLen = mParser.readVarint().toInt()
                                        if (mParser.idx + sLen <= mData.size) {
                                            val valStr = String(mData, mParser.idx, sLen, Charsets.UTF_8)
                                            mParser.idx += sLen
                                            if (!valStr.contains("vs", ignoreCase = true)) {
                                                streamId = valStr
                                            } else {
                                                rawTitle = valStr
                                            }
                                        }
                                    } else if (mtag == 10 && mwire == 2) {
                                        tag10Count++
                                        val hLen = mParser.readVarint().toInt()
                                        if (mParser.idx + hLen <= mData.size) {
                                            val hData = mData.copyOfRange(mParser.idx, mParser.idx + hLen)
                                            mParser.idx += hLen

                                            val nameVal = parseNameFromTag10(hData)
                                            when (tag10Count) {
                                                1 -> leagueName = nameVal
                                                2 -> homeName = nameVal
                                                3 -> awayName = nameVal
                                            }
                                        }
                                    } else {
                                        mParser.skipField(mwire)
                                    }
                                }

                                val finalStreamId = streamId ?: matchId.toString()
                                val finalTitle = if (homeName != null && awayName != null) {
                                    "$homeName vs $awayName" + (if (leagueName != null) " ($leagueName)" else "")
                                } else {
                                    rawTitle ?: (leagueName ?: "RBTV+ Live Match")
                                }

                                val isLive = (matchStatus in 100..1600) || matchStatus == 9000L || matchStatus == 1L
                                if (isLive) {
                                    matches.add(
                                        LiveMatchInfo(
                                            matchId = matchId,
                                            streamId = finalStreamId,
                                            matchTitle = finalTitle,
                                            homeName = homeName,
                                            awayName = awayName,
                                            leagueName = leagueName
                                        )
                                    )
                                }
                            }
                        } else {
                            subParser.skipField(subWire)
                        }
                    }
                }
                break
            } else {
                parser.skipField(wire)
            }
        }
        return matches
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val apiHost = getApiHost()
        
        val (footballMatches, basketballMatches, tennisMatches, otherMatches) = coroutineScope {
            val f = async {
                try {
                    val bytes = fetchLiveMatchesRaw(apiHost, 1)
                    if (bytes != null) parseLiveMatches(bytes) else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val b = async {
                try {
                    val bytes = fetchLiveMatchesRaw(apiHost, 2)
                    if (bytes != null) parseLiveMatches(bytes) else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val t = async {
                try {
                    val bytes = fetchLiveMatchesRaw(apiHost, 3)
                    if (bytes != null) parseLiveMatches(bytes) else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val o = async {
                val deferredList = otherSports.map { sportId ->
                    async {
                        try {
                            val bytes = fetchLiveMatchesRaw(apiHost, sportId)
                            if (bytes != null) parseLiveMatches(bytes) else emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
                deferredList.awaitAll().flatten()
            }
            
            listOf(f.await(), b.await(), t.await(), o.await())
        }

        val homePages = ArrayList<HomePageList>()

        fun addCategory(title: String, matches: List<LiveMatchInfo>) {
            if (matches.isNotEmpty()) {
                val searchResps = matches.map { m ->
                    val encodedTitle = URLEncoder.encode(m.matchTitle, "UTF-8")
                    val detailUrl = "$mainUrl/id/match/detail.html?id=${m.matchId}&stream_id=${m.streamId}&title=$encodedTitle"
                    newLiveSearchResponse(
                        m.matchTitle,
                        detailUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = null
                    }
                }
                homePages.add(HomePageList(title, searchResps))
            }
        }

        addCategory("Sepakbola", footballMatches)
        addCategory("Basket", basketballMatches)
        addCategory("Tenis", tennisMatches)
        addCategory("Olahraga Lainnya", otherMatches)

        return if (homePages.isNotEmpty()) {
            newHomePageResponse(homePages, hasNext = false)
        } else {
            newHomePageResponse(
                listOf(
                    HomePageList(
                        "Info Live Match",
                        listOf(
                            newLiveSearchResponse(
                                "Sedang tidak ada siaran langsung saat ini (Silakan periksa lagi nanti)",
                                "$mainUrl/id/about-us.html",
                                TvType.Live
                            ) {
                                this.posterUrl = null
                            }
                        )
                    )
                ),
                hasNext = false
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiHost = getApiHost()
        val allSports = listOf(1, 2, 3) + otherSports
        
        val allMatches = coroutineScope {
            val deferredList = allSports.map { sportId ->
                async {
                    try {
                        val bytes = fetchLiveMatchesRaw(apiHost, sportId)
                        if (bytes != null) parseLiveMatches(bytes) else emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
            deferredList.awaitAll().flatten()
        }

        val results = ArrayList<SearchResponse>()
        for (m in allMatches) {
            if (m.matchTitle.contains(query, ignoreCase = true) || 
                (m.homeName?.contains(query, ignoreCase = true) == true) || 
                (m.awayName?.contains(query, ignoreCase = true) == true) || 
                (m.leagueName?.contains(query, ignoreCase = true) == true)) {
                
                val encodedTitle = URLEncoder.encode(m.matchTitle, "UTF-8")
                val detailUrl = "$mainUrl/id/match/detail.html?id=${m.matchId}&stream_id=${m.streamId}&title=$encodedTitle"
                
                val searchResp = newLiveSearchResponse(
                    m.matchTitle,
                    detailUrl,
                    TvType.Live
                ) {
                    this.posterUrl = null
                }
                results.add(searchResp)
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
        
        val streamId = queryMap["stream_id"] ?: queryMap["id"] ?: return null
        val encodedTitle = queryMap["title"]
        val matchTitle = if (!encodedTitle.isNullOrEmpty()) {
            try {
                java.net.URLDecoder.decode(encodedTitle, "UTF-8")
            } catch (e: Exception) {
                "RBTV+ Live Match"
            }
        } else {
            "RBTV+ Live Match"
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

            if (redirectUrl.startsWith("http://")) {
                redirectUrl = "https://" + redirectUrl.substring(7)
            } else if (!redirectUrl.startsWith("http")) {
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
