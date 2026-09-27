package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.SportsurgeXR.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.net.URLEncoder

// Data class untuk detail event di web
data class WebMatchInfo(
    val path: String,
    val status: String,
    val timeStr: String,
    val countdown: String,
    val team1: String,
    val logo1: String,
    val team2: String,
    val logo2: String,
    val league: String,
    val isSolo: Boolean = false
)

// Data class untuk list stream yang di-serialize ke JSON loadData
data class SportsurgeStreamInfo(
    val channel: String,
    val language: String,
    val url: String
)

class SportsurgeXRProvider : MainAPI() {
    companion object {
        const val POSTER_WORKER_URL = "https://sportsurge-poster.xr3ed-cdn.workers.dev"
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val cleanClient = okhttp3.OkHttpClient()
    }

    override var mainUrl = "https://ww1.sportsurge.st"
    override var name = "⚽ SportsurgeXR"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "id"
    override val hasMainPage = true
    
    override val mainPage = listOf(
        MainPageData("Sepak Bola", "/football", horizontalImages = true),
        MainPageData("NFL", "/nfl", horizontalImages = true),
        MainPageData("Basket", "/nba", horizontalImages = true),
        MainPageData("Tinju", "/boxing", horizontalImages = true),
        MainPageData("MMA", "/ufc", horizontalImages = true),
        MainPageData("Bisbol", "/baseball", horizontalImages = true),
        MainPageData("Hoki Es", "/nhl", horizontalImages = true),
        MainPageData("Formula 1", "/f1", horizontalImages = true),
        MainPageData("Rugby", "/rugby", horizontalImages = true)
    )

    private fun formatStartTimeToWib(rawIso: String?): String {
        if (rawIso.isNullOrEmpty()) return ""
        return try {
            val instant = java.time.Instant.parse(rawIso)
            val zdt = instant.atZone(java.time.ZoneId.of("Asia/Jakarta"))
            val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm 'WIB'")
            zdt.format(formatter)
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatCountdown(raw: String): String {
        val lower = raw.lowercase()
        if (lower.contains("live")) return "LIVE STREAM"
        if (lower.contains("starts in:")) {
            val parts = raw.replace("Starts in:", "", ignoreCase = true).trim().split(":")
            if (parts.size >= 2) {
                val h = parts[0].toIntOrNull() ?: 0
                val m = parts[1].toIntOrNull() ?: 0
                return when {
                    h > 0 && m > 0 -> "IN ${h}H ${m}M"
                    h > 0 -> "IN $h HOURS"
                    m > 0 -> "IN $m MIN"
                    else -> "UPCOMING"
                }
            }
        }
        return "UPCOMING"
    }

    private fun buildPosterUrl(
        sport: String,
        league: String,
        home: String,
        away: String,
        time: String,
        countdown: String,
        isLive: Boolean,
        isSolo: Boolean,
        logo1: String,
        logo2: String
    ): String {
        return try {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val sb = StringBuilder(POSTER_WORKER_URL)
            sb.append("?sport=").append(enc(sport))
            sb.append("&league=").append(enc(league))
            sb.append("&home=").append(enc(home))
            if (!isSolo && away.isNotEmpty()) {
                sb.append("&away=").append(enc(away))
            }
            if (time.isNotEmpty()) {
                sb.append("&time=").append(enc(time))
            }
            if (countdown.isNotEmpty()) {
                sb.append("&countdown=").append(enc(countdown))
            }
            if (isLive) {
                sb.append("&live=1")
            }
            if (isSolo) {
                sb.append("&solo=1")
            }
            if (logo1.isNotEmpty()) {
                sb.append("&logo1=").append(enc(logo1))
            }
            if (logo2.isNotEmpty()) {
                sb.append("&logo2=").append(enc(logo2))
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun unescapeNextF(text: String): String {
        return text
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
    }

    private fun getSportForLeague(league: String): String {
        val l = league.lowercase()
        return when {
            l.contains("mlb") || l.contains("baseball") || l.contains("bisbol") -> "baseball"
            l.contains("nfl") || l.contains("american football") -> "nfl"
            l.contains("nba") || l.contains("basketball") || l.contains("wnba") || l.contains("euroleague") || l.contains("basket") -> "nba"
            l.contains("boxing") || l.contains("tinju") || l.contains("ppv") -> "boxing"
            l.contains("ufc") || l.contains("mma") || l.contains("bellator") || l.contains("one championship") -> "ufc"
            l.contains("nhl") || l.contains("hockey") || l.contains("hoki") -> "nhl"
            l.contains("formula 1") || l.contains("f1") || l.contains("motogp") || l.contains("motorsport") -> "f1"
            l.contains("rugby") -> "rugby"
            else -> "football" // Default to football/soccer
        }
    }

    private fun getDisplayNameForSport(sport: String): String {
        return when (sport.lowercase()) {
            "football" -> "Sepak Bola"
            "nfl" -> "NFL"
            "nba" -> "NBA"
            "ufc" -> "UFC"
            "boxing" -> "Boxing"
            "f1" -> "Formula 1"
            "nhl" -> "NHL"
            "rugby" -> "Rugby"
            else -> sport.uppercase()
        }
    }

    private fun parseMatches(html: String, targetSport: String? = null): List<WebMatchInfo> {
        val document = org.jsoup.Jsoup.parse(html)
        val matches = ArrayList<WebMatchInfo>()
        
        // Select league blocks
        val leagueBlocks = document.select("div.mb-7, div[class*=\"mb-7\"]")
        for (leagueEl in leagueBlocks) {
            val leagueNameEl = leagueEl.selectFirst("div.text-white.font-semibold.text-sm")
            val leagueName = leagueNameEl?.text() ?: if (targetSport != null) {
                getDisplayNameForSport(targetSport)
            } else continue
            if (leagueName.isEmpty()) continue
            
            val sport = if (leagueNameEl != null) getSportForLeague(leagueName) else targetSport ?: "other"
            if (targetSport != null && sport != targetSport) {
                continue
            }
            
            val matchLinks = leagueEl.select("a[href^=\"/events/\"]")
            for (linkEl in matchLinks) {
                val path = linkEl.attr("href")
                if (path.isEmpty()) continue
                
                val countdownEl = linkEl.selectFirst("div.countdown-status, div.text-xs")
                val statusText = countdownEl?.text()?.trim() ?: "Upcoming"
                val rawStart = countdownEl?.attr("data-start")
                
                val timeWib = formatStartTimeToWib(rawStart)
                val countdown = formatCountdown(statusText)
                val isLive = statusText.contains("live", ignoreCase = true) || (countdownEl?.className()?.contains("text-red") == true)
                
                val teamDivs = linkEl.select("div.flex.gap-2.items-center, div.flex.items-center.gap-2")
                val team1 = teamDivs.getOrNull(0)?.text()?.trim() ?: "Team A"
                val logo1 = teamDivs.getOrNull(0)?.selectFirst("img")?.attr("src") ?: ""
                val team2 = teamDivs.getOrNull(1)?.text()?.trim() ?: ""
                val logo2 = teamDivs.getOrNull(1)?.selectFirst("img")?.attr("src") ?: ""
                
                val isSolo = team2.isEmpty() || team2.equals("Live", ignoreCase = true) || team1.contains("redzone", ignoreCase = true) || team1.contains("grand prix", ignoreCase = true)
                
                val isEnded = statusText.contains("ended", ignoreCase = true) ||
                        statusText.contains("finished", ignoreCase = true) ||
                        statusText.contains("completed", ignoreCase = true)
                
                if (!isEnded) {
                    matches.add(WebMatchInfo(
                        path = path,
                        status = if (isLive) "LIVE NOW" else "Upcoming",
                        timeStr = if (isLive) "LIVE NOW" else if (timeWib.isNotEmpty()) timeWib else statusText,
                        countdown = countdown,
                        team1 = team1,
                        logo1 = logo1,
                        team2 = team2,
                        logo2 = logo2,
                        league = leagueName,
                        isSolo = isSolo
                    ))
                }
            }
        }
        
        // Fallback: if JSoup selection found nothing, try Next.js regex parsing (only for search or general fallback)
        if (matches.isEmpty() && targetSport == null) {
            val pushes = Regex("""self\.__next_f\.push\(\[\d+,\s*"(.*?)"\]\)""")
                .findAll(html)
                .map { it.groupValues[1] }
                .joinToString("")
            
            val combined = unescapeNextF(pushes)
            val blocks = combined.split("{\"href\":\"/events/")
            
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
                val t2Name = teams.getOrNull(1) ?: ""
                val t2Logo = logos.getOrNull(1) ?: ""
                
                val isSolo = t2Name.isEmpty() || t2Name.equals("Live", ignoreCase = true) || t1Name.contains("redzone", ignoreCase = true)
                val isLive = status.contains("live", ignoreCase = true)
                val isEnded = status.contains("ended", ignoreCase = true) ||
                        status.contains("finished", ignoreCase = true) ||
                        status.contains("completed", ignoreCase = true)
                
                if (!isEnded) {
                    matches.add(WebMatchInfo(
                        path = path,
                        status = if (isLive) "LIVE NOW" else "Upcoming",
                        timeStr = if (isLive) "LIVE NOW" else status,
                        countdown = if (isLive) "LIVE STREAM" else "UPCOMING",
                        team1 = t1Name,
                        logo1 = t1Logo,
                        team2 = t2Name,
                        logo2 = t2Logo,
                        league = "Live Match",
                        isSolo = isSolo
                    ))
                }
            }
        }
        
        return matches
    }

    private fun parseStreams(html: String): List<SportsurgeStreamInfo> {
        val streams = ArrayList<SportsurgeStreamInfo>()
        
        // 1. Coba parse dari standard HTML Table via Jsoup
        try {
            val doc = org.jsoup.Jsoup.parse(html)
            val rows = doc.select("table tr")
            for (row in rows) {
                val tds = row.select("td")
                if (tds.isEmpty()) continue
                val cols = tds.map { it.text().trim() }
                val aTag = row.selectFirst("a[href]")
                val href = aTag?.attr("href")
                if (!href.isNullOrEmpty() && href.startsWith("http")) {
                    val channel = if (cols.size > 1 && cols[1].isNotEmpty()) cols[1] else (cols.getOrNull(0) ?: "Stream")
                    val language = if (cols.size > 5 && cols[5].isNotEmpty()) cols[5] else "English"
                    streams.add(SportsurgeStreamInfo(channel, language, href))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        if (streams.isNotEmpty()) return streams

        // 2. Fallback ke Next.js self.__next_f.push parser
        val pushes = Regex("""self\.__next_f\.push\(\[\d+,\s*"(.*?)"\]\)""")
            .findAll(html)
            .map { it.groupValues[1] }
            .joinToString("")
            
        if (pushes.isNotEmpty()) {
            val combined = unescapeNextF(pushes)
            val rows = combined.split(Regex(""""tr","\d+""""))
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
        }
        return streams
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val categoryPath = request.data
        val categoryName = request.name
        
        val targetSport = categoryPath.replace("/", "").lowercase()
        
        // Coba scrape homepage terlebih dahulu untuk mendapatkan nama liga yang akurat
        var response = app.get(mainUrl, timeout = 15)
        var matches = if (response.code == 200) {
            parseMatches(response.text, targetSport)
        } else emptyList()
        
        // Jika kosong, fallback ke subpage kategori langsung
        if (matches.isEmpty()) {
            response = app.get("$mainUrl$categoryPath", timeout = 15)
            if (response.code == 200) {
                matches = parseMatches(response.text, targetSport)
            }
        }
        
        val sortedMatches = matches.sortedByDescending { it.status.contains("live", ignoreCase = true) }

        val searchResps = sortedMatches.map { m ->
            val isLive = m.status.contains("live", ignoreCase = true)
            val poster = buildPosterUrl(
                sport = categoryName,
                league = m.league,
                home = m.team1,
                away = m.team2,
                time = m.timeStr,
                countdown = m.countdown,
                isLive = isLive,
                isSolo = m.isSolo,
                logo1 = m.logo1,
                logo2 = m.logo2
            )
            
            val detailUrl = "https://lynk.id/xr3ed#$mainUrl${m.path}"
            val cardTitle = if (m.isSolo) {
                "${m.team1} (${m.league})"
            } else if (m.league.isNotEmpty()) {
                "${m.team1} vs ${m.team2} (${m.league})"
            } else {
                "${m.team1} vs ${m.team2}"
            }
            newLiveSearchResponse(
                cardTitle,
                detailUrl,
                TvType.Live
            ) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(categoryName, searchResps, isHorizontalImages = true)),
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
                            parseMatches(response.text, cat.data.replace("/", ""))
                        } else emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten().distinctBy { it.path }
            
            val filteredMatches = allMatches.filter { m ->
                m.team1.contains(query, ignoreCase = true) || m.team2.contains(query, ignoreCase = true)
            }.sortedByDescending { it.status.contains("live", ignoreCase = true) }

            filteredMatches.map { m ->
                val isLive = m.status.contains("live", ignoreCase = true)
                val poster = buildPosterUrl(
                    sport = "Olahraga",
                    league = m.league,
                    home = m.team1,
                    away = m.team2,
                    time = m.timeStr,
                    countdown = m.countdown,
                    isLive = isLive,
                    isSolo = m.isSolo,
                    logo1 = m.logo1,
                    logo2 = m.logo2
                )
                
                val detailUrl = "https://lynk.id/xr3ed#$mainUrl${m.path}"
                val cardTitle = if (m.isSolo) {
                    "${m.team1} (${m.league})"
                } else if (m.league.isNotEmpty()) {
                    "${m.team1} vs ${m.team2} (${m.league})"
                } else {
                    "${m.team1} vs ${m.team2}"
                }
                newLiveSearchResponse(
                    cardTitle,
                    detailUrl,
                    TvType.Live
                ) {
                    this.posterUrl = poster
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = if (url.contains("lynk.id")) url.substringAfterLast("#", "") else url
        val maskedUrl = if (url.contains("lynk.id")) url else "https://lynk.id/xr3ed#$url"

        val request = okhttp3.Request.Builder()
            .url(cleanUrl)
            .header("User-Agent", DESKTOP_UA)
            .build()
        val html = try {
            cleanClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string() ?: ""
                } else {
                    println("Request failed with code: ${response.code}, message: ${response.message}")
                    ""
                }
            }
        } catch (e: Exception) {
            println("Exception during load fetch:")
            e.printStackTrace()
            ""
        }
        if (html.isEmpty()) return null
        
        val streams = parseStreams(html)
        
        // Dapatkan nama tim dari URL event path
        val eventPath = cleanUrl.substringAfter("/events/", "")
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
            maskedUrl,
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
                        val duplicateCount = streams.count { it.channel.equals(item.channel, ignoreCase = true) }
                        val linkName = if (duplicateCount > 1) {
                            "${item.channel} #${index + 1}"
                        } else {
                            item.channel
                        }
                        
                        val quality = when {
                            item.channel.contains("4k", ignoreCase = true) || item.channel.contains("uhd", ignoreCase = true) -> Qualities.P2160.value
                            item.channel.contains("fhd", ignoreCase = true) || item.channel.contains("1080", ignoreCase = true) -> Qualities.P1080.value
                            item.channel.contains("480", ignoreCase = true) || item.channel.contains("sd", ignoreCase = true) -> Qualities.P480.value
                            else -> Qualities.P720.value
                        }
                        
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
                                            val cleanUrl = match.replace("[", "").replace("]", "").replace("\"", "").split(",").joinToString("").replace("\\/", "/")
                                            callback.invoke(
                                                ExtractorLink(
                                                    source = this@SportsurgeXRProvider.name,
                                                    name = linkName,
                                                    url = cleanUrl,
                                                    referer = "https://executeandship.com/",
                                                    quality = quality,
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
                                if (targetUrl.contains("trendy48")) {
                                    val embedUrl = if (targetUrl.contains("ch=")) {
                                        val ch = targetUrl.substringAfter("ch=", "")
                                        "https://trendy48.site/embed/$ch"
                                    } else {
                                        targetUrl
                                    }
                                    val req1 = okhttp3.Request.Builder()
                                        .url(embedUrl)
                                        .header("Referer", "https://trendy48.online/")
                                        .header("User-Agent", DESKTOP_UA)
                                        .build()
                                    cleanClient.newCall(req1).execute().use { res1 ->
                                        if (res1.isSuccessful) {
                                            val html1 = res1.body?.string() ?: ""
                                            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html1)?.groupValues?.get(1)
                                            if (iframeSrc != null) {
                                                val req2 = okhttp3.Request.Builder()
                                                    .url(iframeSrc)
                                                    .header("Referer", "https://trendy48.site/")
                                                    .header("User-Agent", DESKTOP_UA)
                                                    .build()
                                                cleanClient.newCall(req2).execute().use { res2 ->
                                                    val html2 = res2.body?.string() ?: ""
                                                    val gi2Match = Regex("""_gi2=\[([0-9, ]+)\]""").find(html2)
                                                    val jt4Match = Regex("""_jt4=(\d+)""").find(html2)
                                                    val hu9Match = Regex("""_hu9=(\d+)""").find(html2)
                                                    if (gi2Match != null && jt4Match != null && hu9Match != null) {
                                                        val gi2 = gi2Match.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
                                                        val jt4 = jt4Match.groupValues[1].toInt()
                                                        val hu9 = hu9Match.groupValues[1].toInt()
                                                        val decodedChars = gi2.map { ((((it xor jt4) - hu9 + 256) % 256).toChar()) }
                                                        val decodedStr = decodedChars.joinToString("")
                                                        val m3u8Match = Regex("""var SIGNED_URL\s*=\s*"([^"]+\.m3u8[^"]*)"""").find(decodedStr)
                                                        val streamUrl = m3u8Match?.groupValues?.get(1)
                                                        if (streamUrl != null) {
                                                            callback.invoke(
                                                                ExtractorLink(
                                                                    source = this@SportsurgeXRProvider.name,
                                                                    name = linkName,
                                                                    url = streamUrl,
                                                                    referer = "https://exmxbxe.cfd/",
                                                                    quality = quality,
                                                                    type = ExtractorLinkType.M3U8,
                                                                    headers = mapOf(
                                                                        "Referer" to "https://exmxbxe.cfd/",
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
                                } else if (targetUrl.contains("streame.center")) {
                                    val req1 = okhttp3.Request.Builder()
                                        .url(targetUrl)
                                        .header("Referer", "https://hitcast.st/")
                                        .header("User-Agent", DESKTOP_UA)
                                        .build()
                                    cleanClient.newCall(req1).execute().use { res1 ->
                                        if (res1.isSuccessful) {
                                            val html1 = res1.body?.string() ?: ""
                                            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html1)?.groupValues?.get(1)
                                            val hlsPageUrl = if (iframeSrc != null) {
                                                if (iframeSrc.startsWith("//")) "https:$iframeSrc"
                                                else if (iframeSrc.startsWith("http")) iframeSrc
                                                else "https://streame.center$iframeSrc"
                                            } else targetUrl

                                            val req2 = okhttp3.Request.Builder()
                                                .url(hlsPageUrl)
                                                .header("Referer", targetUrl)
                                                .header("User-Agent", DESKTOP_UA)
                                                .build()
                                            cleanClient.newCall(req2).execute().use { res2 ->
                                                if (res2.isSuccessful) {
                                                    val html2 = res2.body?.string() ?: ""
                                                    val m3u8Match = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html2)
                                                    val rawM3u8 = m3u8Match?.groupValues?.get(1)?.replace("\\u0026", "&")?.replace("\\/", "/")
                                                    if (rawM3u8 != null) {
                                                        callback.invoke(
                                                            ExtractorLink(
                                                                source = this@SportsurgeXRProvider.name,
                                                                name = linkName,
                                                                url = rawM3u8,
                                                                referer = "https://streame.center/",
                                                                quality = quality,
                                                                type = ExtractorLinkType.M3U8,
                                                                headers = mapOf(
                                                                    "Referer" to "https://streame.center/",
                                                                    "Origin" to "https://streame.center",
                                                                    "User-Agent" to DESKTOP_UA
                                                                )
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (targetUrl.contains("daddylive") || targetUrl.contains("daddy")) {
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
                                                            name = linkName,
                                                            url = decodedUrl,
                                                            referer = "https://hamis.romponalis.st/",
                                                            quality = quality,
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
                                                            name = linkName,
                                                            url = cleanUrl,
                                                            referer = "https://universaltokenforall.st/",
                                                            quality = quality,
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
                                                    val host = if (embedUrl.contains("/embed.php")) embedUrl.substringBefore("/embed.php") else "https://gerfred.com"
                                                    val code = embedUrl.substringAfter("code=", "")
                                                    if (code.isNotEmpty()) {
                                                        val configUrl = "$host/embed.php?code=$code&ppcfg=1"
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
                                                                            name = linkName,
                                                                            url = srcUrl,
                                                                            referer = "$host/",
                                                                            quality = quality,
                                                                            type = ExtractorLinkType.M3U8,
                                                                            headers = mapOf(
                                                                                "Referer" to "$host/",
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
                                            var m3u8Match = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)
                                            var m3u8Url = m3u8Match?.groupValues?.get(1)
                                            
                                            if (m3u8Url == null) {
                                                val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                                                if (iframeSrc != null && !iframeSrc.contains("ad.") && !iframeSrc.contains("histats")) {
                                                    val nextUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc"
                                                    else if (iframeSrc.startsWith("http")) iframeSrc
                                                    else targetUrl.substringBeforeLast("/") + "/" + iframeSrc
                                                    
                                                    val reqNext = okhttp3.Request.Builder()
                                                        .url(nextUrl)
                                                        .header("Referer", targetUrl)
                                                        .header("User-Agent", DESKTOP_UA)
                                                        .build()
                                                    cleanClient.newCall(reqNext).execute().use { resNext ->
                                                        if (resNext.isSuccessful) {
                                                            val nextHtml = resNext.body?.string() ?: ""
                                                            m3u8Match = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(nextHtml)
                                                            m3u8Url = m3u8Match?.groupValues?.get(1)
                                                        }
                                                    }
                                                }
                                            }
                                            
                                            if (m3u8Url != null) {
                                                val cleanM3u8 = m3u8Url.replace("\\u0026", "&").replace("\\/", "/")
                                                callback.invoke(
                                                    ExtractorLink(
                                                        source = this@SportsurgeXRProvider.name,
                                                        name = linkName,
                                                        url = cleanM3u8,
                                                        referer = targetUrl,
                                                        quality = quality,
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
