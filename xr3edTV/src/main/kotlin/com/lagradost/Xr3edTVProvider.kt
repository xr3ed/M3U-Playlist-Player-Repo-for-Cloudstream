package com.lagradost

import android.net.Uri
import android.util.Base64
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.xr3edTV.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ─── Data Structures ──────────────────────────────────────────────────────────

data class StreamServer(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val kodiProps: Map<String, String> = emptyMap()
)

data class Xr3edMatch(
    val id: String,
    val title: String,
    val sportCategory: String,
    val league: String,
    val kickOffTime: String,
    val durationHours: Double,
    val logo: String,
    val isLive: Boolean,
    val isUpcoming: Boolean,
    val isHot: Boolean = false,
    val sortOrder: Int = 999,
    val timestampMs: Long = 0L,
    val servers: List<StreamServer>,
    val homeTeam: String = "",
    val awayTeam: String = "",
    val homeLogo: String = "",
    val awayLogo: String = "",
    val matchDate: String = ""
)

data class SportHub(
    val key: String,
    val name: String,
    val poster: String,
    val description: String
)

data class ChannelItem(
    val id: String,
    val title: String,
    val logo: String,
    val group: String,
    val servers: List<StreamServer>
)

// ─── Main Provider ────────────────────────────────────────────────────────────

class Xr3edTVProvider : MainAPI() {
    companion object {
        val mapper = jacksonObjectMapper()

        val CLEARKEY_UUID: UUID = UUID.fromString("e2719d58-a985-b3c9-781a-b030e99c439d")
        val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        const val MASK_PREFIX = "https://lynk.id/xr3ed#"

        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val posterCache: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        val logoCache: MutableMap<String, android.graphics.Bitmap> = java.util.concurrent.ConcurrentHashMap()

        private var kltraCache: Pair<Long, List<Xr3edMatch>>? = null
        private var ondemandCache: Pair<Long, List<Xr3edMatch>>? = null
        private var channelCache: Pair<Long, Map<String, List<ChannelItem>>>? = null
        private var cachedXorKey: String = ""

        private const val LIVE_CACHE_TTL = 30_000L // 30s
        private const val CHANNEL_CACHE_TTL = 300_000L // 5m

        val SPORT_HUBS = listOf(
            SportHub("all", "Semua Pertandingan (Live & Today)", "https://images.unsplash.com/photo-1461896836934-ffe607ba8211?w=800&q=80", "Semua siaran langsung olahraga hari ini multi-server"),
            SportHub("soccer", "Sepak Bola (Soccer)", "https://images.unsplash.com/photo-1574629810360-7efbbe195018?w=800&q=80", "Premier League, Champions League, La Liga, Serie A, MLS, Liga 1"),
            SportHub("motorsport", "Motorsport & Rally (F1, MotoGP, Rally TV)", "https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?w=800&q=80", "Formula 1, MotoGP, WRC Rally, Rally TV 24/7, Superbike"),
            SportHub("combat", "UFC, Tinju & Combat Sports", "https://images.unsplash.com/photo-1549719386-74dfcbf7dbed?w=800&q=80", "UFC Fight Night, Boxing, MMA, WWE, AEW"),
            SportHub("badminton", "Bulu Tangkis (Badminton)", "https://images.unsplash.com/photo-1626224583764-f87db24ac4ea?w=800&q=80", "BWF World Tour, Thomas & Uber Cup, All England"),
            SportHub("basketball", "Bola Basket (Basketball)", "https://images.unsplash.com/photo-1546519638-68e109498ffc?w=800&q=80", "NBA, EuroLeague, FIBA"),
            SportHub("tennis", "Tenis (Tennis)", "https://images.unsplash.com/photo-1554068865-24cecd4e34b8?w=800&q=80", "ATP, WTA, Grand Slam, Australian Open, Wimbledon"),
            SportHub("table_tennis", "Tenis Meja (Table Tennis)", "https://images.unsplash.com/photo-1534158914592-062992fbe900?w=800&q=80", "WTT, ITTF World Tour"),
            SportHub("volleyball", "Bola Voli (Volleyball)", "https://images.unsplash.com/photo-1612872087720-bb876e2e67d1?w=800&q=80", "VNL, Proliga, CEV Champions League"),
            SportHub("baseball", "Bisbol (Baseball)", "https://images.unsplash.com/photo-1508344928928-7165b67de128?w=800&q=80", "MLB, NPB, KBO"),
            SportHub("billiards", "Biliar (Billiards)", "https://images.unsplash.com/photo-1579952363873-27f3bade9f55?w=800&q=80", "World Pool Championship, Snooker"),
            SportHub("golf", "Golf", "https://images.unsplash.com/photo-1535131749006-b7f58c99034b?w=800&q=80", "PGA Tour, LIV Golf, The Masters"),
            SportHub("cricket", "Kriket (Cricket)", "https://images.unsplash.com/photo-1531415074968-036ba1b575da?w=800&q=80", "IPL, T20 World Cup, The Ashes, Test Matches"),
            SportHub("rugby", "Rugbi & American Football", "https://images.unsplash.com/photo-1566577739112-5180d4bf9390?w=800&q=80", "AFL, NFL, CFL, Rugby Championship, NRL"),
            SportHub("hockey", "Hoki Es (Hockey)", "https://images.unsplash.com/photo-1580748141549-71748dbe0bdc?w=800&q=80", "NHL, IIHF World Championship")
        )
    }

    override var mainUrl = "https://lynk.id/xr3ed"
    override var name = "📺 xr3edTV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override var lang = "live"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = listOf(
        MainPageData("🔥 Hot Event", "HOT_EVENT", horizontalImages = true),
        MainPageData("🔴 Live Sports Hub (Pilih Cabang Olahraga)", "SPORTS_HUB", horizontalImages = true),
        MainPageData("⏳ Upcoming Event", "UPCOMING_EVENT", horizontalImages = true),
        MainPageData("🇮🇩 TV NASIONAL 24/7", "🇮🇩 NASIONAL"),
        MainPageData("⚽ TV SPORTS 24/7", "⚽ SPORTS"),
        MainPageData("🎬 MOVIES & ENTERTAINMENT", "🎬 MOVIES & ENTERTAINMENT"),
        MainPageData("👫 KIDS & ANIME", "👫 KIDS & ANIME"),
        MainPageData("🏆 LIGA CHAMPION", "🏆 LIGA CHAMPION"),
        MainPageData("⚽ LIGA INGGRIS", "⚽ LIGA INGGRIS"),
        MainPageData("⚽ LIGA SPANYOL", "⚽ LIGA SPANYOL"),
        MainPageData("⚽ LIGA ITALIA", "⚽ LIGA ITALIA"),
        MainPageData("⚽ LIGA JERMAN", "⚽ LIGA JERMAN"),
        MainPageData("🏎️ OTOMOTIF", "🏎️ OTOMOTIF"),
        MainPageData("📚 DOCUMENTARY & KNOWLEDGE", "📚 DOCUMENTARY & KNOWLEDGE"),
        MainPageData("🛰 NEWS & BUSINESS", "🛰 NEWS & BUSINESS"),
        MainPageData("☪️ ISLAM", "☪️ ISLAM"),
        MainPageData("✝️ KRISTEN", "✝️ KRISTEN"),
        MainPageData("🇲🇾 MALAYSIA", "🇲🇾 MALAYSIA"),
        MainPageData("🇰🇷 KOREA", "🇰🇷 KOREA"),
        MainPageData("🇨🇳 CHINA", "🇨🇳 CHINA"),
        MainPageData("🎵 MUSIC", "🎵 MUSIC")
    )

    // ─── Network & Crypto Helpers ─────────────────────────────────────────────

    private suspend fun httpGet(url: String, referer: String? = null, userAgent: String = DESKTOP_UA): String? = withContext(Dispatchers.IO) {
        try {
            val reqHeaders = mutableMapOf(
                "User-Agent" to userAgent,
                "Accept" to "*/*"
            )
            if (!referer.isNullOrEmpty()) {
                reqHeaders["Referer"] = referer
                reqHeaders["Origin"] = referer.trimEnd('/')
            }
            app.get(url, headers = reqHeaders, timeout = 15).text
        } catch (_: Exception) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "*/*")
                if (!referer.isNullOrEmpty()) {
                    reqBuilder.header("Referer", referer)
                    reqBuilder.header("Origin", referer.trimEnd('/'))
                }
                httpClient.newCall(reqBuilder.build()).execute().use { res ->
                    if (res.isSuccessful) res.body?.string() else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getEventHiddenId(uuidStr: String, salt: String = "xR7#kLt_vI9\$pZw2@mN5"): String {
        return try {
            val parts = uuidStr.split("-")
            if (parts.size < 5) return ""
            val s1 = if (salt.length >= 7) salt.substring(0, 7) else "xR7#kLt"
            val s2 = if (salt.length >= 20) salt.substring(12, 20) else "pZw2@mN5"
            val raw = parts[2] + s1 + parts[4] + s2 + parts[0]
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Exception) {
            ""
        }
    }

    private fun encryptMatchId(matchId: String, secret: String): String {
        return try {
            if (secret.isEmpty()) {
                return Base64.encodeToString(matchId.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).trimEnd('=')
            }
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
            val encrypted = cipher.doFinal(matchId.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).trimEnd('=')
        } catch (_: Exception) {
            Base64.encodeToString(matchId.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).trimEnd('=')
        }
    }

    private fun hexToBase64Url(str: String): String {
        val clean = str.replace(" ", "").trim()
        val isHex = clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } && clean.length % 2 == 0
        if (!isHex || clean.isEmpty()) return clean
        return try {
            val bytes = ByteArray(clean.length / 2)
            for (i in bytes.indices) {
                bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Exception) {
            clean
        }
    }

    private fun buildMatchPosterUrl(
        home: String,
        away: String,
        homeLogo: String = "",
        awayLogo: String = "",
        league: String = "",
        sport: String = "",
        isLive: Boolean = true,
        time: String = "",
        date: String = "",
        aspect: String = "landscape"
    ): String {
        val base = BuildConfig.XR3EDTV_POSTER_BASE.ifEmpty { "https://xr3edtv-poster.xr3ed-cdn.workers.dev/poster.png" }
        val status = if (isLive) "live" else "upcoming"
        val cleanTime = time.replace(" WIB", "").replace("Live", "").trim()
        return "$base?v=20&aspect=$aspect&home=${Uri.encode(home)}&away=${Uri.encode(away)}&home_logo=${Uri.encode(homeLogo)}&away_logo=${Uri.encode(awayLogo)}&league=${Uri.encode(league)}&sport=${Uri.encode(sport)}&status=$status&time=${Uri.encode(cleanTime)}&date=${Uri.encode(date)}"
    }

    private fun getMatchPoster(m: Xr3edMatch, aspect: String = "landscape"): String {
        if (m.homeTeam.isNotEmpty() && m.awayTeam.isNotEmpty()) {
            return buildMatchPosterUrl(
                home = m.homeTeam,
                away = m.awayTeam,
                homeLogo = m.homeLogo,
                awayLogo = m.awayLogo,
                league = m.league,
                sport = m.sportCategory,
                isLive = m.isLive,
                time = m.kickOffTime,
                date = m.matchDate,
                aspect = aspect
            )
        }
        return m.logo.ifEmpty { "https://kltraid.pages.dev/images/sportsicon/Other.png" }
    }

    // ─── Match & Sport Detection ──────────────────────────────────────────────

    private fun detectSportCategory(league: String, title: String, rawIconOrCat: String = ""): String {
        val icon = rawIconOrCat.lowercase()
        val text = "$league $title $rawIconOrCat".lowercase()

        // 1. Deteksi utama dari icon / category resmi API
        when {
            icon.contains("billiard") || icon.contains("snooker") -> return "billiards"
            icon.contains("basketball") -> return "basketball"
            icon.contains("baseball") -> return "baseball"
            icon.contains("combat") || icon.contains("fight") || icon.contains("boxing") || icon.contains("ufc") -> return "combat"
            icon.contains("motorsport") || icon.contains("racing") || icon.contains("rally") -> return "motorsport"
            icon.contains("table") || icon.contains("pingpong") || icon.contains("meja") -> return "table_tennis"
            icon.contains("tennis") && !icon.contains("table") && !icon.contains("meja") -> return "tennis"
            icon.contains("badminton") -> return "badminton"
            icon.contains("volleyball") -> return "volleyball"
            icon.contains("golf") -> return "golf"
            icon.contains("cricket") -> return "cricket"
            icon.contains("rugby") || icon.contains("afl") || (icon.contains("football") && (icon.contains("american") || icon.contains("cfl") || icon.contains("nfl"))) -> return "rugby"
            icon.contains("hockey") -> return "hockey"
            icon.contains("soccer") -> return "soccer"
        }

        // 2. Deteksi spesifik dari nama liga / judul
        return when {
            text.contains("billiard") || text.contains("pool") || text.contains("snooker") || text.contains("wuhan open") -> "billiards"
            text.contains("basketball") || text.contains("nba") || text.contains("euroleague") || text.contains("fiba") || text.contains("wnba") -> "basketball"
            text.contains("ufc") || text.contains("fight") || text.contains("boxing") || text.contains("combat") || text.contains("mma") || text.contains("wrestling") || text.contains("wwe") || text.contains("aew") || text.contains("one championship") || text.contains("bellator") -> "combat"
            text.contains("motorsport") || text.contains("racing") || text.contains("rally") || text.contains("wrc") || text.contains("f1") || text.contains("formula") || text.contains("motogp") || text.contains("nascar") || text.contains("moto2") || text.contains("superbike") || text.contains("mxgp") -> "motorsport"
            text.contains("badminton") || text.contains("bwf") || text.contains("all england") || text.contains("thomas cup") || text.contains("uber cup") -> "badminton"
            text.contains("table tennis") || text.contains("tenis meja") || text.contains("wtt") || text.contains("ping pong") || text.contains("ittf") -> "table_tennis"
            (text.contains("tennis") || text.contains("atp") || text.contains("wta") || text.contains("wimbledon") || text.contains("us open") || text.contains("french open") || text.contains("australian open")) && !text.contains("table") && !text.contains("meja") -> "tennis"
            text.contains("baseball") || text.contains("mlb") || text.contains("npb") || text.contains("kbo") -> "baseball"
            text.contains("volleyball") || text.contains("vnl") || text.contains("proliga") || text.contains("cev") -> "volleyball"
            text.contains("golf") || text.contains("pga") || text.contains("liv") || text.contains("masters") -> "golf"
            text.contains("cricket") || text.contains("ipl") || text.contains("t20") -> "cricket"
            text.contains("rugby") || text.contains("afl") || text.contains("nfl") || text.contains("cfl") || text.contains("american football") -> "rugby"
            text.contains("hockey") || text.contains("nhl") -> "hockey"
            text.contains("soccer") || text.contains("football") || text.contains("liga") || text.contains("cup") || text.contains("champions") || text.contains("premier") || text.contains("serie a") || text.contains("la liga") || text.contains("bundesliga") || text.contains("mls") || text.contains("afc") || text.contains("fifa") || text.contains("fc ") -> "soccer"
            else -> "soccer"
        }
    }

    private fun computeMatchStatus(dateStr: String?, timeStr: String?, durationHours: Double = 3.0): Triple<Boolean, Boolean, Long> {
        val now = System.currentTimeMillis()
        if (timeStr.isNullOrEmpty()) return Triple(true, false, now)
        return try {
            val tz = TimeZone.getTimeZone("Asia/Jakarta")
            val nowCal = Calendar.getInstance(tz)

            val fullDateStr = if (!dateStr.isNullOrEmpty()) "$dateStr $timeStr" else {
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
                "${sdfDate.format(nowCal.time)} $timeStr"
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }
            val matchDate = sdf.parse(fullDateStr) ?: return Triple(true, false, now)

            val matchTimeMs = matchDate.time
            val durationMs = (durationHours * 3600 * 1000).toLong()
            val endTimeMs = matchTimeMs + durationMs
            val nowMs = nowCal.timeInMillis

            val isLive = nowMs in matchTimeMs until endTimeMs
            val isUpcoming = nowMs < matchTimeMs
            Triple(isLive, isUpcoming, matchTimeMs)
        } catch (_: Exception) {
            Triple(true, false, now)
        }
    }

    private fun xorDecrypt(encryptedBase64: String, key: String): String {
        if (key.isEmpty() || encryptedBase64.isEmpty()) return encryptedBase64
        return try {
            val rawData = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val keyLen = keyBytes.size
            val decrypted = ByteArray(rawData.size)
            for (i in rawData.indices) {
                decrypted[i] = (rawData[i].toInt() xor keyBytes[i % keyLen].toInt()).toByte()
            }
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            encryptedBase64
        }
    }

    private suspend fun getDynamicXorKey(): String {
        return try {
            val js = httpGet("https://kltraid.pages.dev/js/testa.js") ?: return ""
            val kPattern = Regex("""const\s+__K_[a-zA-Z0-9]+\s*=\s*\[([0-9,\s]+)\];""")
            val sPattern = Regex("""const\s+__S_[a-zA-Z0-9]+\s*=\s*\[([^\]]+)\];""")
            val kMatch = kPattern.find(js)
            val sMatch = sPattern.find(js)
            if (kMatch != null && sMatch != null) {
                val kArr = kMatch.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
                val sMatches = Regex(""""([^"]+)"""").findAll(sMatch.groupValues[1]).map { it.groupValues[1] }.toList()
                if (sMatches.size > 2) {
                    val rawB = Base64.decode(sMatches[2], Base64.DEFAULT)
                    val n = 2
                    val resBytes = ByteArray(rawB.size)
                    for (i in rawB.indices) {
                        resBytes[i] = (rawB[i].toInt() xor kArr[(i + n) % kArr.size] xor ((n * 31 + i * 17) and 255)).toByte()
                    }
                    return String(resBytes, Charsets.UTF_8)
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    // ─── Engine 1: Kltra Realtime Fetcher ──────────────────────────────────────

    private suspend fun fetchKltraMatches(): List<Xr3edMatch> {
        val now = System.currentTimeMillis()
        kltraCache?.let { (ts, data) ->
            if (now - ts < LIVE_CACHE_TTL) return data
        }

        val apiBase = BuildConfig.XR3EDTV_API_BASE.trimEnd('/').ifEmpty { "https://apiweb.filmmania.click" }
        val saltKey = BuildConfig.XR3EDTV_SALT_KEY.ifEmpty { "xR7#kLt_vI9\$pZw2@mN5" }
        var xorKey = BuildConfig.XR3EDTV_XOR_KEY.ifEmpty {
            if (cachedXorKey.isEmpty()) {
                cachedXorKey = getDynamicXorKey()
            }
            cachedXorKey
        }

        val results = mutableListOf<Xr3edMatch>()
        try {
            val ts = System.currentTimeMillis()
            val eventsRaw = httpGet("$apiBase/vip/eventweb.json?v=$ts") ?: return emptyList()
            val playersRaw = httpGet("$apiBase/vip/sdplayer.json?v=$ts") ?: "{}"

            var decEvents = if (eventsRaw.trim().startsWith("[")) eventsRaw else xorDecrypt(eventsRaw, xorKey)
            var decPlayers = if (playersRaw.trim().startsWith("[")) playersRaw else xorDecrypt(playersRaw, xorKey)

            if (!decEvents.trim().startsWith("[")) {
                val dyn = getDynamicXorKey()
                if (dyn.isNotEmpty() && dyn != xorKey) {
                    xorKey = dyn
                    cachedXorKey = dyn
                    decEvents = xorDecrypt(eventsRaw, xorKey)
                    decPlayers = xorDecrypt(playersRaw, xorKey)
                }
            }

            val eventsNode = try { mapper.readTree(decEvents) } catch (_: Exception) { null }
            val playersNode = try { mapper.readTree(decPlayers) } catch (_: Exception) { null }

            val playerMap = mutableMapOf<String, List<StreamServer>>()
            if (playersNode != null && playersNode.isArray) {
                for (p in playersNode) {
                    val pKey = p.get("r")?.asText() ?: p.get("id")?.asText() ?: continue
                    val serversNode = p.get("servers")
                    val serverList = mutableListOf<StreamServer>()
                    if (serversNode != null && serversNode.isArray) {
                        for ((idx, s) in serversNode.withIndex()) {
                            var url = s.get("url")?.asText()?.trim() ?: continue
                            if (url.isEmpty() || url.startsWith("javascript:")) continue
                            if (url.contains("liveUrl=")) {
                                try {
                                    val parsed = Uri.parse(url)
                                    val liveParam = parsed.getQueryParameter("liveUrl")
                                    if (!liveParam.isNullOrEmpty()) {
                                        url = liveParam
                                    }
                                } catch (_: Exception) {}
                            }
                            val rawLabel = s.get("label")?.asText() ?: s.get("name")?.asText() ?: "Server ${idx + 1}"
                            val sName = when {
                                rawLabel.contains("W", ignoreCase = true) -> "Server ${idx + 1} (SD)"
                                rawLabel.contains("Vivo", ignoreCase = true) -> "Server ${idx + 1} (SD Vivo)"
                                rawLabel.contains("Yalla", ignoreCase = true) -> "Server ${idx + 1} (SD Yalla)"
                                else -> "Server ${idx + 1} ($rawLabel)"
                            }
                            val kodiProps = mutableMapOf<String, String>()
                            s.get("key")?.asText()?.trim()?.let { k ->
                                if (k.isNotEmpty() && (k.contains(":") || (k.length == 32 && k.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }))) {
                                    kodiProps["inputstream.adaptive.license_type"] = "clearkey"
                                    kodiProps["inputstream.adaptive.license_key"] = k
                                }
                            }
                            serverList.add(StreamServer(sName, url, mapOf("User-Agent" to DESKTOP_UA), kodiProps))
                        }
                    }
                    if (serverList.isNotEmpty()) {
                        playerMap[pKey] = serverList
                    }
                }
            }

            if (eventsNode != null && eventsNode.isArray) {
                for (ev in eventsNode) {
                    val evId = ev.get("id")?.asText() ?: ""
                    val rKey = ev.get("r")?.asText() ?: evId
                    var servers = playerMap[rKey]
                    if (servers.isNullOrEmpty() && saltKey.isNotEmpty() && evId.isNotEmpty()) {
                        val hiddenId = getEventHiddenId(evId, saltKey)
                        servers = playerMap[hiddenId]
                    }
                    if (servers.isNullOrEmpty()) continue

                    val league = ev.get("league")?.asText()?.trim() ?: "Sports Event"
                    val t1 = ev.get("team1")?.get("name")?.asText()?.trim() ?: ""
                    val t2 = ev.get("team2")?.get("name")?.asText()?.trim() ?: ""
                    val title = if (t1.isNotEmpty() && t2.isNotEmpty()) {
                        "$t1 vs $t2"
                    } else if (league.isNotEmpty() && league != "Sports Event") {
                        league
                    } else {
                        ev.get("name")?.asText() ?: if (t1.isNotEmpty()) t1 else "Match"
                    }
                    val logo = ev.get("team1")?.get("logo")?.asText() ?: ev.get("team2")?.get("logo")?.asText() ?: ev.get("icon")?.asText() ?: ""

                    val kickDate = ev.get("kickoff_date")?.asText() ?: ev.get("match_date")?.asText() ?: ""
                    val kickTime = ev.get("kickoff_time")?.asText() ?: ev.get("match_time")?.asText() ?: ""
                    val duration = ev.get("duration")?.asDouble() ?: 3.0

                    val rawIcon = ev.get("icon")?.asText() ?: ""
                    val rawFirstRow = ev.get("firstRow")?.asInt() ?: 999
                    val isHot = rawIcon.contains("main_", ignoreCase = true) || rawFirstRow <= 120 || rawFirstRow <= 10

                    val (isLive, isUpcoming, matchTimeMs) = computeMatchStatus(kickDate, kickTime, duration)
                    val cat = detectSportCategory(league, title, rawIcon)

                    results.add(Xr3edMatch(
                        id = "kltra_${if (evId.isNotEmpty()) evId else rKey}",
                        title = title,
                        sportCategory = cat,
                        league = league,
                        kickOffTime = if (kickTime.isNotEmpty()) "$kickTime WIB" else "Live",
                        durationHours = duration,
                        logo = logo,
                        isLive = isLive,
                        isUpcoming = isUpcoming,
                        isHot = isHot,
                        sortOrder = rawFirstRow,
                        timestampMs = matchTimeMs,
                        servers = servers,
                        homeTeam = t1,
                        awayTeam = t2,
                        homeLogo = ev.get("team1")?.get("logo")?.asText()?.trim() ?: "",
                        awayLogo = ev.get("team2")?.get("logo")?.asText()?.trim() ?: "",
                        matchDate = kickDate
                    ))
                }
            }

            kltraCache = Pair(now, results)
        } catch (_: Exception) {}
        return results
    }

    // ─── Engine 2: OnDemand Realtime Fetcher ───────────────────────────────────

    private suspend fun fetchOnDemandMatches(): List<Xr3edMatch> {
        val now = System.currentTimeMillis()
        ondemandCache?.let { (ts, data) ->
            if (now - ts < LIVE_CACHE_TTL) return data
        }

        var ondemandApi = BuildConfig.XR3EDTV_ONDEMAND_API.trim()
        if (ondemandApi.endsWith("/schedule") || ondemandApi.isEmpty()) {
            ondemandApi = "https://ondemand.st/papi/matches/all"
        }
        val ondemandReferer = BuildConfig.XR3EDTV_ONDEMAND_REFERER.trim().ifEmpty { "https://damitv.st/" }
        val workerBase = BuildConfig.WORKER_BASE_URL.trimEnd('/').ifEmpty { "https://stream-cdn-box.xr3ed-edge.workers.dev" }
        val workerKey = BuildConfig.WORKER_AUTH_KEY.trim().ifEmpty { "kltra-auth-secret-2024" }

        val results = mutableListOf<Xr3edMatch>()
        try {
            val jsonStr = httpGet(ondemandApi, referer = ondemandReferer) ?: return emptyList()
            val rootNode = mapper.readTree(jsonStr)

            val matchesNode = if (rootNode.isArray) rootNode else rootNode.get("matches") ?: rootNode.get("data")
            if (matchesNode != null && matchesNode.isArray) {
                val tz = TimeZone.getTimeZone("Asia/Jakarta")
                val sdfTime = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }

                for (m in matchesNode) {
                    val mid = m.get("id")?.asText() ?: m.get("match_id")?.asText() ?: continue
                    val league = m.get("league")?.asText()?.trim() ?: "Live Sports"
                    
                    val homeName = m.get("teams")?.get("home")?.get("name")?.asText()?.trim() ?: ""
                    val awayName = m.get("teams")?.get("away")?.get("name")?.asText()?.trim() ?: ""
                    val title = if (homeName.isNotEmpty() && awayName.isNotEmpty()) {
                        "$homeName vs $awayName"
                    } else {
                        m.get("title")?.asText()?.trim() ?: m.get("name")?.asText()?.trim() ?: "Sports Match"
                    }

                    val logo = m.get("poster")?.asText() 
                        ?: m.get("teams")?.get("home")?.get("badge")?.asText() 
                        ?: m.get("ppvPoster")?.asText() ?: ""

                    val rawCat = m.get("category")?.asText()?.lowercase() ?: ""
                    val is247 = rawCat.contains("24/7") || league.contains("24/7", ignoreCase = true) || title.contains("24/7", ignoreCase = true)
                    val isRally = title.contains("rally", ignoreCase = true) || league.contains("rally", ignoreCase = true)

                    // Sembunyikan/Hapus semua siaran 24/7 non-olahraga, sisakan hanya Rally TV
                    if (is247 && !isRally) continue

                    val status = m.get("status")?.asText()?.lowercase() ?: "upcoming"
                    val dateMs = m.get("date")?.asLong() ?: 0L
                    val nowMs = System.currentTimeMillis()

                    val isLiveStatus = status == "live"
                    val isUpcomingStatus = status == "upcoming" || (!isLiveStatus && dateMs > nowMs)

                    // Jika bukan live dan bukan upcoming (misal sudah selesai), lewati
                    if (!isLiveStatus && !isUpcomingStatus) continue

                    val kickOffStr = if (isRally) {
                        "24/7 Live"
                    } else if (isLiveStatus && dateMs > 0) {
                        "LIVE " + sdfTime.format(Date(dateMs)) + " WIB"
                    } else if (isLiveStatus) {
                        "Live Sekarang"
                    } else if (dateMs > 0) {
                        sdfTime.format(Date(dateMs)) + " WIB"
                    } else {
                        "Jadwal N/A"
                    }

                    val servers = mutableListOf<StreamServer>()
                    val odHeaders = mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Referer" to "https://damitv.st/"
                    )

                    // 1. Primary Worker HLS stream
                    val encPrimary = encryptMatchId(mid, workerKey)
                    val primaryUrl = "$workerBase/live/$encPrimary.m3u8"
                    servers.add(StreamServer("Server 1 (Worker HLS)", primaryUrl, odHeaders))

                    // 2. Substreams
                    val substreamsNode = m.get("substreams")
                    if (substreamsNode != null && substreamsNode.isArray) {
                        for ((idx, sub) in substreamsNode.withIndex()) {
                            val subId = sub.get("id")?.asText() ?: continue
                            val subName = sub.get("name")?.asText() ?: "Alt Stream ${idx + 2}"
                            val subLocale = sub.get("locale")?.asText() ?: ""
                            val label = if (subLocale.isNotEmpty()) "Server ${servers.size + 1} ($subName ${subLocale.uppercase()})" else "Server ${servers.size + 1} ($subName)"
                            val encSub = encryptMatchId(subId, workerKey)
                            servers.add(StreamServer(label, "$workerBase/live/$encSub.m3u8", odHeaders))
                        }
                    }

                    // 3. TV Channels
                    val tvChannelsNode = m.get("tvChannels")
                    if (tvChannelsNode != null && tvChannelsNode.isArray) {
                        for ((idx, tv) in tvChannelsNode.withIndex()) {
                            val tvId = tv.get("id")?.asText()?.replace("dlhd-", "") ?: continue
                            val tvName = tv.get("name")?.asText() ?: "TV Channel"
                            val encTv = encryptMatchId(tvId, workerKey)
                            val srvName = "Server ${servers.size + 1} ($tvName HD)"
                            servers.add(StreamServer(srvName, "$workerBase/live/$encTv.m3u8", odHeaders))
                        }
                    }

                    val cat = if (isRally) "motorsport" else detectSportCategory(league, title, rawCat)
                    val cleanTitle = if (isRally) "Rally TV" else title

                    val isPopular = m.get("popular")?.asBoolean() ?: false
                    val viewers = m.get("viewers")?.asInt() ?: 0
                    val isHot = isPopular || viewers > 500

                    val odDateStr = if (dateMs > 0) {
                        val sdfDate = SimpleDateFormat("dd MMMM yyyy", Locale.US).apply { timeZone = tz }
                        sdfDate.format(Date(dateMs))
                    } else "Hari Ini"

                    results.add(Xr3edMatch(
                        id = "od_$mid",
                        title = cleanTitle,
                        sportCategory = cat,
                        league = if (isRally) "WRC Rally" else league,
                        kickOffTime = kickOffStr,
                        durationHours = 3.0,
                        logo = logo,
                        isLive = isLiveStatus,
                        isUpcoming = isUpcomingStatus,
                        isHot = isHot,
                        sortOrder = 1000 + results.size,
                        timestampMs = dateMs,
                        servers = servers,
                        homeTeam = homeName,
                        awayTeam = awayName,
                        homeLogo = m.get("teams")?.get("home")?.get("badge")?.asText()?.trim() ?: "",
                        awayLogo = m.get("teams")?.get("away")?.get("badge")?.asText()?.trim() ?: "",
                        matchDate = odDateStr
                    ))
                }
            }

            ondemandCache = Pair(now, results)
        } catch (_: Exception) {}
        return results
    }

    private fun normalizeName(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(" fc", "")
            .replace(" sc", "")
            .replace(" cf", "")
            .replace(" utd", " united")
            .replace(" city", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun nameTokens(s: String): Set<String> {
        return normalizeName(s).split(" ").filter { it.length > 2 }.toSet()
    }

    private fun isSameMatch(m1: Xr3edMatch, m2: Xr3edMatch): Boolean {
        val tok1 = nameTokens(m1.title)
        val tok2 = nameTokens(m2.title)
        if (tok1.isEmpty() || tok2.isEmpty()) return false

        val intersect = tok1.intersect(tok2).size
        val minSize = minOf(tok1.size, tok2.size)
        if (minSize == 0) return false

        return (intersect.toDouble() / minSize.toDouble()) >= 0.5
    }

    private suspend fun fetchMergedMatches(): List<Xr3edMatch> = coroutineScope {
        val kltra = async { fetchKltraMatches() }
        val od = async { fetchOnDemandMatches() }
        val kltraMatches = kltra.await()
        val onDemandMatches = od.await()

        val mergedResults = mutableListOf<Xr3edMatch>()
        val usedOnDemandIds = mutableSetOf<String>()

        for (km in kltraMatches) {
            val matchedOd = onDemandMatches.firstOrNull { odMatch ->
                !usedOnDemandIds.contains(odMatch.id) && isSameMatch(km, odMatch)
            }

            if (matchedOd != null) {
                usedOnDemandIds.add(matchedOd.id)
                val combinedServers = km.servers.toMutableList()
                matchedOd.servers.forEachIndexed { idx, srv ->
                    val serverName = if (srv.name.contains("Worker") || srv.name.contains("Server 1")) {
                        "Server ${combinedServers.size + 1} (OnDemand Backup)"
                    } else {
                        "Server ${combinedServers.size + 1} (${srv.name.substringAfter("Server ")})"
                    }
                    combinedServers.add(srv.copy(name = serverName))
                }
                mergedResults.add(km.copy(
                    servers = combinedServers,
                    isHot = km.isHot || matchedOd.isHot,
                    logo = km.logo.ifEmpty { matchedOd.logo },
                    homeLogo = km.homeLogo.ifEmpty { matchedOd.homeLogo },
                    awayLogo = km.awayLogo.ifEmpty { matchedOd.awayLogo }
                ))
            } else {
                mergedResults.add(km)
            }
        }

        for (od in onDemandMatches) {
            if (!usedOnDemandIds.contains(od.id)) {
                mergedResults.add(od)
            }
        }

        mergedResults.sortedBy { it.sortOrder }
    }

    // ─── Engine 3: DekoTech Realtime 24/7 Channels ────────────────────────────

    private suspend fun fetch247Channels(): Map<String, List<ChannelItem>> {
        val now = System.currentTimeMillis()
        channelCache?.let { (ts, data) ->
            if (now - ts < CHANNEL_CACHE_TTL && data.isNotEmpty()) return data
        }

        val sourceUrl = BuildConfig.NASIONAL_SOURCE_URL.trim().ifEmpty { "https://raw.githubusercontent.com/xr3ed/xr3ed-tv/main/xr3dtv.m3u8" }
        var content = httpGet(sourceUrl)
        if (content.isNullOrEmpty() || !content.contains("#EXTINF")) {
            content = httpGet("https://raw.githubusercontent.com/xr3ed/xr3ed-tv/main/xr3dtv.m3u8")
        }
        if (content.isNullOrEmpty() || !content.contains("#EXTINF")) return emptyMap()

        val categoryMap = mutableMapOf<String, MutableList<ChannelItem>>()
        try {
            var currentTitle: String? = null
            var currentLogo: String = ""
            var currentGroup: String = "🇮🇩 NASIONAL"
            var currentHeaders = mutableMapOf<String, String>()
            var currentKodiProps = mutableMapOf<String, String>()

            content.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isNotEmpty()) {
                    when {
                        line.startsWith("#EXTINF:", ignoreCase = true) -> {
                            val logoMatch = Regex("""tvg-logo="([^"]*)"""").find(line)
                            currentLogo = logoMatch?.groupValues?.get(1) ?: ""

                            val groupMatch = Regex("""group-title="([^"]*)"""").find(line)
                            currentGroup = groupMatch?.groupValues?.get(1)?.trim() ?: "🇮🇩 NASIONAL"

                            val titleMatch = Regex("""tvg-name="([^"]*)"""").find(line)
                            currentTitle = titleMatch?.groupValues?.get(1) ?: line.substringAfterLast(",").trim()
                        }
                        line.startsWith("#EXTVLCOPT:") -> {
                            val lower = line.lowercase()
                            if (lower.contains("http-user-agent=")) {
                                currentHeaders["User-Agent"] = line.substringAfter("http-user-agent=").trim()
                            } else if (lower.contains("http-referrer=")) {
                                currentHeaders["Referer"] = line.substringAfter("http-referrer=").trim()
                            }
                        }
                        line.startsWith("#KODIPROP:") -> {
                            val prop = line.substringAfter("#KODIPROP:").trim()
                            val parts = prop.split("=", limit = 2)
                            if (parts.size == 2) {
                                currentKodiProps[parts[0].trim()] = parts[1].trim()
                            }
                        }
                        !line.startsWith("#") && !line.startsWith("//") -> {
                            val rawTitle = currentTitle
                            if (!rawTitle.isNullOrEmpty() && (line.startsWith("http://") || line.startsWith("https://"))) {
                                val srvMatch = Regex("""\s*-\s*(Server\s*\d+(?:\s*\([^)]*\))?)\s*""", RegexOption.IGNORE_CASE).find(rawTitle)
                                val cleanName = if (srvMatch != null) rawTitle.removeRange(srvMatch.range).trim() else rawTitle.trim()
                                val srvName = srvMatch?.groupValues?.get(1)?.trim() ?: "Server 1"

                                val list = categoryMap.getOrPut(currentGroup) { mutableListOf() }
                                val existingItem = list.find { it.title.equals(cleanName, ignoreCase = true) }

                                val srv = StreamServer(srvName, line, currentHeaders.toMap(), currentKodiProps.toMap())
                                if (existingItem != null) {
                                    val updatedServers = existingItem.servers + srv
                                    val idx = list.indexOf(existingItem)
                                    list[idx] = existingItem.copy(servers = updatedServers)
                                } else {
                                    list.add(ChannelItem(
                                        id = "ch_${cleanName.hashCode()}",
                                        title = cleanName,
                                        logo = currentLogo,
                                        group = currentGroup,
                                        servers = listOf(srv)
                                    ))
                                }
                            }
                            currentTitle = null
                            currentLogo = ""
                            currentHeaders.clear()
                            currentKodiProps.clear()
                        }
                    }
                }
            }

            channelCache = Pair(now, categoryMap)
        } catch (_: Exception) {}
        return categoryMap
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val reqTag = (if (request.data.isNotBlank()) request.data else request.name).trim()
        val lowerTag = "${request.data} ${request.name}".lowercase()

        // 1. Hot Event (Hanya Pertandingan yang SEDANG LIVE Saat Ini)
        if (lowerTag.contains("hot")) {
            val matches = fetchMergedMatches()
            val hotMatches = matches.filter { it.isLive && it.isHot && !it.title.contains("Rally TV", ignoreCase = true) }
                .ifEmpty { matches.filter { it.isLive && !it.title.contains("Rally TV", ignoreCase = true) } }
                .sortedByDescending { if (it.timestampMs > 0) it.timestampMs else 0L }

            val directCards = hotMatches.map { m ->
                val matchPayload = mapper.writeValueAsString(m)
                val maskedData = "${MASK_PREFIX}direct::" + Base64.encodeToString(matchPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                newLiveSearchResponse(m.title, maskedData, TvType.Live) {
                    this.posterUrl = getMatchPoster(m, "landscape")
                }
            }
            return newHomePageResponse(HomePageList(request.name, directCards, isHorizontalImages = true), hasNext = false)
        }

        // 2. Live Sports Hub (Pilih Cabang Olahraga - Hub Utama)
        if (reqTag == "SPORTS_HUB" || reqTag == "LIVE_EVENT" || lowerTag.contains("sports hub") || lowerTag.contains("cabang olahraga") || lowerTag.contains("live event")) {
            val matches = fetchMergedMatches()
            val activeHubs = SPORT_HUBS.filter { hub ->
                if (hub.key == "all") matches.isNotEmpty()
                else matches.any { it.sportCategory.equals(hub.key, ignoreCase = true) }
            }
            val hubCards = activeHubs.map { hub ->
                val maskedUrl = "${MASK_PREFIX}hub::${hub.key}"
                newTvSeriesSearchResponse(hub.name, maskedUrl, TvType.TvSeries) {
                    this.posterUrl = hub.poster
                }
            }
            return newHomePageResponse(HomePageList(request.name, hubCards, isHorizontalImages = true), hasNext = false)
        }

        // 3. Upcoming Event (Jadwal Pertandingan Berikutnya)
        if (lowerTag.contains("upcoming")) {
            val matches = fetchMergedMatches()
            val upcomingMatches = matches.filter { it.isUpcoming }
                .sortedBy { if (it.timestampMs > 0) it.timestampMs else Long.MAX_VALUE }
            val directCards = upcomingMatches.map { m ->
                val matchPayload = mapper.writeValueAsString(m)
                val maskedData = "${MASK_PREFIX}direct::" + Base64.encodeToString(matchPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                newLiveSearchResponse("${m.kickOffTime} • ${m.title}", maskedData, TvType.Live) {
                    this.posterUrl = getMatchPoster(m, "landscape")
                }
            }
            return newHomePageResponse(HomePageList(request.name, directCards, isHorizontalImages = true), hasNext = false)
        }

        // 5. Specific Sport Category (Badminton, Soccer, Cricket, Combat, etc.)
        val sportMatch = SPORT_HUBS.find { hub ->
            if (hub.key == "all") {
                lowerTag == "all" || lowerTag.contains("semua pertandingan")
            } else {
                lowerTag == hub.key || lowerTag.contains(hub.name.lowercase()) ||
                (hub.key == "combat" && (lowerTag.contains("fight") || lowerTag.contains("combat") || lowerTag.contains("boxing") || lowerTag.contains("ufc"))) ||
                (hub.key == "soccer" && (lowerTag.contains("soccer") || lowerTag.contains("sepak bola") || lowerTag.contains("football"))) ||
                (hub.key == "motorsport" && (lowerTag.contains("racing") || lowerTag.contains("motor") || lowerTag.contains("f1") || lowerTag.contains("rally"))) ||
                (hub.key == "basketball" && (lowerTag.contains("basket") || lowerTag.contains("nba"))) ||
                (hub.key == "badminton" && (lowerTag.contains("badminton") || lowerTag.contains("bulu tangkis") || lowerTag.contains("bwf"))) ||
                (hub.key == "tennis" && (lowerTag.contains("tennis") || lowerTag.contains("tenis")) && !lowerTag.contains("meja") && !lowerTag.contains("table"))
            }
        }

        if (sportMatch != null) {
            val matches = fetchMergedMatches()
            val categoryMatches = if (sportMatch.key == "all") {
                matches.filter { !it.title.contains("Rally TV", ignoreCase = true) }
            } else {
                matches.filter { it.sportCategory.equals(sportMatch.key, ignoreCase = true) }
            }
            val directCards = categoryMatches.map { m ->
                val matchPayload = mapper.writeValueAsString(m)
                val maskedData = "${MASK_PREFIX}direct::" + Base64.encodeToString(matchPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val titleWithBadge = if (m.isUpcoming) "${m.kickOffTime} • ${m.title}" else m.title
                newLiveSearchResponse(titleWithBadge, maskedData, TvType.Live) {
                    this.posterUrl = getMatchPoster(m, "landscape")
                }
            }
            return newHomePageResponse(HomePageList(request.name, directCards, isHorizontalImages = true), hasNext = false)
        }

        // 6. 24/7 Linear TV Channels
        val channelsMap = fetch247Channels()
        val channels = channelsMap[reqTag]
            ?: channelsMap.entries.find { 
                it.key.contains(reqTag, ignoreCase = true) || 
                reqTag.contains(it.key, ignoreCase = true) ||
                lowerTag.contains(it.key.lowercase().replace(Regex("[^a-z0-9]"), ""))
            }?.value
            ?: emptyList()

        val searchResponses = channels.map { ch ->
            val payloadJson = mapper.writeValueAsString(ch)
            val maskedData = "${MASK_PREFIX}ch::" + Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            newLiveSearchResponse(ch.title, maskedData, TvType.Live) {
                this.posterUrl = ch.logo.ifEmpty { "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/live_icon.png" }
            }
        }

        return newHomePageResponse(request.name, searchResponses, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.lowercase().trim()
        val results = mutableListOf<SearchResponse>()

        // Search Sport Hubs
        SPORT_HUBS.filter { it.name.lowercase().contains(cleanQuery) }.forEach { hub ->
            val maskedUrl = "${MASK_PREFIX}hub::${hub.key}"
            results.add(newTvSeriesSearchResponse(hub.name, maskedUrl, TvType.TvSeries) {
                this.posterUrl = hub.poster
            })
        }

        // Search Matches
        val allMatches = fetchMergedMatches()
        allMatches.filter { it.title.lowercase().contains(cleanQuery) || it.league.lowercase().contains(cleanQuery) }.forEach { m ->
            val matchPayload = mapper.writeValueAsString(m)
            val maskedData = "${MASK_PREFIX}direct::" + Base64.encodeToString(matchPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            results.add(newLiveSearchResponse(m.title, maskedData, TvType.Live) {
                this.posterUrl = getMatchPoster(m, "landscape")
            })
        }

        // Search 24/7 Channels
        val channelsMap = fetch247Channels()
        channelsMap.values.flatten().filter { it.title.lowercase().contains(cleanQuery) }.forEach { ch ->
            val payloadJson = mapper.writeValueAsString(ch)
            val maskedData = "${MASK_PREFIX}ch::" + Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            results.add(newLiveSearchResponse(ch.title, maskedData, TvType.Live) {
                this.posterUrl = ch.logo.ifEmpty { "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/live_icon.png" }
            })
        }

        return results
    }

    // ─── Details & Link Resolution ────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = if (url.contains("lynk.id")) url.substringAfterLast("#", "") else url

        // ── 1. Direct Match Clicked (Dari Kategori HOT EVENT / Search / Rows) ──
        if (cleanUrl.startsWith("direct::")) {
            val jsonBase64 = cleanUrl.substringAfter("direct::")
            val match: Xr3edMatch = try {
                val json = String(Base64.decode(jsonBase64, Base64.DEFAULT), Charsets.UTF_8)
                mapper.readValue(json)
            } catch (_: Exception) {
                Xr3edMatch("direct", "Live Match", "soccer", "Sports", "Live", 3.0, "", true, false, false, 999, 0L, emptyList())
            }

            val serversPayload = mapper.writeValueAsString(match.servers)
            val epData = "${MASK_PREFIX}match::" + Base64.encodeToString(serversPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val episodes = listOf(
                newEpisode(epData) {
                    this.name = match.title
                    this.season = 1
                    this.episode = 1
                    this.description = "Liga: ${match.league} | Jadwal: ${match.kickOffTime} | Tersedia ${match.servers.size} Server Pilihan."
                }
            )

            val statusText = if (match.isLive) "🔴 SEDANG BERLANGSUNG (LIVE)" else "⏳ JADWAL (${match.kickOffTime})"

            return newTvSeriesLoadResponse(match.title, url, TvType.Live, episodes) {
                this.posterUrl = getMatchPoster(match, "portrait")
                this.plot = "Status: $statusText\nLiga: ${match.league} | Jadwal: ${match.kickOffTime} | Tersedia ${match.servers.size} Server Pilihan."
                this.seasonNames = listOf(SeasonData(1, "📡 Siaran Langsung (${match.servers.size} Server)"))
            }
        }

        // ── 2. Cabang Olahraga Hub (Sport Category Clicked) ──
        if (cleanUrl.startsWith("hub::")) {
            val sportKey = cleanUrl.substringAfter("hub::")
            val hubInfo = SPORT_HUBS.find { it.key == sportKey } ?: SPORT_HUBS.first()

            val allMatches = fetchMergedMatches()

            val targetMatches = if (sportKey == "all") {
                allMatches.filter { !it.title.contains("Rally TV", ignoreCase = true) }
            } else {
                allMatches.filter { it.sportCategory.equals(sportKey, ignoreCase = true) }
            }

            val liveMatches = targetMatches.filter { it.isLive }
                .sortedByDescending { if (it.timestampMs > 0) it.timestampMs else 0L }

            val upcomingMatches = targetMatches.filter { it.isUpcoming }
                .sortedBy { if (it.timestampMs > 0) it.timestampMs else Long.MAX_VALUE }

            val episodes = mutableListOf<Episode>()

            // Season 1: LIVE (Pertandingan Sedang Berlangsung - Paling Baru di Nomor 1)
            liveMatches.forEachIndexed { idx, m ->
                val serversPayload = mapper.writeValueAsString(m.servers)
                val epData = "${MASK_PREFIX}match::" + Base64.encodeToString(serversPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

                episodes.add(newEpisode(epData) {
                    this.name = "${m.kickOffTime} • ${m.title}"
                    this.season = 1
                    this.episode = idx + 1
                    this.posterUrl = getMatchPoster(m, "landscape")
                    this.description = "Liga: ${m.league} | Jadwal: ${m.kickOffTime} | Status: LIVE | Tersedia ${m.servers.size} Server"
                })
            }

            // Season 2: UPCOMING (Jadwal Paling Terdekat Jadi Nomor 1, 2, 3...)
            upcomingMatches.forEachIndexed { idx, m ->
                val serversPayload = mapper.writeValueAsString(m.servers)
                val epData = "${MASK_PREFIX}match::" + Base64.encodeToString(serversPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

                episodes.add(newEpisode(epData) {
                    this.name = "${m.kickOffTime} • ${m.title}"
                    this.season = 2
                    this.episode = idx + 1
                    this.posterUrl = getMatchPoster(m, "landscape")
                    this.description = "Liga: ${m.league} | Jadwal: ${m.kickOffTime} | Status: UPCOMING | Tersedia ${m.servers.size} Server"
                })
            }

            if (episodes.isEmpty()) {
                val placeholderData = "${MASK_PREFIX}empty"
                episodes.add(newEpisode(placeholderData) {
                    this.name = "Belum ada pertandingan siaran langsung saat ini"
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = hubInfo.poster
                    this.description = "Silakan periksa kembali beberapa saat lagi saat jadwal match dimulai."
                })
            }

            return newTvSeriesLoadResponse(hubInfo.name, url, TvType.TvSeries, episodes) {
                this.posterUrl = hubInfo.poster
                this.plot = "${hubInfo.description}\n\n• 🔴 LIVE: ${liveMatches.size} Pertandingan\n• ⏳ UPCOMING: ${upcomingMatches.size} Pertandingan"
                this.seasonNames = listOf(
                    SeasonData(1, "LIVE"),
                    SeasonData(2, "UPCOMING")
                )
            }
        }

        // ── 3. TV 24/7 Channel Clicked ──
        if (cleanUrl.startsWith("ch::")) {
            val jsonBase64 = cleanUrl.substringAfter("ch::")
            val channel: ChannelItem = try {
                val json = String(Base64.decode(jsonBase64, Base64.DEFAULT), Charsets.UTF_8)
                mapper.readValue(json)
            } catch (_: Exception) {
                ChannelItem("ch", "Live TV", "", "TV", emptyList())
            }

            val episodes = channel.servers.mapIndexed { idx, srv ->
                val srvPayload = mapper.writeValueAsString(srv)
                val epData = "${MASK_PREFIX}srv::" + Base64.encodeToString(srvPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                newEpisode(epData) {
                    this.name = srv.name
                    this.season = 1
                    this.episode = idx + 1
                    this.description = "Kategori: ${channel.group} | Server: ${srv.name}"
                }
            }

            return newTvSeriesLoadResponse(channel.title, url, TvType.Live, episodes) {
                this.posterUrl = channel.logo.ifEmpty { "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/live_icon.png" }
                this.plot = "Siaran TV 24/7 ${channel.title} (${channel.group}) • Multi-Server Failover"
                this.seasonNames = listOf(SeasonData(1, "Pilihan Server TV"))
            }
        }

        // Direct Fallback
        return newTvSeriesLoadResponse("Live Stream", url, TvType.Live, emptyList())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = if (data.contains("lynk.id")) data.substringAfterLast("#", "") else data
        if (cleanData.isEmpty() || cleanData == "empty") return false

        // ── Case A: Match Episode Clicked (Contains List of StreamServers) ──
        if (cleanData.startsWith("match::")) {
            val jsonBase64 = cleanData.substringAfter("match::")
            val servers: List<StreamServer> = try {
                val json = String(Base64.decode(jsonBase64, Base64.DEFAULT), Charsets.UTF_8)
                mapper.readValue(json)
            } catch (_: Exception) {
                emptyList()
            }

            for (srv in servers) {
                emitServerLink(srv, callback)
            }
            return true
        }

        // ── Case B: Single Server Clicked (From 24/7 Channel / Direct Match) ──
        if (cleanData.startsWith("srv::")) {
            val jsonBase64 = cleanData.substringAfter("srv::")
            val srv: StreamServer = try {
                val json = String(Base64.decode(jsonBase64, Base64.DEFAULT), Charsets.UTF_8)
                mapper.readValue(json)
            } catch (_: Exception) {
                StreamServer("Server", cleanData)
            }

            emitServerLink(srv, callback)
            return true
        }

        // Direct URL fallback
        emitServerLink(StreamServer("Direct Server", cleanData), callback)
        return true
    }

    private suspend fun emitServerLink(srv: StreamServer, callback: (ExtractorLink) -> Unit) = withContext(Dispatchers.IO) {
        var streamUrl = srv.url
        if (streamUrl.isEmpty()) return@withContext

        // 0. Unpack direct liveUrl if wrapped in web player query param
        if (streamUrl.contains("liveUrl=")) {
            try {
                val parsed = Uri.parse(streamUrl)
                val liveParam = parsed.getQueryParameter("liveUrl")
                if (!liveParam.isNullOrEmpty()) {
                    streamUrl = liveParam
                }
            } catch (_: Exception) {}
        }

        val headers = srv.headers.toMutableMap()
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = DESKTOP_UA
        }

        // 1. Resolve redirect for worker / resolve-web to direct m3u8
        if (streamUrl.contains("resolve-web") || streamUrl.contains("livevent.elutuna.workers.dev")) {
            try {
                val req = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "https://playerkltratv.pages.dev/")
                    .build()
                val resp = app.baseClient.newBuilder().followRedirects(false).build().newCall(req).execute()
                val loc = resp.header("Location")
                if (!loc.isNullOrEmpty()) {
                    val parsed = Uri.parse(loc)
                    val liveParam = parsed.getQueryParameter("liveUrl")
                    streamUrl = if (!liveParam.isNullOrEmpty()) liveParam else loc
                }
            } catch (_: Exception) {}
        }

        // 2. Resolve ondemand.st / damitv.st extract-url (Main / Substreams)
        if (streamUrl.contains("/papi/extract-url/")) {
            try {
                val req = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "https://ondemand.st/")
                    .header("Accept", "application/json")
                    .build()
                val resp = app.baseClient.newCall(req).execute()
                val bodyStr = resp.body?.string() ?: ""
                val jsonTree = mapper.readTree(bodyStr)
                val hlsUrl = jsonTree.get("hlsUrl")?.asText()
                if (!hlsUrl.isNullOrEmpty() && !hlsUrl.contains("tv/dlhd") && !hlsUrl.contains("tv/playlist")) {
                    streamUrl = hlsUrl
                    headers["Referer"] = "https://messi.damitv.st/"
                } else {
                    return@withContext
                }
            } catch (_: Exception) {
                return@withContext
            }
        }

        // 2. Inject precise Referer required by CDN anti-hotlink
        val lower = streamUrl.lowercase()
        if (!headers.containsKey("Referer")) {
            when {
                lower.contains("vivo200.com") || lower.contains("online909.com") -> headers["Referer"] = "https://player.online909.com/"
                lower.contains("elutuna.workers.dev") || lower.contains("resolve-web") -> headers["Referer"] = "https://playerkltratv.pages.dev/"
                lower.contains("stream-cdn-box") || lower.contains("damitv") || lower.contains("ondemand.st") -> headers["Referer"] = "https://damitv.st/"
                lower.contains("weibisai") || lower.contains("smtcdns") -> headers["Referer"] = "https://play.cbalive.weibisai.com/"
                lower.contains("quickscoreboardz") || lower.contains("100ycdn") -> headers["Referer"] = "https://live1.quickscoreboardz.com/"
                lower.contains("yalla") -> headers["Referer"] = "https://yalla-shoot.com/"
                lower.contains("phaohoa") -> headers["Referer"] = "https://phaohoa.live/"
                lower.contains("dens.tv") -> headers["Referer"] = "https://www.dens.tv/"
                lower.contains("detik.com") -> headers["Referer"] = "https://video.detik.com/"
                lower.contains("rctiplus") -> headers["Referer"] = "https://www.rctiplus.com/"
            }
        }

        val isMpd = streamUrl.contains(".mpd", ignoreCase = true)
        val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
        val linkType = when {
            isMpd -> ExtractorLinkType.DASH
            isM3u8 -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        val kodiProps = srv.kodiProps
        val licenseType = kodiProps["inputstream.adaptive.license_type"]
        val licenseKey = kodiProps["inputstream.adaptive.license_key"]
        val isDrm = (licenseKey != null && (licenseKey.contains(":") || licenseKey.startsWith("http"))) || (licenseType != null && isMpd)
        if (isDrm) {
            var isClearkey = licenseType?.contains("clearkey", ignoreCase = true) == true
            var clearkeyKid: String? = null
            var clearkeyKey: String? = null
            var finalLicenseUrl: String? = kodiProps["inputstream.adaptive.license_url"]

            if (licenseKey != null) {
                val trimmed = licenseKey.trim()
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    finalLicenseUrl = trimmed
                } else if (trimmed.contains(":")) {
                    isClearkey = true
                    val parts = trimmed.split(":", limit = 2)
                    clearkeyKid = hexToBase64Url(parts[0].trim())
                    clearkeyKey = hexToBase64Url(parts[1].trim())
                } else {
                    isClearkey = true
                    clearkeyKey = hexToBase64Url(trimmed)
                }
            }

            val drmUuid = if (isClearkey) CLEARKEY_UUID else WIDEVINE_UUID

            callback.invoke(
                newDrmExtractorLink(
                    source = this@Xr3edTVProvider.name,
                    name = srv.name,
                    url = streamUrl,
                    type = linkType,
                    uuid = drmUuid
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                    if (isClearkey) {
                        this.kty = "oct"
                        if (clearkeyKid != null) this.kid = clearkeyKid
                        if (clearkeyKey != null) this.key = clearkeyKey
                    }
                    if (finalLicenseUrl != null) {
                        this.licenseUrl = finalLicenseUrl
                    }
                }
            )
        } else {
            callback.invoke(
                newExtractorLink(
                    source = this@Xr3edTVProvider.name,
                    name = srv.name,
                    url = streamUrl,
                    type = linkType
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }
}
