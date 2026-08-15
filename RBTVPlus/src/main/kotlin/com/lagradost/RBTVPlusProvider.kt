package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.RBTVPlus.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    companion object {
        val posterCache = java.util.concurrent.ConcurrentHashMap<String, String>()
        val logoCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
        val cleanClient = okhttp3.OkHttpClient()
        
        private var cachedLiveMatches: List<LiveMatchInfo>? = null
        private var lastLiveMatchesFetchTime = 0L
        private val liveMatchesMutex = Mutex()
        private const val LIVE_MATCHES_CACHE_TTL = 2 * 60 * 1000L // 2 menit
        private var resolvedMainUrl: String? = null
        private val urlMutex = Mutex()

        private fun downloadBitmap(url: String): android.graphics.Bitmap? {
            return try {
                logoCache[url]?.let { return it }
                val refererUrl = resolvedMainUrl ?: BuildConfig.RBTV_MAIN_URL
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", BuildConfig.RBTV_USER_AGENT)
                    .header("Referer", "$refererUrl/")
                    .build()
                cleanClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val opts = android.graphics.BitmapFactory.Options().apply {
                                inSampleSize = 2
                                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                            }
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                            if (bmp != null) {
                                logoCache[url] = bmp
                            }
                            bmp
                        } else null
                    } else null
                }
            } catch (e: Throwable) {
                null
            }
        }
    }
    // Domain dari BuildConfig — diisi via GitHub Secrets (CI) atau local.properties (lokal)
    override var mainUrl = BuildConfig.RBTV_MAIN_URL

    private suspend fun getOrResolveMainUrl(): String {
        resolvedMainUrl?.let { 
            mainUrl = it
            return it 
        }
        return urlMutex.withLock {
            resolvedMainUrl?.let { 
                mainUrl = it
                return@withLock it 
            }
            
            // 1. Coba baca domain aktif dari Gist
            try {
                val gistUrl = BuildConfig.RBTV_GIST_URL + "?t=${System.currentTimeMillis()}"
                val response = app.get(gistUrl, timeout = 8000).text
                val json = org.json.JSONObject(response)
                val activeDomain = json.optString("active_domain", "")
                if (activeDomain.isNotEmpty()) {
                    val urlObj = java.net.URL(activeDomain)
                    val newMainUrl = "${urlObj.protocol}://${urlObj.host}"
                    resolvedMainUrl = newMainUrl
                    mainUrl = newMainUrl
                    android.util.Log.d("RBTVPlus", "Successfully resolved mainUrl from Gist: $newMainUrl")
                    return@withLock newMainUrl
                }
            } catch (e: Exception) {
                // ignore, lanjut ke fallback redirect
            }
            
            // 2. Jika Gist gagal, gunakan BuildConfig.RBTV_MAIN_URL dan follow redirect
            val initialUrl = BuildConfig.RBTV_MAIN_URL
            try {
                val response = app.get("$initialUrl/id/", timeout = 8)
                if (response.code == 200) {
                    val finalUrl = response.url
                    val urlObj = java.net.URL(finalUrl)
                    val newMainUrl = "${urlObj.protocol}://${urlObj.host}"
                    resolvedMainUrl = newMainUrl
                    mainUrl = newMainUrl
                    android.util.Log.d("RBTVPlus", "Successfully resolved mainUrl via redirect: $newMainUrl")
                    return@withLock newMainUrl
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // 3. Jika gagal semua, fallback ke initialUrl
            resolvedMainUrl = initialUrl
            mainUrl = initialUrl
            initialUrl
        }
    }
    override var name = "🏆 RBTV+"
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
        logoUrl1: String? = null,
        logoUrl2: String? = null,
        preloadedLogos: Map<String, android.graphics.Bitmap?> = emptyMap(),
        isIndonesia: Boolean = false,
        title: String? = null
    ): String {
        val cacheKey = "${sport}_${league ?: ""}_${team1 ?: ""}_${team2 ?: ""}_${timeStr}_${sportType}_${isLive}_${logoUrl1 ?: ""}_${logoUrl2 ?: ""}_${isIndonesia}_${title ?: ""}"
        val cached = posterCache[cacheKey]
        if (cached != null) return cached
        return try {
            val width = 400
            val height = 600
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

            // Tema warna dinamis berdasarkan sportType
            val (baseColor, accentColor) = if (isIndonesia) {
                Pair("#560202", "#ffffff") // Merah Gelap & Putih
            } else {
                when (sportType) {
                    1 -> Pair("#041c0e", "#00ff87") // Football (Dark Green -> Neon Green)
                    2 -> Pair("#241105", "#ff5e00") // Basketball (Dark Orange -> Neon Orange)
                    3, 12 -> Pair("#1a2007", "#ccff00") // Tennis / Badminton (Dark Lime -> Neon Lime)
                    14 -> Pair("#260d0d", "#ff3333") // Fighting (Dark Crimson -> Neon Red)
                    7, 15 -> Pair("#0d1e26", "#00d2ff") // Motorsport / Cycling (Dark Blue -> Neon Blue)
                    else -> Pair("#16082c", "#00f2fe") // Default (Dark Purple -> Neon Cyan)
                }
            }

            // 1. Draw Background Gradient (LinearGradient dari hitam pekat ke warna dasar tema gelap)
            val bgGradient = android.graphics.LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                android.graphics.Color.parseColor("#020202"),
                android.graphics.Color.parseColor(baseColor),
                android.graphics.Shader.TileMode.CLAMP
            )
            paint.shader = bgGradient
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null // Reset shader

            // 2. Draw Slicing Diagonal Background (Path diagonal transparan untuk efek e-sports)
            paint.color = android.graphics.Color.parseColor(accentColor)
            paint.alpha = 10 // Sangat transparan (sekitar 4% opacity)
            val diagPath = android.graphics.Path().apply {
                moveTo(0f, height * 0.4f)
                lineTo(width.toFloat(), height * 0.2f)
                lineTo(width.toFloat(), height.toFloat())
                lineTo(0f, height.toFloat())
                close()
            }
            canvas.drawPath(diagPath, paint)
            paint.alpha = 255 // Reset alpha

            // 3. Draw Card Container (Glassmorphism card)
            // Warna card transparan agar menyatu dengan background gradien
            paint.color = android.graphics.Color.parseColor("#E6" + baseColor.replace("#", "")) // 90% opacity
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRoundRect(25f, 40f, 375f, 560f, 24f, 24f, paint)

            // Border kartu menggunakan gradien accentColor ke semi-transparan
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
            paint.shader = null // Reset shader

            if (isIndonesia) {
                val cardRect = android.graphics.RectF(25f, 40f, 375f, 560f)
                val cardPath = android.graphics.Path().apply {
                    addRoundRect(cardRect, 24f, 24f, android.graphics.Path.Direction.CW)
                }
                canvas.save()
                canvas.clipPath(cardPath)

                // Ribbon Merah (paling ujung)
                val redPath = android.graphics.Path().apply {
                    moveTo(335f, 40f)
                    lineTo(375f, 40f)
                    lineTo(375f, 80f)
                    close()
                }
                paint.color = android.graphics.Color.parseColor("#ff0000")
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawPath(redPath, paint)

                // Ribbon Putih (sejajar di bawah merah)
                val whitePath = android.graphics.Path().apply {
                    moveTo(295f, 40f)
                    lineTo(335f, 40f)
                    lineTo(375f, 80f)
                    lineTo(375f, 120f)
                    close()
                }
                paint.color = android.graphics.Color.WHITE
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawPath(whitePath, paint)

                canvas.restore()
            }

            // 4. Draw HUD Corners (Ornamen siku antarmuka)
            paint.color = android.graphics.Color.parseColor(accentColor)
            paint.alpha = 100 // Semi-transparan
            paint.strokeWidth = 3f
            // Sudut Kiri Atas
            canvas.drawLine(40f, 55f, 60f, 55f, paint)
            canvas.drawLine(40f, 55f, 40f, 75f, paint)
            // Sudut Kanan Atas
            canvas.drawLine(360f, 55f, 340f, 55f, paint)
            canvas.drawLine(360f, 55f, 360f, 75f, paint)
            // Sudut Kiri Bawah
            canvas.drawLine(40f, 545f, 60f, 545f, paint)
            canvas.drawLine(40f, 545f, 40f, 525f, paint)
            // Sudut Kanan Bawah
            canvas.drawLine(360f, 545f, 340f, 545f, paint)
            canvas.drawLine(360f, 545f, 360f, 525f, paint)
            paint.alpha = 255 // Reset alpha

            // 5. Draw Header (Sport name)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = android.graphics.Color.parseColor("#a0a5c0")
            paint.textSize = 24f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            canvas.drawText(sport.uppercase(), 200f, 95f, paint)

            // 6. Draw League
            paint.color = android.graphics.Color.parseColor(accentColor)
            paint.textSize = 30f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            val cleanLeague = league ?: "Tournament"
            val truncatedLeague = if (cleanLeague.length > 20) cleanLeague.substring(0, 17) + "..." else cleanLeague
            canvas.drawText(truncatedLeague, 200f, 150f, paint)

            val isSingleEvent = team1.isNullOrEmpty() && team2.isNullOrEmpty()
            if (isSingleEvent) {
                // Draw single container card Y=185 to Y=445
                paint.color = android.graphics.Color.parseColor("#26FFFFFF") // Putih transparan (15% opacity)
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawRoundRect(45f, 185f, 355f, 445f, 16f, 16f, paint)
                // Border
                paint.color = android.graphics.Color.parseColor("#40FFFFFF")
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                canvas.drawRoundRect(45f, 185f, 355f, 445f, 16f, 16f, paint)

                // League name top half
                paint.color = android.graphics.Color.WHITE
                paint.style = android.graphics.Paint.Style.FILL
                paint.textSize = 34f
                paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                paint.textAlign = android.graphics.Paint.Align.CENTER
                val cleanLg = league ?: "Tournament"
                val lgLines = wrapText(cleanLg, 16)
                var currentY = if (lgLines.size > 1) 230f else 255f
                for (line in lgLines) {
                    canvas.drawText(line, 200f, currentY, paint)
                    currentY += 40f
                }

                // Divider line
                paint.color = android.graphics.Color.parseColor("#33FFFFFF")
                paint.strokeWidth = 2f
                canvas.drawLine(100f, 315f, 300f, 315f, paint)

                // Match title bottom half
                paint.color = android.graphics.Color.parseColor(accentColor)
                paint.textSize = 30f
                paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                val cleanT = title?.replace(Regex("\\s*\\(\\d{2}:\\d{2}\\s*WIB\\)\\s*"), "") ?: "Live Event"
                val tLines = wrapText(cleanT, 16)
                currentY = if (tLines.size > 1) 365f else 385f
                for (line in tLines) {
                    canvas.drawText(line, 200f, currentY, paint)
                    currentY += 36f
                }
                paint.textAlign = android.graphics.Paint.Align.CENTER
            } else {
                // 7. Draw Team 1 Badge Card & Text
                // Sub-container card untuk Team 1
                paint.color = android.graphics.Color.parseColor("#26FFFFFF") // Putih transparan (15% opacity)
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawRoundRect(45f, 185f, 355f, 285f, 16f, 16f, paint)
                // Border mini card
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
                        canvas.drawBitmap(logoBmp, null, destRect, null)
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

                // Teks Team 1
                paint.color = android.graphics.Color.WHITE
                paint.style = android.graphics.Paint.Style.FILL
                paint.textSize = 36f
                paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                val t1 = team1 ?: "Team A"
                val t1Limit = if (team1TextLeft == 155f) 8 else 14
                val t1Lines = wrapText(t1, t1Limit)
                if (team1TextLeft == 65f) {
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

                // 8. Draw Glow Divider & VS Text
                // Garis pemisah horizontal kiri
                paint.color = android.graphics.Color.parseColor("#33FFFFFF")
                paint.strokeWidth = 2f
                canvas.drawLine(45f, 315f, 150f, 315f, paint)
                // Garis pemisah horizontal kanan
                canvas.drawLine(250f, 315f, 355f, 315f, paint)

                // Teks VS
                paint.color = android.graphics.Color.parseColor(accentColor)
                paint.textSize = 34f
                paint.textAlign = android.graphics.Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD_ITALIC)
                canvas.drawText("VS", 200f, 326f, paint)

                // 9. Draw Team 2 Badge Card & Text
                // Sub-container card untuk Team 2
                paint.color = android.graphics.Color.parseColor("#26FFFFFF") // Putih transparan (15% opacity)
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawRoundRect(45f, 345f, 355f, 445f, 16f, 16f, paint)
                // Border mini card
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
                        canvas.drawBitmap(logoBmp, null, destRect, null)
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

                // Teks Team 2
                paint.color = android.graphics.Color.WHITE
                paint.style = android.graphics.Paint.Style.FILL
                paint.textSize = 36f
                paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                val t2 = team2 ?: "Team B"
                val t2Limit = if (team2TextLeft == 155f) 8 else 14
                val t2Lines = wrapText(t2, t2Limit)
                if (team2TextLeft == 65f) {
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
                paint.textAlign = android.graphics.Paint.Align.CENTER
            }

            // 10. Draw Status Badge (LIVE NOW / UPCOMING)
            val badgeColor = if (isLive) "#ff3333" else "#1a73e8"
            val badgeText = if (isLive) "LIVE NOW" else "UPCOMING"
            paint.color = android.graphics.Color.parseColor(badgeColor)
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRoundRect(100f, 475f, 300f, 520f, 22f, 22f, paint)

            // Efek Live Dot (bulatan merah)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 24f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            
            if (isLive) {
                paint.color = android.graphics.Color.RED
                canvas.drawCircle(145f, 497.5f, 7f, paint)
                paint.color = android.graphics.Color.WHITE
                canvas.drawText("LIVE NOW", 215f, 506f, paint)
            } else {
                canvas.drawText(badgeText, 200f, 506f, paint)
            }

            // 11. Draw Time Subtext
            paint.color = android.graphics.Color.parseColor("#a0a5c0")
            paint.textSize = 24f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            canvas.drawText(timeStr, 200f, 550f, paint)

            // Compress & Encode to Base64 (JPEG format)
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
        val keyStr = BuildConfig.RBTV_AES_KEY
        val ivStr = BuildConfig.RBTV_AES_IV
        
        val keySpec = javax.crypto.spec.SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = javax.crypto.spec.IvParameterSpec(ivStr.toByteArray(Charsets.UTF_8))
        
        val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val base64Str = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        return URLEncoder.encode(base64Str, "UTF-8")
    }

    private suspend fun getApiHost(): String {
        val currentMainUrl = getOrResolveMainUrl()
        // API host dari BuildConfig — diisi via GitHub Secrets (CI) atau local.properties (lokal)
        val fallback = BuildConfig.RBTV_API_HOST
        try {
            val response = app.get("$currentMainUrl/id/", timeout = 10)
            val html = response.text
            val jsUrls = Regex("""https://statics1\.[a-zA-Z0-9.-]+\.cfd/statics/[a-f0-9]+\.js""").findAll(html)
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
        val bsUrl = "$apiHost${BuildConfig.RBTV_PATH_BS}?code=100&code=101&stream=true&sportType=$sportType&language=34"
        val headers = mapOf(
            "User-Agent" to BuildConfig.RBTV_USER_AGENT,
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
        val url = "$apiHost/$sfver${BuildConfig.RBTV_PATH_LIVE}?$query"
        val headers = mapOf(
            "User-Agent" to BuildConfig.RBTV_USER_AGENT,
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

    private data class ParsedMatchRaw(
        val matchId: Long,
        val finalStreamId: String,
        val finalTitle: String,
        val homeName: String?,
        val awayName: String?,
        val leagueName: String?,
        val matchSportType: Int,
        val matchTime: Long,
        val matchStatus: Long,
        val logoUrl1: String?,
        val logoUrl2: String?,
        val isIndonesia: Boolean
    )

    private fun getFullLogoUrl(logo: String, apiHost: String): String {
        val activeLogoHost = try {
            val apiHostUri = java.net.URI(apiHost)
            val apiHostHost = apiHostUri.host ?: ""
            if (apiHostHost.startsWith("apis-data")) {
                val base = apiHostHost.substringAfter(".")
                "https://logos1.$base"
            } else {
                "https://logos1.tcdru136ovur.ru"
            }
        } catch (e: Exception) {
            "https://logos1.tcdru136ovur.ru"
        }

        val domain = mainUrl.ifEmpty { "https://www.rbtvplus.com" }
        return when {
            logo.contains("/aelogo/") -> {
                val path = logo.substringAfter("/aelogo/")
                "$activeLogoHost/aelogo/$path"
            }
            logo.startsWith("http") -> logo
            logo.startsWith("//") -> "https:$logo"
            logo.startsWith("/") -> "${domain}${logo}"
            else -> "${domain}/${logo}"
        }
    }

    private fun isIndonesiaMatch(title: String?, league: String?, team1: String?, team2: String?): Boolean {
        val keywords = listOf("indonesia", "piala presiden", "piala aff", "aff cup", "persija", "persib", "persebaya", "bali united", "timnas")
        val t = (title ?: "").lowercase()
        val l = (league ?: "").lowercase()
        val t1 = (team1 ?: "").lowercase()
        val t2 = (team2 ?: "").lowercase()
        return keywords.any { kw -> t.contains(kw) || l.contains(kw) || t1.contains(kw) || t2.contains(kw) }
    }

    private suspend fun parseLiveMatches(data: ByteArray, sportType: Int, apiHost: String): List<LiveMatchInfo> {
        val parser = ProtoParser(data)
        val rawItems = ArrayList<ParsedMatchRaw>()
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

                                val timeSdfShort = java.text.SimpleDateFormat("HH:mm", java.util.Locale("id", "ID"))
                                timeSdfShort.timeZone = java.util.TimeZone.getTimeZone("GMT+7")
                                val timeStrShort = timeSdfShort.format(java.util.Date(matchTime))

                                val finalTitle = if (homeName != null && awayName != null) {
                                    "$homeName vs $awayName" + (if (leagueName != null) " (${cleanText(leagueName)})" else "")
                                } else {
                                    val base = cleanText(rawTitle) ?: (cleanText(leagueName) ?: "RBTV+ Live Match")
                                    "$base ($timeStrShort WIB)"
                                }

                                val rawLogo1 = teamLogos.getOrNull(0)?.let { getFullLogoUrl(it, apiHost) }
                                val rawLogo2 = teamLogos.getOrNull(1)?.let { getFullLogoUrl(it, apiHost) }
                                val isIndo = isIndonesiaMatch(finalTitle, leagueName, homeName, awayName)

                                rawItems.add(
                                    ParsedMatchRaw(
                                        matchId = matchId,
                                        finalStreamId = finalStreamId,
                                        finalTitle = finalTitle,
                                        homeName = homeName,
                                        awayName = awayName,
                                        leagueName = leagueName,
                                        matchSportType = matchSportType,
                                        matchTime = matchTime,
                                        matchStatus = matchStatus,
                                        logoUrl1 = rawLogo1,
                                        logoUrl2 = rawLogo2,
                                        isIndonesia = isIndo
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

        // Unduh logo secara terkendali (Semaphore 3) agar tidak membebani memori di Android TV
        val logoUrls = rawItems.flatMap { listOf(it.logoUrl1, it.logoUrl2) }.filterNotNull().filter { it.isNotEmpty() }.distinct().take(25)
        val logoSemaphore = kotlinx.coroutines.sync.Semaphore(3)
        val logoBitmaps = coroutineScope {
            logoUrls.map { url ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    logoSemaphore.withPermit {
                        try {
                            url to downloadBitmap(url)
                        } catch (e: Throwable) {
                            url to null
                        }
                    }
                }
            }.awaitAll().toMap()
        }

        for (item in rawItems) {
            val sportName = sportNames[item.matchSportType] ?: "Olahraga"
            val timeSdf = java.text.SimpleDateFormat("dd MMM, HH:mm 'WIB'", java.util.Locale("id", "ID"))
            timeSdf.timeZone = java.util.TimeZone.getTimeZone("GMT+7")
            val timeStr = timeSdf.format(java.util.Date(item.matchTime))
            
            val now = System.currentTimeMillis() + serverTimeOffset
            val isLive = (item.matchStatus in ongoingStatuses) || (now >= item.matchTime && item.matchStatus < 10000L)
            
            val finalPosterUrl = generateDynamicJpegPoster(
                sport = sportName,
                league = item.leagueName,
                team1 = item.homeName,
                team2 = item.awayName,
                timeStr = timeStr,
                sportType = item.matchSportType,
                isLive = isLive,
                logoUrl1 = item.logoUrl1,
                logoUrl2 = item.logoUrl2,
                preloadedLogos = logoBitmaps,
                isIndonesia = item.isIndonesia,
                title = item.finalTitle
            )

            matches.add(
                LiveMatchInfo(
                    matchId = item.matchId,
                    streamId = item.finalStreamId,
                    matchTitle = item.finalTitle,
                    homeName = item.homeName,
                    awayName = item.awayName,
                    leagueName = item.leagueName,
                    sportType = item.matchSportType,
                    posterUrl = finalPosterUrl,
                    matchTime = item.matchTime,
                    matchStatus = item.matchStatus
                )
            )
        }

        return matches
    }


    private suspend fun fetchAllLiveMatches(apiHost: String): List<LiveMatchInfo> {
        val now = System.currentTimeMillis()
        val cached = cachedLiveMatches
        if (cached != null && (now - lastLiveMatchesFetchTime) < LIVE_MATCHES_CACHE_TTL) {
            return cached
        }
        return liveMatchesMutex.withLock {
            val cachedSecond = cachedLiveMatches
            if (cachedSecond != null && (System.currentTimeMillis() - lastLiveMatchesFetchTime) < LIVE_MATCHES_CACHE_TTL) {
                return@withLock cachedSecond
            }
            val matches = coroutineScope {
                sportTypes.map { sportType ->
                    async {
                        try {
                            val bytes = fetchLiveMatchesRaw(apiHost, sportType)
                            if (bytes != null) parseLiveMatches(bytes, sportType, apiHost) else emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten().distinctBy { it.matchId }
            }
            if (matches.isNotEmpty()) {
                cachedLiveMatches = matches
                lastLiveMatchesFetchTime = System.currentTimeMillis()
            }
            matches
        }
    }

    private suspend fun fetchGistLiveMatchIds(): Set<Long>? {
        return try {
            val gistUrl = BuildConfig.RBTV_GIST_URL + "?t=${System.currentTimeMillis()}"
            val response = app.get(gistUrl, timeout = 8000).text
            val json = org.json.JSONObject(response)
            val matches = json.optJSONArray("matches") ?: return null
            val ids = mutableSetOf<Long>()
            for (i in 0 until matches.length()) {
                val matchId = matches.getJSONObject(i).optLong("matchId", 0L)
                if (matchId > 0L) ids.add(matchId)
            }
            if (ids.isNotEmpty()) ids else null
        } catch (e: Exception) {
            null  // Fallback ke heuristik jika Gist tidak bisa diakses
        }
    }

    private fun getSportMaxDurationMs(sportType: Int): Long {
        return when (sportType) {
            1 -> 130 * 60 * 1000L       // Sepak Bola: 2 jam 10 menit
            2 -> 160 * 60 * 1000L       // Basket: 2 jam 40 menit
            3 -> 300 * 60 * 1000L       // Tenis: 5 jam
            4 -> 240 * 60 * 1000L       // Bisbol: 4 jam
            6 -> 480 * 60 * 1000L       // Kriket: 8 jam
            7 -> 210 * 60 * 1000L       // Motorsport: 3,5 jam
            8 -> 120 * 60 * 1000L       // Rugby: 2 jam
            12, 13 -> 180 * 60 * 1000L  // Bulutangkis, Voli: 3 jam
            14 -> 360 * 60 * 1000L      // Fighting: 6 jam
            90 -> 480 * 60 * 1000L      // Golf: 8 jam
            else -> 180 * 60 * 1000L    // Default: 3 jam
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val apiHost = getApiHost()
        val allMatches = fetchAllLiveMatches(apiHost)

        val now = System.currentTimeMillis() + serverTimeOffset

        // Ambil daftar live matchId dari Gist (sumber kebenaran, diupdate tiap 5 mnt)
        val gistLiveIds = fetchGistLiveMatchIds()

        val liveMatches = allMatches.filter { m ->
            // Sembunyikan yang sudah FINISH / CANCEL / POSTPONE (status >= 10000)
            if (m.matchStatus >= 10000L) return@filter false
            
            // Pertandingan Indonesia selalu tampilkan di halaman utama, walaupun masih upcoming
            if (isIndonesiaMatch(m.matchTitle, m.leagueName, m.homeName, m.awayName)) {
                return@filter true
            }

            // Tampilkan yang jelas sedang live (matchStatus aktif dari server)
            if (m.matchStatus in ongoingStatuses) return@filter true

            // Jika ada di Gist, langsung tampilkan
            if (gistLiveIds != null && m.matchId in gistLiveIds) return@filter true

            // Fallback waktu: tampilkan jika waktu mulai sudah lewat >= 10 menit (untuk antisipasi Gist telat update)
            m.matchTime > 0L && now >= (m.matchTime + 10 * 60 * 1000) && now <= (m.matchTime + getSportMaxDurationMs(m.sportType))
        }

        val homePages = ArrayList<HomePageList>()

        fun addCategory(title: String, matches: List<LiveMatchInfo>) {
            if (matches.isNotEmpty()) {
                val searchResps = matches.map { m ->
                    android.util.Log.d("RBTVPlus", "Sending Match to UI: ID=${m.matchId}, Title=${m.matchTitle}, Time=${m.matchTime}")
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

        // Sort matches: Indonesia & Live -> Indonesia & Upcoming -> Lainnya & Live -> Lainnya & Upcoming
        val sortedLiveMatches = liveMatches.sortedWith(
            compareByDescending<LiveMatchInfo> { 
                val isIndo = isIndonesiaMatch(it.matchTitle, it.leagueName, it.homeName, it.awayName)
                val isLive = (it.matchStatus in ongoingStatuses) || (it.matchTime > 0L && now >= it.matchTime && it.matchStatus < 10000L)
                if (isIndo && isLive) 2 else if (isIndo) 1 else 0
            }.thenByDescending { 
                (it.matchStatus in ongoingStatuses) || (it.matchTime > 0L && now >= it.matchTime && it.matchStatus < 10000L)
            }.thenBy { 
                if (it.sportType == 1) 0 else 1 
            }.thenBy { 
                it.matchTime 
            }
        )
        addCategory("Live Event", sortedLiveMatches)

        // Hapus poster lama yang tidak aktif di cache
        val activeKeys = allMatches.map { m ->
            val sportName = sportNames[m.sportType] ?: "Olahraga"
            val timeSdf = java.text.SimpleDateFormat("dd MMM, HH:mm 'WIB'", java.util.Locale("id", "ID")).apply {
                timeZone = java.util.TimeZone.getTimeZone("GMT+7")
            }
            val timeStr = timeSdf.format(java.util.Date(m.matchTime))
            val isLive = (m.matchStatus in ongoingStatuses) || (now >= m.matchTime && m.matchStatus < 10000L)
            "${sportName}_${m.leagueName ?: ""}_${m.homeName ?: ""}_${m.awayName ?: ""}_${timeStr}_${m.sportType}_$isLive"
        }.toSet()
        posterCache.keys.retainAll(activeKeys)

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
        val cleanUrl = if (url.contains("lynk.id")) {
            val hash = url.substringAfterLast("#", "")
            "https://www.cutad.web.id/watch?$hash"
        } else {
            url
        }
        val uri = URI(cleanUrl)
        val queryMap = uri.query?.split("&")?.associate {
            val parts = it.split("=")
            parts[0] to parts.getOrNull(1)
        } ?: emptyMap()
        
        val matchId = queryMap["id"] ?: queryMap["matchId"] ?: return null
        val sportType = queryMap["sportType"] ?: "1"
        val streamId = queryMap["stream_id"] ?: queryMap["streamId"] ?: matchId
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
            "https://lynk.id/xr3ed#$loadData&t=${System.currentTimeMillis()}",
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
            val detailUrl = "$apiHost/$detailSfver${BuildConfig.RBTV_PATH_DETAIL}?matchId=$matchId&sportType=$sportType&language=34"

            val headers = mapOf(
                "User-Agent" to BuildConfig.RBTV_USER_AGENT,
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
                            val streamUrl = "$apiHost/$streamSfver${BuildConfig.RBTV_PATH_STREAM_DETAIL}?$streamQuery"

                            val streamResponse = app.get(streamUrl, headers = headers, timeout = 15)
                            if (streamResponse.code == 200) {
                                var rbSession = streamResponse.headers["rb-session"]
                                val streamDetailBytes = streamResponse.body.bytes()

                                // Fallback jika rb-session null
                                if (rbSession.isNullOrEmpty()) {
                                    val urlErr = "$apiHost${BuildConfig.RBTV_PATH_STREAM_DETAIL}?matchId=$matchId&sportType=$sportType&language=34"
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

                                             val isDash = finalUrl.contains(".mpd", ignoreCase = true)
                                             val linkType = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8

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
                                                 ExtractorLink(
                                                     source = "RBTV+",
                                                     name = sourceName,
                                                     url = finalUrl,
                                                     referer = "https://lola30es.mpipzni2naturally32kistomach.ru/",
                                                     quality = qualityVal,
                                                     type = linkType,
                                                     headers = mapOf(
                                                         "Referer" to "https://lola30es.mpipzni2naturally32kistomach.ru/",
                                                         "Origin" to "https://lola30es.mpipzni2naturally32kistomach.ru",
                                                         "User-Agent" to BuildConfig.RBTV_USER_AGENT
                                                     )
                                                 )
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
