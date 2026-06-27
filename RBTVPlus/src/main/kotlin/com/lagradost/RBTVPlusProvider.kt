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
// Helper data class untuk data pertandingan live
data class LiveMatchInfo(
    val matchId: Long,
    val streamId: String,
    val matchTitle: String,
    val homeName: String?,
    val awayName: String?,
    val leagueName: String?,
    val sportType: Int,
    val posterUrl: String? = null,
    val matchTime: Long = 0,
    val matchStatus: Long = 0
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

    private var serverTimeOffset: Long = 0L

    private fun getServerTimeFromHeaders(headers: okhttp3.Headers?): Long? {
        val dateHeader = headers?.get("Date") ?: return null
        return try {
            val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
            sdf.parse(dateHeader)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanText(text: String?): String? {
        return text?.replace("&", "&amp;")
            ?.replace("<", "&lt;")
            ?.replace(">", "&gt;")
            ?.replace("\"", "&quot;")
            ?.replace("'", "&apos;")
    }

    private fun generateDynamicSvgPoster(
        sport: String,
        league: String?,
        team1: String?,
        team2: String?,
        timeStr: String,
        sportType: Int
    ): String {
        val sportLabel = cleanText(sport.uppercase()) ?: ""
        val leagueLabel = cleanText(league ?: "Tournament") ?: ""
        val t1 = cleanText(team1 ?: "Team A") ?: ""
        val t2 = cleanText(team2 ?: "Team B") ?: ""

        // Tema warna dinamis berdasarkan sportType
        val (themeStart, themeEnd, accentColor) = when (sportType) {
            1 -> Triple("#11998e", "#38ef7d", "#38ef7d") // Football (Green)
            2 -> Triple("#ff9900", "#ff5b00", "#ff9900") // Basketball (Orange)
            3, 12 -> Triple("#d4fc79", "#96e6a1", "#d4fc79") // Tennis / Badminton (Lime)
            14 -> Triple("#f857a6", "#ff5858", "#ff5858") // Fighting (Crimson)
            7, 15 -> Triple("#3a7bd5", "#3a6073", "#3a7bd5") // Motorsport / Cycling (Blue)
            else -> Triple("#7f00ff", "#ff007f", "#00f2fe") // Default (Purple/Neon Cyan)
        }

        // Sesuaikan ukuran font nama tim agar tidak terpotong
        val t1FontSize = if (t1.length > 20) "13" else if (t1.length > 15) "15" else "19"
        val t2FontSize = if (t2.length > 20) "13" else if (t2.length > 15) "15" else "19"

        val svg = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 600" width="100%" height="100%">
            <defs>
                <linearGradient id="bgGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                    <stop offset="0%" stop-color="#0f0c1b"/>
                    <stop offset="50%" stop-color="#15102a"/>
                    <stop offset="100%" stop-color="#06050a"/>
                </linearGradient>
                <linearGradient id="accentGrad" x1="0%" y1="0%" x2="100%" y2="0%">
                    <stop offset="0%" stop-color="$accentColor"/>
                    <stop offset="100%" stop-color="#4facfe"/>
                </linearGradient>
                <linearGradient id="liveGrad" x1="0%" y1="0%" x2="100%" y2="0%">
                    <stop offset="0%" stop-color="$themeStart"/>
                    <stop offset="100%" stop-color="$themeEnd"/>
                </linearGradient>
                <linearGradient id="cardGrad" x1="0%" y1="0%" x2="0%" y2="100%">
                    <stop offset="0%" stop-color="#ffffff" stop-opacity="0.07"/>
                    <stop offset="100%" stop-color="#ffffff" stop-opacity="0.02"/>
                </linearGradient>
                <filter id="glow" x="-20%" y="-20%" width="140%" height="140%">
                    <feGaussianBlur stdDeviation="8" result="blur" />
                    <feComposite in="SourceGraphic" in2="blur" operator="over"/>
                </filter>
            </defs>
            <rect width="400" height="600" fill="url(#bgGrad)"/>
            <circle cx="200" cy="300" r="180" fill="#7f00ff" opacity="0.15" filter="url(#glow)"/>
            <circle cx="50" cy="100" r="100" fill="#00f2fe" opacity="0.1" filter="url(#glow)"/>
            <circle cx="350" cy="500" r="120" fill="#ff007f" opacity="0.1" filter="url(#glow)"/>
            <rect x="25" y="40" width="350" height="520" rx="24" fill="url(#cardGrad)" stroke="#ffffff" stroke-width="1.5" stroke-opacity="0.15"/>
            <rect x="110" y="70" width="180" height="24" rx="12" fill="#ffffff" fill-opacity="0.08"/>
            <text x="200" y="86" font-family="sans-serif" font-size="9" font-weight="900" fill="#a0a5c0" letter-spacing="2" text-anchor="middle">$sportLabel</text>
            <text x="200" y="135" font-family="sans-serif" font-size="13" font-weight="600" fill="#00f2fe" text-anchor="middle" opacity="0.9">$leagueLabel</text>
            <text x="200" y="225" font-family="sans-serif" font-size="$t1FontSize" font-weight="800" fill="#ffffff" text-anchor="middle">$t1</text>
            <circle cx="200" cy="285" r="28" fill="#15102a" stroke="url(#accentGrad)" stroke-width="2" filter="url(#glow)"/>
            <text x="200" y="292" font-family="sans-serif" font-size="18" font-weight="900" fill="url(#accentGrad)" text-anchor="middle">VS</text>
            <text x="200" y="365" font-family="sans-serif" font-size="$t2FontSize" font-weight="800" fill="#ffffff" text-anchor="middle">$t2</text>
            <g transform="translate(130, 440)">
                <rect width="140" height="36" rx="18" fill="url(#liveGrad)" filter="url(#glow)"/>
                <circle cx="28" cy="18" r="5" fill="#ffffff"/>
                <text x="80" y="23" font-family="sans-serif" font-size="12" font-weight="900" fill="#ffffff" text-anchor="middle" letter-spacing="1">LIVE NOW</text>
            </g>
            <text x="200" y="515" font-family="sans-serif" font-size="11" font-weight="500" fill="#6d7598" text-anchor="middle">$timeStr</text>
        </svg>
        """.trimIndent()

        val base64 = android.util.Base64.encodeToString(svg.toByteArray(), android.util.Base64.NO_WRAP)
        return "data:image/svg+xml;base64,$base64"
    }

    private val sportTypes = listOf(1, 2, 3, 4, 6, 7, 8, 10, 12, 13, 14, 15, 16, 90)
    private val sportNames = mapOf(
        1 to "Sepak Bola",
        2 to "Basket",
        3 to "Tenis",
        4 to "Bisbol",
        6 to "Kriket",
        7 to "Motorsport",
        8 to "Rugby",
        10 to "Aussie Rules",
        12 to "Bulutangkis",
        13 to "Bola Voli",
        14 to "Fighting",
        15 to "Balap Sepeda",
        16 to "Bola Tangan",
        90 to "Golf"
    )

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

    private fun rot47(text: String): String {
        val result = StringBuilder()
        for (i in 0 until text.length) {
            val c = text[i]
            val y = c.toInt()
            if (y in 33..79) {
                result.append((y + 47).toChar())
            } else if (y in 80..126) {
                result.append((y - 47).toChar())
            } else {
                result.append(c)
            }
        }
        return result.toString()
    }

    private fun encryptAesCtr(token: String): String {
        val keyStr = "a7981cc9eb2f4d19dcfea57b101ecd89"
        val ivStr = "8017d3a8f1400d2f"
        
        val keySpec = javax.crypto.spec.SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = javax.crypto.spec.IvParameterSpec(ivStr.toByteArray(Charsets.UTF_8))
        
        val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val base64Str = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        return URLEncoder.encode(base64Str, "UTF-8")
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
                val serverTime = getServerTimeFromHeaders(response.headers)
                if (serverTime != null) {
                    serverTimeOffset = serverTime - System.currentTimeMillis()
                }
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
            val keyVal = parser.readVarint().toInt()
            val tag = keyVal shr 3
            val wire = keyVal and 7

            if (tag == 3 && wire == 2) {
                val length = parser.readVarint().toInt()
                if (parser.idx + length <= blockData.size) {
                    val subData = blockData.copyOfRange(parser.idx, parser.idx + length)
                    parser.idx += length

                    val subParser = ProtoParser(subData)
                    while (subParser.idx < subData.size) {
                        val skey = subParser.readVarint().toInt()
                        val stag = skey shr 3
                        val swire = skey and 7
                        if (stag == 2 && swire == 2) {
                            val sLen = subParser.readVarint().toInt()
                            if (subParser.idx + sLen <= subData.size) {
                                name = String(subParser.data, subParser.idx, sLen, Charsets.UTF_8)
                                subParser.idx += sLen
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

    private fun parseLogoFromTag10(blockData: ByteArray): String? {
        val parser = ProtoParser(blockData)
        var logo: String? = null
        while (parser.idx < blockData.size) {
            val keyVal = parser.readVarint().toInt()
            val tag = keyVal shr 3
            val wire = keyVal and 7

            if (tag == 4 && wire == 2) {
                val length = parser.readVarint().toInt()
                if (parser.idx + length <= blockData.size) {
                    logo = String(blockData, parser.idx, length, Charsets.UTF_8)
                    parser.idx += length
                }
                break
            } else {
                parser.skipField(wire)
            }
        }
        return logo
    }

    private fun parseLiveMatches(data: ByteArray, sportType: Int): List<LiveMatchInfo> {
        val parser = ProtoParser(data)
        val matches = ArrayList<LiveMatchInfo>()

        while (parser.idx < data.size) {
            val keyVal = parser.readVarint().toInt()
            val tag = keyVal shr 3
            val wire = keyVal and 7

            if (tag == 10 && wire == 2) {
                val length = parser.readVarint().toInt()
                if (parser.idx + length <= data.size) {
                    val dataBlock = data.copyOfRange(parser.idx, parser.idx + length)
                    parser.idx += length

                    val subParser = ProtoParser(dataBlock)
                    while (subParser.idx < dataBlock.size) {
                        val subKeyVal = subParser.readVarint().toInt()
                        val subTag = subKeyVal shr 3
                        val subWire = subKeyVal and 7

                        if (subTag == 1 && subWire == 2) { // PBDataMatch
                            val mLen = subParser.readVarint().toInt()
                            if (subParser.idx + mLen <= dataBlock.size) {
                                val mData = dataBlock.copyOfRange(subParser.idx, subParser.idx + mLen)
                                subParser.idx += mLen

                                val mParser = ProtoParser(mData)
                                var matchId: Long = 0
                                var streamId: String? = null
                                var rawTitle: String? = null
                                var leagueName: String? = null
                                val teams = ArrayList<String>()
                                var matchStatus: Long = 0
                                var matchTime: Long = 0
                                var matchSportType = sportType
                                var leagueLogo: String? = null
                                val teamLogos = ArrayList<String>()

                                while (mParser.idx < mData.size) {
                                    val mKeyVal = mParser.readVarint().toInt()
                                    val mtag = mKeyVal shr 3
                                    val mwire = mKeyVal and 7

                                    if (mtag == 1 && mwire == 0) {
                                        matchId = mParser.readVarint()
                                    } else if (mtag == 2 && mwire == 0) {
                                        matchSportType = mParser.readVarint().toInt()
                                    } else if (mtag == 3 && mwire == 0) {
                                        matchTime = mParser.readVarint()
                                    } else if (mtag == 4 && mwire == 0) {
                                        matchStatus = mParser.readVarint()
                                    } else if (mtag == 10 && mwire == 2) { // league
                                        val lLen = mParser.readVarint().toInt()
                                        if (mParser.idx + lLen <= mData.size) {
                                            val lData = mData.copyOfRange(mParser.idx, mParser.idx + lLen)
                                            mParser.idx += lLen
                                            leagueName = parseNameFromTag10(lData)
                                            leagueLogo = parseLogoFromTag10(lData)
                                        }
                                    } else if (mtag == 30 && mwire == 2) { // contender
                                        val cLen = mParser.readVarint().toInt()
                                        if (mParser.idx + cLen <= mData.size) {
                                            val cData = mData.copyOfRange(mParser.idx, mParser.idx + cLen)
                                            mParser.idx += cLen
                                            
                                            val cParser = ProtoParser(cData)
                                            while (cParser.idx < cData.size) {
                                                val cKeyVal = cParser.readVarint().toInt()
                                                val ctag = cKeyVal shr 3
                                                val cwire = cKeyVal and 7
                                                if (ctag == 2 && cwire == 2) {
                                                    val vLen = cParser.readVarint().toInt()
                                                    if (cParser.idx + vLen <= cData.size) {
                                                        rawTitle = String(cData, cParser.idx, vLen, Charsets.UTF_8)
                                                        cParser.idx += vLen
                                                    }
                                                } else if (ctag == 10 && cwire == 2) { // team PBDataTeam
                                                    val tLen = cParser.readVarint().toInt()
                                                    if (cParser.idx + tLen <= cData.size) {
                                                        val tData = cData.copyOfRange(cParser.idx, cParser.idx + tLen)
                                                        cParser.idx += tLen
                                                        val tName = parseNameFromTag10(tData)
                                                        if (tName != null) {
                                                            teams.add(tName)
                                                        }
                                                        val tLogo = parseLogoFromTag10(tData)
                                                        if (tLogo != null) {
                                                            teamLogos.add(tLogo)
                                                        }
                                                    }
                                                } else {
                                                    cParser.skipField(cwire)
                                                }
                                            }
                                        }
                                    } else {
                                        mParser.skipField(mwire)
                                    }
                                }

                                val finalStreamId = streamId ?: matchId.toString()
                                val homeName = teams.getOrNull(0)
                                val awayName = teams.getOrNull(1)
                                val finalTitle = if (homeName != null && awayName != null) {
                                    "$homeName vs $awayName" + (if (leagueName != null) " (${cleanText(leagueName)})" else "")
                                } else {
                                    cleanText(rawTitle) ?: (cleanText(leagueName) ?: "RBTV+ Live Match")
                                }

                                 // Generate dynamic SVG poster for the match
                                 val sportName = sportNames[matchSportType] ?: "Olahraga"
                                 val timeSdf = java.text.SimpleDateFormat("dd MMM, HH:mm 'WIB'", java.util.Locale("id", "ID"))
                                 timeSdf.timeZone = java.util.TimeZone.getTimeZone("GMT+7")
                                 val timeStr = timeSdf.format(java.util.Date(matchTime))
                                 val finalPosterUrl = generateDynamicSvgPoster(
                                     sport = sportName,
                                     league = cleanText(leagueName),
                                     team1 = homeName,
                                     team2 = awayName,
                                     timeStr = timeStr,
                                     sportType = matchSportType
                                 )

                                matches.add(
                                    LiveMatchInfo(
                                        matchId = matchId,
                                        streamId = finalStreamId,
                                        matchTitle = finalTitle,
                                        homeName = homeName,
                                        awayName = awayName,
                                        leagueName = leagueName,
                                        sportType = matchSportType,
                                        posterUrl = finalPosterUrl,
                                        matchTime = matchTime,
                                        matchStatus = matchStatus
                                    )
                                )
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


    private suspend fun fetchAllLiveMatches(apiHost: String): List<LiveMatchInfo> {
        return coroutineScope {
            sportTypes.map { sportType ->
                async {
                    try {
                        val bytes = fetchLiveMatchesRaw(apiHost, sportType)
                        if (bytes != null) parseLiveMatches(bytes, sportType) else emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten().distinctBy { it.matchId }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val apiHost = getApiHost()
        val allMatches = fetchAllLiveMatches(apiHost)

        val now = System.currentTimeMillis() + serverTimeOffset
        val ongoingStatuses = setOf(
            1L, 100L, 101L, 102L, 103L, 104L, 105L,
            200L, 201L, 202L, 203L, 204L, 211L, 212L, 213L, 214L,
            300L, 400L, 600L, 700L, 800L, 900L, 1000L, 1100L, 1200L, 1300L, 1400L, 1500L, 1600L, 9000L
        )

        // Filter 100% live matches
        val liveMatches = allMatches.filter { m ->
            val isOngoingStatus = m.matchStatus in ongoingStatuses
            val isUpcomingOrOmitted = m.matchStatus == 0L || m.matchStatus == 9L
            val isTimeActive = now >= (m.matchTime - 15 * 60 * 1000) && now <= (m.matchTime + 5 * 60 * 60 * 1000)
            
            (isOngoingStatus || (isUpcomingOrOmitted && isTimeActive)) && m.matchStatus < 10000L
        }

        val homePages = ArrayList<HomePageList>()

        fun addCategory(title: String, matches: List<LiveMatchInfo>) {
            if (matches.isNotEmpty()) {
                val searchResps = matches.map { m ->
                    val encodedTitle = URLEncoder.encode(m.matchTitle, "UTF-8")
                    val detailUrl = "$mainUrl/id/match/detail.html?id=${m.matchId}&sportType=${m.sportType}&stream_id=${m.streamId}&title=$encodedTitle"
                    newLiveSearchResponse(
                        m.matchTitle,
                        detailUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = m.posterUrl
                    }
                }
                homePages.add(HomePageList(title, searchResps))
            }
        }

        val footballMatches = liveMatches.filter { it.sportType == 1 }
        val otherMatches = liveMatches.filter { it.sportType != 1 }

        addCategory("Sepak Bola", footballMatches)
        addCategory("Live Event", otherMatches)

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
        val allMatches = fetchAllLiveMatches(apiHost)

        val results = ArrayList<SearchResponse>()
        for (m in allMatches) {
            if (m.matchTitle.contains(query, ignoreCase = true) || 
                (m.homeName?.contains(query, ignoreCase = true) == true) || 
                (m.awayName?.contains(query, ignoreCase = true) == true) || 
                (m.leagueName?.contains(query, ignoreCase = true) == true)) {
                
                val encodedTitle = URLEncoder.encode(m.matchTitle, "UTF-8")
                val detailUrl = "$mainUrl/id/match/detail.html?id=${m.matchId}&sportType=${m.sportType}&stream_id=${m.streamId}&title=$encodedTitle"
                
                val searchResp = newLiveSearchResponse(
                    m.matchTitle,
                    detailUrl,
                    TvType.Live
                ) {
                    this.posterUrl = m.posterUrl
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
        
        val matchId = queryMap["id"] ?: return null
        val sportType = queryMap["sportType"] ?: "1"
        val streamId = queryMap["stream_id"] ?: matchId
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

        val loadData = "matchId=$matchId&sportType=$sportType&streamId=$streamId&title=$encodedTitle"

        return newLiveStreamLoadResponse(
            matchTitle,
            url,
            loadData
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
        if (!data.contains("matchId=") || !data.contains("streamId=")) return false
        
        try {
            val queryMap = data.split("&").associate {
                val parts = it.split("=")
                parts[0] to parts.getOrNull(1)
            }
            val matchId = queryMap["matchId"]?.toLongOrNull() ?: return false
            val sportType = queryMap["sportType"]?.toIntOrNull() ?: 1
            val streamId = queryMap["streamId"]?.toLongOrNull() ?: return false

            val apiHost = getApiHost()
            val token = getBsToken(apiHost, sportType) ?: return false

            // 1. Panggil detail match biner untuk mencari siteType
            val detailParamsJson = """{"matchId":$matchId,"sportType":$sportType,"language":34}"""
            val detailMd5 = md5(detailParamsJson)
            val detailSliceMd5 = detailMd5.substring(0, 6)
            val detailSfver = "sfver$detailSliceMd5$token"
            val detailUrl = "$apiHost/$detailSfver/api/match/detail?matchId=$matchId&sportType=$sportType&language=34"

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "Accept" to "application/json, text/plain, */*"
            )

            val detailResponse = app.get(detailUrl, headers = headers, timeout = 15)
            if (detailResponse.code != 200) return false
            val detailBytes = detailResponse.body.bytes()

            var siteType = 2001 // default fallback

            val dp = ProtoParser(detailBytes)
            var detailPayload: ByteArray? = null
            while (dp.idx < detailBytes.size) {
                val keyVal = dp.readVarint().toInt()
                val tag = keyVal shr 3
                val wire = keyVal and 7
                if (tag == 10 && wire == 2) {
                    val length = dp.readVarint().toInt()
                    if (dp.idx + length <= detailBytes.size) {
                        detailPayload = detailBytes.copyOfRange(dp.idx, dp.idx + length)
                        dp.idx += length
                    }
                    break
                } else {
                    dp.skipField(wire)
                }
            }

            var realStreamId = streamId
            if (detailPayload != null) {
                val dp2 = ProtoParser(detailPayload)
                var firstStreamId: Long = 0
                var firstSiteType = 2001
                var foundStream = false
                while (dp2.idx < detailPayload.size) {
                    val keyVal = dp2.readVarint().toInt()
                    val tag = keyVal shr 3
                    val wire = keyVal and 7
                    if (tag == 2 && wire == 2) {
                        val length = dp2.readVarint().toInt()
                        if (dp2.idx + length <= detailPayload.size) {
                            val streamBytes = detailPayload.copyOfRange(dp2.idx, dp2.idx + length)
                            dp2.idx += length

                            val sp = ProtoParser(streamBytes)
                            var sId: Long = 0
                            var sSiteType = 2001
                            while (sp.idx < streamBytes.size) {
                                val skey = sp.readVarint().toInt()
                                val stag = skey shr 3
                                val swire = skey and 7
                                if (stag == 1 && swire == 0) {
                                    sId = sp.readVarint()
                                } else if (stag == 9 && swire == 0) {
                                    sSiteType = sp.readVarint().toInt()
                                } else {
                                    sp.skipField(swire)
                                }
                            }
                            
                            if (firstStreamId == 0L && sId != 0L) {
                                firstStreamId = sId
                                firstSiteType = sSiteType
                            }
                            
                            if (sId == streamId) {
                                siteType = sSiteType
                                foundStream = true
                            }
                        }
                    } else {
                        dp2.skipField(wire)
                    }
                }
                
                if (!foundStream && firstStreamId != 0L) {
                    realStreamId = firstStreamId
                    siteType = firstSiteType
                }
            }

            // 2. Panggil API detail stream
            val streamParamsJson = """{"matchId":$matchId,"sportType":$sportType,"language":34,"streamId":$realStreamId,"siteType":$siteType,"usls":"rbp","digit":"sith","continent":"AS","country":"ID"}"""
            val streamMd5 = md5(streamParamsJson)
            val streamSliceMd5 = streamMd5.substring(0, 6)
            val streamSfver = "sfver$streamSliceMd5$token"
            val streamQuery = "streamId=$realStreamId&siteType=$siteType&matchId=$matchId&sportType=$sportType&language=34&usls=rbp&digit=sith&continent=AS&country=ID"
            val streamUrl = "$apiHost/$streamSfver/api/stream/detail?$streamQuery"

            val streamResponse = app.get(streamUrl, headers = headers, timeout = 15)
            if (streamResponse.code != 200) return false
            
            var rbSession = streamResponse.headers["rb-session"]
            val streamBytes = streamResponse.body.bytes()

            // Fallback jika rb-session null, panggil URL error sengaja untuk memicu respons header rb-session
            if (rbSession.isNullOrEmpty()) {
                val urlErr = "$apiHost/api/stream/detail?matchId=$matchId&sportType=$sportType&language=34"
                try {
                    val errResponse = app.get(urlErr, headers = headers, timeout = 5)
                    rbSession = errResponse.headers["rb-session"]
                } catch (e: Exception) {
                    // ignore
                }
            }


            // Parse detail stream biner
            val parser = ProtoParser(streamBytes)
            var pbResponseData: ByteArray? = null
            while (parser.idx < streamBytes.size) {
                val keyVal = parser.readVarint().toInt()
                val tag = keyVal shr 3
                val wire = keyVal and 7
                if (tag == 10 && wire == 2) {
                    val length = parser.readVarint().toInt()
                    if (parser.idx + length <= streamBytes.size) {
                        pbResponseData = streamBytes.copyOfRange(parser.idx, parser.idx + length)
                        parser.idx += length
                    }
                    break
                } else {
                    parser.skipField(wire)
                }
            }
            if (pbResponseData == null) return false

            val parser2 = ProtoParser(pbResponseData)
            var pbStreamData: ByteArray? = null
            while (parser2.idx < pbResponseData.size) {
                val keyVal = parser2.readVarint().toInt()
                val tag = keyVal shr 3
                val wire = keyVal and 7
                if (tag == 2 && wire == 2) {
                    val length = parser2.readVarint().toInt()
                    if (parser2.idx + length <= pbResponseData.size) {
                        pbStreamData = pbResponseData.copyOfRange(parser2.idx, parser2.idx + length)
                        parser2.idx += length
                    }
                    break
                } else {
                    parser2.skipField(wire)
                }
            }
            if (pbStreamData == null) return false

            val parser3 = ProtoParser(pbStreamData)
            var encryptedUrl: String? = null
            while (parser3.idx < pbStreamData.size) {
                val keyVal = parser3.readVarint().toInt()
                val tag = keyVal shr 3
                val wire = keyVal and 7
                if (tag == 4 && wire == 2) {
                    val length = parser3.readVarint().toInt()
                    if (parser3.idx + length <= pbStreamData.size) {
                        encryptedUrl = String(pbStreamData, parser3.idx, length, Charsets.UTF_8)
                        parser3.idx += length
                    }
                    break
                } else {
                    parser3.skipField(wire)
                }
            }
            if (encryptedUrl.isNullOrEmpty()) return false

            val decryptedRaw = rot47(encryptedUrl)
            val decryptedUrl = decryptedRaw.substring(8)

            var finalUrl = if (!rbSession.isNullOrEmpty()) {
                val encToken = encryptAesCtr(rbSession)
                val uriParsed = URI(decryptedUrl)
                val origin = "${uriParsed.scheme}://${uriParsed.host}"
                val pathname = uriParsed.path
                val search = uriParsed.query
                "$origin/token-${encToken}a$pathname" + (if (!search.isNullOrEmpty()) "?$search" else "")
            } else {
                decryptedUrl
            }

            if (finalUrl.startsWith("http://")) {
                finalUrl = finalUrl.replaceFirst("http://", "https://")
            }

            val isM3u8 = finalUrl.contains(".m3u8", ignoreCase = true)
            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            callback.invoke(
                newExtractorLink(
                    name = "RBTV+ Live Player",
                    source = "RBTV+ Stream",
                    url = finalUrl,
                    type = linkType
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to "https://lola30es.mpipzni2naturally32kistomach.ru/",
                        "Origin" to "https://lola30es.mpipzni2naturally32kistomach.ru",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
                    )
                }
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
