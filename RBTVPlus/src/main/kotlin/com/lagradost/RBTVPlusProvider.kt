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

data class StreamItem(
    val id: Long,
    val siteType: Int,
    val name: String?
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
    override val mainPage = listOf(
        MainPageData("Live Event", "Live Event")
    )

    private var serverTimeOffset: Long = 0L

    private val ongoingStatuses = setOf(
        1L, 100L, 101L, 102L, 103L, 104L, 105L,
        200L, 201L, 202L, 203L, 204L, 211L, 212L, 213L, 214L,
        300L, 400L, 600L, 700L, 800L, 900L, 1000L, 1100L, 1200L, 1300L, 1400L, 1500L, 1600L, 9000L
    )

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

    private fun wrapText(text: String, maxChars: Int): List<String> {
        val words = text.split(" ")
        val lines = ArrayList<String>()
        var currentLine = ""
        for (word in words) {
            if (currentLine.isEmpty()) {
                currentLine = word
            } else if (currentLine.length + 1 + word.length <= maxChars) {
                currentLine += " $word"
            } else {
                lines.add(currentLine)
                currentLine = word
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }
        return lines.take(2)
    }

    private fun generateDynamicJpegPoster(
        sport: String,
        league: String?,
        team1: String?,
        team2: String?,
        timeStr: String,
        sportType: Int,
        isLive: Boolean
    ): String {
        return try {
            val width = 400
            val height = 600
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

            // Tema warna dinamis berdasarkan sportType
            val (bgColor, cardColor, accentColor) = when (sportType) {
                1 -> Triple("#05140b", "#0d2618", "#38ef7d") // Football (Dark Green)
                2 -> Triple("#140b05", "#26170d", "#ff9900") // Basketball (Dark Orange)
                3, 12 -> Triple("#101405", "#22260d", "#d4fc79") // Tennis / Badminton (Dark Lime)
                14 -> Triple("#140505", "#260d0d", "#ff5858") // Fighting (Dark Crimson)
                7, 15 -> Triple("#050e14", "#0d1e26", "#3a7bd5") // Motorsport / Cycling (Dark Blue)
                else -> Triple("#0c0914", "#151024", "#00f2fe") // Default (Dark Purple / Neon Cyan)
            }

            // 1. Draw Background
            canvas.drawColor(android.graphics.Color.parseColor(bgColor))

            // 2. Draw Card Container
            paint.color = android.graphics.Color.parseColor(cardColor)
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRoundRect(25f, 40f, 375f, 560f, 24f, 24f, paint)

            paint.color = android.graphics.Color.parseColor(accentColor)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawRoundRect(25f, 40f, 375f, 560f, 24f, 24f, paint)

            // 3. Draw Header (Sport name)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = android.graphics.Color.parseColor("#a0a5c0")
            paint.textSize = 26f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            canvas.drawText(sport.uppercase(), 200f, 100f, paint)

            // 4. Draw League
            paint.color = android.graphics.Color.parseColor(accentColor)
            paint.textSize = 32f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            val cleanLeague = league ?: "Tournament"
            val truncatedLeague = if (cleanLeague.length > 20) cleanLeague.substring(0, 17) + "..." else cleanLeague
            canvas.drawText(truncatedLeague, 200f, 165f, paint)

            // 5. Draw Team 1 (Split if too long)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 38f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            val t1 = team1 ?: "Team A"
            val t1Lines = wrapText(t1, 11)
            var currentY = if (t1Lines.size > 1) 220f else 245f
            for (line in t1Lines) {
                canvas.drawText(line, 200f, currentY, paint)
                currentY += 45f
            }

            // 6. Draw VS Badge
            paint.color = android.graphics.Color.parseColor(accentColor)
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(200f, 315f, 36f, paint)
            
            paint.color = android.graphics.Color.BLACK
            paint.textSize = 26f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            canvas.drawText("VS", 200f, 324f, paint)

            // 7. Draw Team 2 (Split if too long)
            paint.color = android.graphics.Color.WHITE
            paint.style = android.graphics.Paint.Style.FILL
            paint.textSize = 38f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            val t2 = team2 ?: "Team B"
            val t2Lines = wrapText(t2, 11)
            currentY = 385f
            for (line in t2Lines) {
                canvas.drawText(line, 200f, currentY, paint)
                currentY += 45f
            }

            // 8. Draw Status Badge
            val badgeColor = if (isLive) "#ff5858" else "#3a7bd5"
            val badgeText = if (isLive) "LIVE NOW" else "UPCOMING"
            paint.color = android.graphics.Color.parseColor(badgeColor)
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRoundRect(100f, 475f, 300f, 520f, 22f, 22f, paint)

            paint.color = android.graphics.Color.WHITE
            paint.textSize = 24f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            canvas.drawText(badgeText, 200f, 506f, paint)

            // 9. Draw Time Subtext
            paint.color = android.graphics.Color.parseColor("#a0a5c0")
            paint.textSize = 24f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            canvas.drawText(timeStr, 200f, 555f, paint)

            // Compress & Encode to Base64 (JPEG format is universally supported by Glide/Coil)
            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            val bytes = baos.toByteArray()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
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
                                 
                                 val now = System.currentTimeMillis() + serverTimeOffset
                                 val isLive = (matchStatus in ongoingStatuses) || (now >= matchTime && now <= matchTime + 150 * 60 * 1000)
                                 
                                 val finalPosterUrl = generateDynamicJpegPoster(
                                     sport = sportName,
                                     league = leagueName,
                                     team1 = homeName,
                                     team2 = awayName,
                                     timeStr = timeStr,
                                     sportType = matchSportType,
                                     isLive = isLive
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
        // Filter 100% live matches
        val liveMatches = allMatches.filter { m ->
            val isOngoingStatus = m.matchStatus in ongoingStatuses
            val isUpcomingOrOmitted = m.matchStatus == 0L || m.matchStatus == 9L
            val isTimeActive = now >= (m.matchTime - 15 * 60 * 1000) && now <= (m.matchTime + 150 * 60 * 1000)
            
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

        // Sort matches: Sepak Bola (sportType == 1) first, all other sports after
        val sortedLiveMatches = liveMatches.sortedWith(compareBy { if (it.sportType == 1) 0 else 1 })
        addCategory("Live Event", sortedLiveMatches)

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

            val streams = ArrayList<StreamItem>()
            if (detailPayload != null) {
                val dp2 = ProtoParser(detailPayload)
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
                            var sName: String? = null
                            while (sp.idx < streamBytes.size) {
                                val skey = sp.readVarint().toInt()
                                val stag = skey shr 3
                                val swire = skey and 7
                                if (stag == 1 && swire == 0) {
                                    sId = sp.readVarint()
                                } else if (stag == 9 && swire == 0) {
                                    sSiteType = sp.readVarint().toInt()
                                } else if (stag == 3 && swire == 2) {
                                    val sLen = sp.readVarint().toInt()
                                    if (sp.idx + sLen <= streamBytes.size) {
                                        sName = String(sp.data, sp.idx, sLen, Charsets.UTF_8)
                                        sp.idx += sLen
                                    }
                                } else {
                                    sp.skipField(swire)
                                }
                            }
                            if (sId != 0L) {
                                streams.add(StreamItem(sId, sSiteType, sName))
                            }
                        }
                    } else {
                        dp2.skipField(wire)
                    }
                }
            }

            if (streams.isEmpty()) {
                streams.add(StreamItem(streamId, 2001, null))
            }

            coroutineScope {
                streams.mapIndexed { index, item ->
                    async {
                        try {
                            val streamParamsJson = """{"matchId":$matchId,"sportType":$sportType,"language":34,"streamId":${item.id},"siteType":${item.siteType},"usls":"rbp","digit":"sith","continent":"AS","country":"ID"}"""
                            val streamMd5 = md5(streamParamsJson)
                            val streamSliceMd5 = streamMd5.substring(0, 6)
                            val streamSfver = "sfver$streamSliceMd5$token"
                            val streamQuery = "streamId=${item.id}&siteType=${item.siteType}&matchId=$matchId&sportType=$sportType&language=34&usls=rbp&digit=sith&continent=AS&country=ID"
                            val streamUrl = "$apiHost/$streamSfver/api/stream/detail?$streamQuery"

                            val streamResponse = app.get(streamUrl, headers = headers, timeout = 15)
                            if (streamResponse.code == 200) {
                                var rbSession = streamResponse.headers["rb-session"]
                                val streamDetailBytes = streamResponse.body.bytes()

                                // Fallback jika rb-session null
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
                                val parser = ProtoParser(streamDetailBytes)
                                var pbResponseData: ByteArray? = null
                                while (parser.idx < streamDetailBytes.size) {
                                    val keyVal = parser.readVarint().toInt()
                                    val tag = keyVal shr 3
                                    val wire = keyVal and 7
                                    if (tag == 10 && wire == 2) {
                                        val length = parser.readVarint().toInt()
                                        if (parser.idx + length <= streamDetailBytes.size) {
                                            pbResponseData = streamDetailBytes.copyOfRange(parser.idx, parser.idx + length)
                                            parser.idx += length
                                        }
                                        break
                                    } else {
                                        parser.skipField(wire)
                                    }
                                }

                                if (pbResponseData != null) {
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

                                    if (pbStreamData != null) {
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

                                        if (!encryptedUrl.isNullOrEmpty()) {
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

                                            val sourceName = if (!item.name.isNullOrEmpty()) {
                                                "Source ${index + 1} (${item.name})"
                                            } else {
                                                "Source ${index + 1}"
                                            }

                                            val qualityVal = when {
                                                item.name?.contains("1080", ignoreCase = true) == true -> Qualities.P1080.value
                                                item.name?.contains("720", ignoreCase = true) == true -> Qualities.P720.value
                                                item.name?.contains("480", ignoreCase = true) == true -> Qualities.P480.value
                                                item.name?.contains("360", ignoreCase = true) == true -> Qualities.P360.value
                                                item.name?.contains("HD", ignoreCase = true) == true -> Qualities.P720.value
                                                else -> Qualities.Unknown.value
                                            }

                                            callback.invoke(
                                                newExtractorLink(
                                                    name = sourceName,
                                                    source = "RBTV+",
                                                    url = finalUrl,
                                                    type = linkType
                                                ) {
                                                    this.quality = qualityVal
                                                    this.headers = mapOf(
                                                        "Referer" to "https://lola30es.mpipzni2naturally32kistomach.ru/",
                                                        "Origin" to "https://lola30es.mpipzni2naturally32kistomach.ru",
                                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }.awaitAll()
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
