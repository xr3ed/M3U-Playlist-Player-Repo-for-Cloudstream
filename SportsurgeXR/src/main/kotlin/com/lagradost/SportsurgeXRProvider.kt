package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.SportsurgeXR.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.net.URLEncoder

// Data class untuk detail event di web
data class WebMatchInfo(
    val path: String,
    val status: String,
    val team1: String,
    val logo1: String,
    val team2: String,
    val logo2: String
)

// Data class untuk list stream yang di-serialize ke JSON loadData
data class SportsurgeStreamInfo(
    val channel: String,
    val language: String,
    val url: String
)

class SportsurgeXRProvider : MainAPI() {
    companion object {
        val posterCache = java.util.concurrent.ConcurrentHashMap<String, String>()
        val logoCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
        val cleanClient = okhttp3.OkHttpClient()
        
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://s1.sportsurge.pk"
    override var name = "SportsurgeXR"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "id"
    override val hasMainPage = true
    
    override val mainPage = listOf(
        MainPageData("Sepak Bola", "/football"),
        MainPageData("NFL", "/nfl"),
        MainPageData("Basket", "/nba"),
        MainPageData("Tinju", "/boxing"),
        MainPageData("MMA", "/ufc"),
        MainPageData("Bisbol", "/baseball"),
        MainPageData("Hoki Es", "/nhl"),
        MainPageData("Formula 1", "/f1"),
        MainPageData("Rugby", "/rugby")
    )

    private fun unescapeNextF(text: String): String {
        return text
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
    }

    private fun parseMatches(html: String): List<WebMatchInfo> {
        val pushes = Regex("""self\.__next_f\.push\(\[\d+,\s*"(.*?)"\]\)""")
            .findAll(html)
            .map { it.groupValues[1] }
            .joinToString("")
        
        val combined = unescapeNextF(pushes)
        val blocks = combined.split("{\"href\":\"/events/")
        val matches = ArrayList<WebMatchInfo>()
        
        for (i in 1 until blocks.size) {
            val b = blocks[i]
            val pathMatch = Regex("""^([a-zA-Z0-9-]+)""").find(b) ?: continue
            val path = "/events/" + pathMatch.groupValues[1]
            
            val statusMatch = Regex("""\"className\":\"text-xs\",\"children\":\"([^\"]+)\"""").find(b)
            val status = statusMatch?.groupValues?.get(1) ?: "Upcoming"
            
            val logos = ArrayList<String>()
            val teams = ArrayList<String>()
            
            val imgMatches = Regex("""\"src\":\"(https://v1\.1cdnforall\.online/storage/[^\"]+)\"[^}]+p\s*\}\s*,\s*\"\s*([^\"]+?)\s*\"\]""").findAll(b)
            for (im in imgMatches) {
                logos.add(im.groupValues[1])
                teams.add(im.groupValues[2])
            }
            
            if (logos.size < 2) {
                logos.clear()
                teams.clear()
                val cleanLogos = Regex("""\"src\":\"(https://v1\.1cdnforall\.online/storage/[^\"]+)\"""").findAll(b).map { it.groupValues[1] }.toList()
                val cleanAlts = Regex("""\"alt\":\"([^\"]+?)\s*Live\s*HD\"""").findAll(b).map { it.groupValues[1] }.toList()
                for (j in 0 until minOf(cleanLogos.size, cleanAlts.size)) {
                    logos.add(cleanLogos[j])
                    teams.add(cleanAlts[j])
                }
            }
            
            val t1Name = teams.getOrNull(0) ?: "Team A"
            val t1Logo = logos.getOrNull(0) ?: ""
            val t2Name = teams.getOrNull(1) ?: "Team B"
            val t2Logo = logos.getOrNull(1) ?: ""
            
            matches.add(WebMatchInfo(path, status, t1Name, t1Logo, t2Name, t2Logo))
        }
        return matches
    }

    private fun parseStreams(html: String): List<SportsurgeStreamInfo> {
        val pushes = Regex("""self\.__next_f\.push\(\[\d+,\s*"(.*?)"\]\)""")
            .findAll(html)
            .map { it.groupValues[1] }
            .joinToString("")
            
        val combined = unescapeNextF(pushes)
        val rows = combined.split(Regex(""""tr","\d+""""))
        val streams = ArrayList<SportsurgeStreamInfo>()
        
        for (i in 1 until rows.size) {
            val r = rows[i]
            val children = Regex(""""children":"([^"]+)"""").findAll(r).map { it.groupValues[1] }.toList()
            
            var channel = "Unknown Channel"
            for (c in children) {
                if (c !in listOf("Yes", "No", "English", "Watch", "Live Now!", "Upcoming", "Live HD") && !c.all { it.isDigit() }) {
                    channel = c
                    break
                }
            }
            
            var language = "English"
            for (c in children) {
                if (c in listOf("English", "Spanish", "French", "German", "Portuguese", "Italian", "Arabic", "Russian")) {
                    language = c
                    break
                }
            }
            
            val hrefMatch = Regex(""""href":"(https?://[^"]+)"""").find(r)
            val href = hrefMatch?.groupValues?.get(1)
            
            if (href != null) {
                streams.add(SportsurgeStreamInfo(channel, language, href))
            }
        }
        return streams
    }

    private fun downloadBitmap(url: String): android.graphics.Bitmap? {
        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .build()
            cleanClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null) {
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }


    private fun wrapText(text: String, maxChars: Int): List<String> {
        val words = text.split(" ")
        val lines = ArrayList<String>()
        var currentLine = ""
        for (word in words) {
            if (word.length > maxChars) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    currentLine = ""
                }
                var tempWord = word
                while (tempWord.length > maxChars) {
                    lines.add(tempWord.substring(0, maxChars))
                    tempWord = tempWord.substring(maxChars)
                }
                currentLine = tempWord
            } else {
                if (currentLine.isEmpty()) {
                    currentLine = word
                } else if (currentLine.length + 1 + word.length <= maxChars) {
                    currentLine += " $word"
                } else {
                    lines.add(currentLine)
                    currentLine = word
                }
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
        isLive: Boolean,
        logoUrl1: String?,
        logoUrl2: String?,
        preloadedLogos: Map<String, android.graphics.Bitmap?> = emptyMap()
    ): String {
        val cacheKey = "${sport}_${league ?: ""}_${team1 ?: ""}_${team2 ?: ""}_${timeStr}_${sportType}_${isLive}_${logoUrl1 ?: ""}_${logoUrl2 ?: ""}"
        val cached = posterCache[cacheKey]
        if (cached != null) return cached
        return try {
            val width = 400
            val height = 600
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

            val (baseColor, accentColor) = when (sport.lowercase()) {
                "sepak bola", "football", "soccer" -> Pair("#041c0e", "#00ff87")
                "basket", "nba", "basketball" -> Pair("#241105", "#ff5e00")
                "tenis", "bulutangkis", "tennis", "badminton" -> Pair("#1a2007", "#ccff00")
                "tinju", "mma", "boxing", "ufc", "fighting" -> Pair("#260d0d", "#ff3333")
                "motorsport", "formula 1", "f1" -> Pair("#0d1e26", "#00d2ff")
                else -> Pair("#16082c", "#00f2fe")
            }

            val bgGradient = android.graphics.LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                android.graphics.Color.parseColor("#020202"),
                android.graphics.Color.parseColor(baseColor),
                android.graphics.Shader.TileMode.CLAMP
            )
            paint.shader = bgGradient
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null

            paint.color = android.graphics.Color.parseColor(accentColor)
            paint.alpha = 10
            val diagPath = android.graphics.Path().apply {
                moveTo(0f, height * 0.4f)
                lineTo(width.toFloat(), height * 0.2f)
                lineTo(width.toFloat(), height.toFloat())
                lineTo(0f, height.toFloat())
                close()
            }
            canvas.drawPath(diagPath, paint)
            paint.alpha = 255

            paint.color = android.graphics.Color.parseColor("#E6" + baseColor.replace("#", ""))
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRoundRect(25f, 40f, 375f, 560f, 24f, 24f, paint)

            val borderGradient = android.graphics.LinearGradient(
                25f, 40f, 375f, 560f,
                android.graphics.Color.parseColor(accentColor),
                android.graphics.Color.parseColor("#44FFFFFF"),
                android.graphics.Shader.TileMode.CLAMP
            )
            paint.shader = borderGradient
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawRoundRect(25f, 40f, 375f, 560f, 24f, 24f, paint)
            paint.shader = null

            paint.color = android.graphics.Color.parseColor(accentColor)
            paint.alpha = 100
            paint.strokeWidth = 3f
            canvas.drawLine(40f, 55f, 60f, 55f, paint)
            canvas.drawLine(40f, 55f, 40f, 75f, paint)
            canvas.drawLine(360f, 55f, 340f, 55f, paint)
            canvas.drawLine(360f, 55f, 360f, 75f, paint)
            canvas.drawLine(40f, 545f, 60f, 545f, paint)
            canvas.drawLine(40f, 545f, 40f, 525f, paint)
            canvas.drawLine(360f, 545f, 340f, 545f, paint)
            canvas.drawLine(360f, 545f, 360f, 525f, paint)
            paint.alpha = 255

            paint.style = android.graphics.Paint.Style.FILL
            paint.color = android.graphics.Color.parseColor("#a0a5c0")
            paint.textSize = 24f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            canvas.drawText(sport.uppercase(), 200f, 95f, paint)

            paint.color = android.graphics.Color.parseColor(accentColor)
            paint.textSize = 30f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            val cleanLeague = league ?: "Live Event"
            val truncatedLeague = if (cleanLeague.length > 20) cleanLeague.substring(0, 17) + "..." else cleanLeague
            canvas.drawText(truncatedLeague, 200f, 150f, paint)

            paint.color = android.graphics.Color.parseColor("#26FFFFFF")
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRoundRect(45f, 185f, 355f, 285f, 16f, 16f, paint)
            paint.color = android.graphics.Color.parseColor("#40FFFFFF")
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            canvas.drawRoundRect(45f, 185f, 355f, 285f, 16f, 16f, paint)

            var team1TextLeft = 65f
            if (!logoUrl1.isNullOrEmpty()) {
                val logoBmp = preloadedLogos[logoUrl1] ?: logoCache[logoUrl1]
                if (logoBmp != null) {
                    logoCache[logoUrl1] = logoBmp
                    val destRect = android.graphics.RectF(65f, 200f, 135f, 270f)
                    val path = android.graphics.Path().apply {
                        addRoundRect(destRect, 12f, 12f, android.graphics.Path.Direction.CW)
                    }
                    canvas.save()
                    canvas.clipPath(path)
                    canvas.drawBitmap(logoBmp, null, destRect, paint)
                    canvas.restore()
                    team1TextLeft = 155f
                } else {
                    val destRect = android.graphics.RectF(65f, 200f, 135f, 270f)
                    paint.color = android.graphics.Color.parseColor("#33FFFFFF")
                    paint.style = android.graphics.Paint.Style.FILL
                    canvas.drawRoundRect(destRect, 12f, 12f, paint)
                    
                    paint.color = android.graphics.Color.WHITE
                    paint.textSize = 32f
                    paint.textAlign = android.graphics.Paint.Align.CENTER
                    paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                    val letter = team1?.firstOrNull()?.toString()?.uppercase() ?: "A"
                    canvas.drawText(letter, 100f, 245f, paint)
                    team1TextLeft = 155f
                }
            } else {
                team1TextLeft = 200f
            }

            paint.color = android.graphics.Color.WHITE
            paint.style = android.graphics.Paint.Style.FILL
            paint.textSize = 36f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            val t1 = team1 ?: "Team A"
            val t1Lines = wrapText(t1, if (team1TextLeft == 200f) 14 else 8)
            if (team1TextLeft == 200f) {
                paint.textAlign = android.graphics.Paint.Align.CENTER
                var currentY = if (t1Lines.size > 1) 225f else 245f
                for (line in t1Lines) {
                    canvas.drawText(line, 200f, currentY, paint)
                    currentY += 42f
                }
            } else {
                paint.textAlign = android.graphics.Paint.Align.LEFT
                var currentY = if (t1Lines.size > 1) 225f else 245f
                for (line in t1Lines) {
                    canvas.drawText(line, team1TextLeft, currentY, paint)
                    currentY += 42f
                }
            }

            paint.color = android.graphics.Color.parseColor("#33FFFFFF")
            paint.strokeWidth = 2f
            canvas.drawLine(45f, 315f, 150f, 315f, paint)
            canvas.drawLine(250f, 315f, 355f, 315f, paint)

            paint.color = android.graphics.Color.parseColor(accentColor)
            paint.textSize = 34f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD_ITALIC)
            canvas.drawText("VS", 200f, 326f, paint)

            paint.color = android.graphics.Color.parseColor("#26FFFFFF")
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRoundRect(45f, 345f, 355f, 445f, 16f, 16f, paint)
            paint.color = android.graphics.Color.parseColor("#40FFFFFF")
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            canvas.drawRoundRect(45f, 345f, 355f, 445f, 16f, 16f, paint)

            var team2TextLeft = 65f
            if (!logoUrl2.isNullOrEmpty()) {
                val logoBmp = preloadedLogos[logoUrl2] ?: logoCache[logoUrl2]
                if (logoBmp != null) {
                    logoCache[logoUrl2] = logoBmp
                    val destRect = android.graphics.RectF(65f, 360f, 135f, 430f)
                    val path = android.graphics.Path().apply {
                        addRoundRect(destRect, 12f, 12f, android.graphics.Path.Direction.CW)
                    }
                    canvas.save()
                    canvas.clipPath(path)
                    canvas.drawBitmap(logoBmp, null, destRect, paint)
                    canvas.restore()
                    team2TextLeft = 155f
                } else {
                    val destRect = android.graphics.RectF(65f, 360f, 135f, 430f)
                    paint.color = android.graphics.Color.parseColor("#33FFFFFF")
                    paint.style = android.graphics.Paint.Style.FILL
                    canvas.drawRoundRect(destRect, 12f, 12f, paint)
                    
                    paint.color = android.graphics.Color.WHITE
                    paint.textSize = 32f
                    paint.textAlign = android.graphics.Paint.Align.CENTER
                    paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                    val letter = team2?.firstOrNull()?.toString()?.uppercase() ?: "B"
                    canvas.drawText(letter, 100f, 405f, paint)
                    team2TextLeft = 155f
                }
            } else {
                team2TextLeft = 200f
            }

            paint.color = android.graphics.Color.WHITE
            paint.style = android.graphics.Paint.Style.FILL
            paint.textSize = 36f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            val t2 = team2 ?: "Team B"
            val t2Lines = wrapText(t2, if (team2TextLeft == 200f) 14 else 8)
            if (team2TextLeft == 200f) {
                paint.textAlign = android.graphics.Paint.Align.CENTER
                var currentY = if (t2Lines.size > 1) 385f else 405f
                for (line in t2Lines) {
                    canvas.drawText(line, 200f, currentY, paint)
                    currentY += 42f
                }
            } else {
                paint.textAlign = android.graphics.Paint.Align.LEFT
                var currentY = if (t2Lines.size > 1) 385f else 405f
                for (line in t2Lines) {
                    canvas.drawText(line, team2TextLeft, currentY, paint)
                    currentY += 42f
                }
            }

            val isLiveReal = isLive || timeStr.contains("live", ignoreCase = true)
            val badgeColor = if (isLiveReal) "#ff3333" else "#1a73e8"
            val badgeText = if (isLiveReal) "LIVE NOW" else "UPCOMING"
            paint.color = android.graphics.Color.parseColor(badgeColor)
            paint.style = android.graphics.Paint.Style.FILL
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawRoundRect(100f, 475f, 300f, 520f, 22f, 22f, paint)

            paint.color = android.graphics.Color.WHITE
            paint.textSize = 24f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            if (isLiveReal) {
                paint.color = android.graphics.Color.RED
                canvas.drawCircle(145f, 497.5f, 7f, paint)
                paint.color = android.graphics.Color.WHITE
                canvas.drawText("LIVE NOW", 215f, 506f, paint)
            } else {
                canvas.drawText(badgeText, 200f, 506f, paint)
            }

            paint.color = android.graphics.Color.parseColor("#a0a5c0")
            paint.textSize = 24f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            canvas.drawText(timeStr, 200f, 550f, paint)

            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            val bytes = baos.toByteArray()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val finalUrl = "data:image/jpeg;base64,$base64"
            posterCache[cacheKey] = finalUrl
            finalUrl
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val categoryPath = request.data
        val categoryName = request.name
        
        val url = "$mainUrl$categoryPath"
        val response = app.get(url, timeout = 15)
        if (response.code != 200) return null
        
        val matches = parseMatches(response.text)
        
        // Unduh semua logo unik secara paralel terlebih dahulu di Dispatchers.IO
        val logoUrls = matches.flatMap { listOf(it.logo1, it.logo2) }.filter { it.isNotEmpty() }.distinct()
        val logoBitmaps = coroutineScope {
            logoUrls.map { url ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    url to downloadBitmap(url)
                }
            }.awaitAll().toMap()
        }

        val searchResps = matches.map { m ->
            val isLive = m.status.contains("live", ignoreCase = true)
            val poster = generateDynamicJpegPoster(
                sport = categoryName,
                league = categoryName,
                team1 = m.team1,
                team2 = m.team2,
                timeStr = m.status,
                sportType = 1,
                isLive = isLive,
                logoUrl1 = m.logo1,
                logoUrl2 = m.logo2,
                preloadedLogos = logoBitmaps
            )
            
            val detailUrl = "$mainUrl${m.path}"
            newLiveSearchResponse(
                "${m.team1} vs ${m.team2}",
                detailUrl,
                TvType.Live
            ) {
                this.posterUrl = poster
            }
        }
        
        if (posterCache.size > 100) {
            posterCache.clear()
        }

        return newHomePageResponse(
            listOf(HomePageList(categoryName, searchResps)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return coroutineScope {
            val allMatches = mainPage.map { cat ->
                async {
                    try {
                        val url = "$mainUrl${cat.data}"
                        val response = app.get(url, timeout = 8)
                        if (response.code == 200) {
                            parseMatches(response.text)
                        } else emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten().distinctBy { it.path }
            
            val filteredMatches = allMatches.filter { m ->
                m.team1.contains(query, ignoreCase = true) || m.team2.contains(query, ignoreCase = true)
            }
            
            // Unduh logo secara paralel untuk hasil pencarian
            val logoUrls = filteredMatches.flatMap { listOf(it.logo1, it.logo2) }.filter { it.isNotEmpty() }.distinct()
            val logoBitmaps = logoUrls.map { url ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    url to downloadBitmap(url)
                }
            }.awaitAll().toMap()

            filteredMatches.map { m ->
                val isLive = m.status.contains("live", ignoreCase = true)
                val poster = generateDynamicJpegPoster(
                    sport = "Olahraga",
                    league = "Live Match",
                    team1 = m.team1,
                    team2 = m.team2,
                    timeStr = m.status,
                    sportType = 1,
                    isLive = isLive,
                    logoUrl1 = m.logo1,
                    logoUrl2 = m.logo2,
                    preloadedLogos = logoBitmaps
                )
                
                val detailUrl = "$mainUrl${m.path}"
                newLiveSearchResponse(
                    "${m.team1} vs ${m.team2}",
                    detailUrl,
                    TvType.Live
                ) {
                    this.posterUrl = poster
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, timeout = 15)
        if (response.code != 200) return null
        
        val rawStreams = parseStreams(response.text)
        val streams = rawStreams.sortedWith(compareBy<SportsurgeStreamInfo> {
            val name = it.channel.lowercase()
            when {
                name.contains("fhd") || name.contains("1080p") || name.contains("1080") -> 0
                name.contains("hd") || name.contains("720p") || name.contains("720") -> 1
                else -> 2
            }
        }.thenBy { it.channel })
        
        // Dapatkan nama tim dari URL event path
        val eventPath = url.substringAfter("/events/", "")
        val matchTitle = if (eventPath.isNotEmpty()) {
            eventPath.split("-vs-").joinToString(" vs ") { word ->
                word.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            }
        } else {
            "SportsurgeXR Live Match"
        }
        
        val loadData = mapper.writeValueAsString(streams)
        
        return newLiveStreamLoadResponse(
            matchTitle,
            url,
            loadData
        ) {
            this.posterUrl = null
            this.plot = "Tonton siaran langsung $matchTitle secara gratis di SportsurgeXR"
        }
    }

    private fun base64Decode(text: String): String {
        return try {
            String(android.util.Base64.decode(text, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            String(java.util.Base64.getDecoder().decode(text))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streams = try {
            mapper.readValue(data, Array<SportsurgeStreamInfo>::class.java).toList()
        } catch (e: Exception) {
            return false
        }
        
        if (streams.isEmpty()) return false
        
        coroutineScope {
            streams.mapIndexed { index, item ->
                async {
                    try {
                        val name = "Source ${index + 1} (${item.channel})"
                        val urlStr = item.url
                        
                        if (urlStr.contains("totwatch.php")) {
                            // Ekstrak fid / value
                            val fid = urlStr.substringAfter("value=", "")
                            if (fid.isNotEmpty()) {
                                val iframeUrl = "https://executeandship.com/premiumcr.php?player=desktop&live=$fid"
                                
                                // Gunakan OkHttpClient bersih untuk melewati filter User-Agent
                                val request = okhttp3.Request.Builder()
                                    .url(iframeUrl)
                                    .header("Referer", "https://hitcast.st/")
                                    .header("User-Agent", DESKTOP_UA)
                                    .build()
                                    
                                cleanClient.newCall(request).execute().use { response ->
                                    if (response.isSuccessful) {
                                        val html = response.body?.string() ?: ""
                                        val charArrayRegex = Regex("""\["h","t","t","p","s",.*?\]""")
                                        val match = charArrayRegex.find(html)?.value
                                        if (match != null) {
                                            val cleanUrl = match.replace("[", "").replace("]", "").replace("\"", "").split(",").joinToString("")
                                            callback.invoke(
                                                ExtractorLink(
                                                    source = this@SportsurgeXRProvider.name,
                                                    name = name,
                                                    url = cleanUrl,
                                                    referer = "https://executeandship.com/",
                                                    quality = Qualities.P720.value,
                                                    type = ExtractorLinkType.M3U8,
                                                    headers = mapOf(
                                                        "Referer" to "https://executeandship.com/",
                                                        "Origin" to "https://executeandship.com",
                                                        "User-Agent" to DESKTOP_UA
                                                    )
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (urlStr.contains("totview.php")) {
                            val targetUrl = urlStr.substringAfter("src=", "")
                            if (targetUrl.isNotEmpty()) {
                                if (targetUrl.contains("daddylive") || targetUrl.contains("daddy")) {
                                    val id = targetUrl.substringAfter("stream-", "").substringBefore(".php", "")
                                    if (id.isNotEmpty()) {
                                        val iframeUrl = "https://hamis.romponalis.st/premiumtv/daddy2.php?id=$id"
                                        val req = okhttp3.Request.Builder()
                                            .url(iframeUrl)
                                            .header("Referer", "https://daddylive1.cx/")
                                            .header("User-Agent", DESKTOP_UA)
                                            .build()
                                        cleanClient.newCall(req).execute().use { res ->
                                            if (res.isSuccessful) {
                                                val html = res.body?.string() ?: ""
                                                val atobMatch = Regex("""atob\(['"]([^'"]+)['"]\)""").find(html)
                                                val base64Str = atobMatch?.groupValues?.get(1)
                                                if (base64Str != null) {
                                                    val decodedUrl = base64Decode(base64Str)
                                                    callback.invoke(
                                                        ExtractorLink(
                                                            source = this@SportsurgeXRProvider.name,
                                                            name = name,
                                                            url = decodedUrl,
                                                            referer = "https://hamis.romponalis.st/",
                                                            quality = Qualities.P720.value,
                                                            type = ExtractorLinkType.M3U8,
                                                            headers = mapOf(
                                                                "Referer" to "https://hamis.romponalis.st/",
                                                                "User-Agent" to DESKTOP_UA
                                                            )
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else if (targetUrl.contains("vivtops") || targetUrl.contains("universaltokenforall")) {
                                    val channelNum = targetUrl.substringAfter("channel", "")
                                    if (channelNum.isNotEmpty()) {
                                        val iframeUrl = "https://universaltokenforall.st/player6/channel$channelNum"
                                        val req = okhttp3.Request.Builder()
                                            .url(iframeUrl)
                                            .header("Referer", "https://vivtops.st/")
                                            .header("User-Agent", DESKTOP_UA)
                                            .build()
                                        cleanClient.newCall(req).execute().use { res ->
                                            if (res.isSuccessful) {
                                                val html = res.body?.string() ?: ""
                                                val streamUrlMatch = Regex("""streamUrl\s*=\s*"([^"]+)"""").find(html)
                                                val rawStreamUrl = streamUrlMatch?.groupValues?.get(1)
                                                if (rawStreamUrl != null) {
                                                    val cleanUrl = rawStreamUrl.replace("\\/", "/")
                                                    callback.invoke(
                                                        ExtractorLink(
                                                            source = this@SportsurgeXRProvider.name,
                                                            name = name,
                                                            url = cleanUrl,
                                                            referer = "https://universaltokenforall.st/",
                                                            quality = Qualities.P720.value,
                                                            type = ExtractorLinkType.M3U8,
                                                            headers = mapOf(
                                                                "Referer" to "https://universaltokenforall.st/",
                                                                "User-Agent" to DESKTOP_UA
                                                            )
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else if (targetUrl.contains("vertex") || targetUrl.contains("gerfred")) {
                                    val id = targetUrl.substringAfter("id=", "")
                                    if (id.isNotEmpty()) {
                                        val apiPlayerUrl = "https://s3.vertex.st/api/player.php?id=$id"
                                        val req = okhttp3.Request.Builder()
                                            .url(apiPlayerUrl)
                                            .header("Referer", targetUrl)
                                            .header("User-Agent", DESKTOP_UA)
                                            .build()
                                        cleanClient.newCall(req).execute().use { res ->
                                            if (res.isSuccessful) {
                                                val apiJson = res.body?.string() ?: ""
                                                val embedUrlMatch = Regex(""""url":"([^"]+)"""").find(apiJson)
                                                val embedUrl = embedUrlMatch?.groupValues?.get(1)?.replace("\\/", "/")
                                                if (embedUrl != null) {
                                                    val code = embedUrl.substringAfter("code=", "")
                                                    if (code.isNotEmpty()) {
                                                        val configUrl = "https://gerfred.com/embed.php?code=$code&ppcfg=1"
                                                        val reqConfig = okhttp3.Request.Builder()
                                                            .url(configUrl)
                                                            .header("Referer", "https://s3.vertex.st/")
                                                            .header("User-Agent", DESKTOP_UA)
                                                            .build()
                                                        cleanClient.newCall(reqConfig).execute().use { resConfig ->
                                                            if (resConfig.isSuccessful) {
                                                                val configJson = resConfig.body?.string() ?: ""
                                                                val srcUrlMatch = Regex(""""src":"([^"]+)"""").find(configJson)
                                                                val srcUrl = srcUrlMatch?.groupValues?.get(1)?.replace("\\/", "/")
                                                                if (srcUrl != null) {
                                                                    callback.invoke(
                                                                        ExtractorLink(
                                                                            source = this@SportsurgeXRProvider.name,
                                                                            name = name,
                                                                            url = srcUrl,
                                                                            referer = "https://gerfred.com/",
                                                                            quality = Qualities.P720.value,
                                                                            type = ExtractorLinkType.M3U8,
                                                                            headers = mapOf(
                                                                                "Referer" to "https://gerfred.com/",
                                                                                "User-Agent" to DESKTOP_UA
                                                                            )
                                                                        )
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    val request = okhttp3.Request.Builder()
                                        .url(targetUrl)
                                        .header("Referer", "https://hitcast.st/")
                                        .header("User-Agent", DESKTOP_UA)
                                        .build()
                                        
                                    cleanClient.newCall(request).execute().use { response ->
                                        if (response.isSuccessful) {
                                            val html = response.body?.string() ?: ""
                                            val m3u8Match = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)
                                            if (m3u8Match != null) {
                                                val m3u8Url = m3u8Match.groupValues[1]
                                                callback.invoke(
                                                    ExtractorLink(
                                                        source = this@SportsurgeXRProvider.name,
                                                        name = name,
                                                        url = m3u8Url,
                                                        referer = targetUrl,
                                                        quality = Qualities.P720.value,
                                                        type = ExtractorLinkType.M3U8,
                                                        headers = mapOf(
                                                            "Referer" to targetUrl,
                                                            "User-Agent" to DESKTOP_UA
                                                        )
                                                    )
                                                )
                                            }
                                        }
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
    }
}
