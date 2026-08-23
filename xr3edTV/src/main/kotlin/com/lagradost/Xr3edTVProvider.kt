package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.xr3edTV.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ─── Data Classes ─────────────────────────────────────────────────────────────

data class StreamServer(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val kodiProps: Map<String, String> = emptyMap()
)

data class Xr3edTvItem(
    val id: String,
    val title: String,
    val logo: String,
    val group: String,
    val servers: List<StreamServer>,
    val info: String = ""
)

// ─── Main Provider ────────────────────────────────────────────────────────────

class Xr3edTVProvider : MainAPI() {
    companion object {
        val mapper = jacksonObjectMapper()

        val CLEARKEY_UUID: UUID = UUID.fromString("e2719d58-a985-b3c9-781a-b030e99c439d")
        val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        const val MASK_PREFIX = "https://lynk.id/xr3ed#"
        const val FALLBACK_MASTER_M3U = "https://raw.githubusercontent.com/xr3ed/xr3ed-tv/main/xr3dtv.m3u8"
        const val FALLBACK_NASIONAL_M3U = "https://raw.githubusercontent.com/xr3ed/xr3ed-tv/main/nasional.m3u"

        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        private var kltraCache: Pair<Long, List<Xr3edTvItem>>? = null
        private var ondemandCache: Pair<Long, List<Xr3edTvItem>>? = null
        private var masterM3uCache: Pair<Long, Map<String, List<Xr3edTvItem>>>? = null

        private const val LIVE_CACHE_TTL = 30_000L // 30 detik untuk live sports
        private const val M3U_CACHE_TTL = 60_000L  // 60 detik untuk master M3U
    }

    override var mainUrl = "https://lynk.id/xr3ed"
    override var name = "📺 xr3edTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "live"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = listOf(
        MainPageData("🔥 Hot Event", "🔥 Hot Event"),
        MainPageData("🔴 Live Event", "🔴 Live Event"),
        MainPageData("🥊 FIGHT & COMBAT", "🥊 FIGHT & COMBAT"),
        MainPageData("⏳ Upcoming Event", "⏳ Upcoming Event"),
        MainPageData("🇮🇩 NASIONAL", "🇮🇩 NASIONAL"),
        MainPageData("⚽ SPORTS", "⚽ SPORTS"),
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

    private fun httpGet(url: String, referer: String? = null, userAgent: String = DESKTOP_UA): String? {
        return try {
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

    private fun getEventHiddenId(uuidStr: String, salt: String): String {
        return try {
            val parts = uuidStr.split("-")
            if (parts.size < 5) return ""
            val s1 = if (salt.length >= 7) salt.substring(0, 7) else salt
            val s2 = if (salt.length >= 20) salt.substring(12, 20) else ""
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
                return Base64.encodeToString(matchId.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
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
            Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Exception) {
            matchId
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

    private fun parseServerAndTitle(rawTitle: String): Pair<String, String> {
        val serverRegex = Regex("""\s*-\s*(Server\s*\d+(?:\s*\([^)]*\))?)\s*""", RegexOption.IGNORE_CASE)
        val match = serverRegex.find(rawTitle)
        return if (match != null) {
            val srvName = match.groupValues[1].trim()
            val cleanTitle = rawTitle.removeRange(match.range).replace(Regex("""\s+"""), " ").trim()
            Pair(cleanTitle, srvName)
        } else {
            Pair(rawTitle.trim(), "Server 1")
        }
    }

    // ─── Engine 1: Kltra Direct Fetcher ───────────────────────────────────────

    private fun fetchKltraItems(): List<Xr3edTvItem> {
        val now = System.currentTimeMillis()
        kltraCache?.let { (ts, data) ->
            if (now - ts < LIVE_CACHE_TTL) return data
        }

        val apiBase = BuildConfig.XR3EDTV_API_BASE.trimEnd('/')
        val saltKey = BuildConfig.XR3EDTV_SALT_KEY
        if (apiBase.isEmpty() || saltKey.isEmpty()) return emptyList()

        val results = mutableListOf<Xr3edTvItem>()
        try {
            val ts = System.currentTimeMillis()
            val eventsRaw = httpGet("$apiBase/vip/eventweb.json?v=$ts") ?: return emptyList()
            val playersRaw = httpGet("$apiBase/vip/sdplayer.json?v=$ts") ?: "{}"

            val eventsNode = mapper.readTree(eventsRaw)
            val playersNode = mapper.readTree(playersRaw)

            val playerMap = mutableMapOf<String, List<StreamServer>>()
            if (playersNode.isArray) {
                for (p in playersNode) {
                    val id = p.get("id")?.asText() ?: continue
                    val serversNode = p.get("servers")
                    val serverList = mutableListOf<StreamServer>()
                    if (serversNode != null && serversNode.isArray) {
                        for ((idx, s) in serversNode.withIndex()) {
                            val url = s.get("url")?.asText() ?: continue
                            val sName = s.get("name")?.asText() ?: "Server ${idx + 1}"
                            val kodiProps = mutableMapOf<String, String>()
                            s.get("key")?.asText()?.let { k ->
                                if (k.isNotEmpty()) {
                                    kodiProps["inputstream.adaptive.license_type"] = "clearkey"
                                    kodiProps["inputstream.adaptive.license_key"] = k
                                }
                            }
                            serverList.add(StreamServer(sName, url, mapOf("User-Agent" to DESKTOP_UA), kodiProps))
                        }
                    }
                    if (serverList.isNotEmpty()) {
                        playerMap[id] = serverList
                    }
                }
            }

            if (eventsNode.isArray) {
                for (ev in eventsNode) {
                    val evId = ev.get("id")?.asText() ?: continue
                    val hiddenId = getEventHiddenId(evId, saltKey)
                    val servers = playerMap[hiddenId] ?: continue
                    if (servers.isEmpty()) continue

                    val league = ev.get("league")?.asText()?.trim() ?: "Sports Event"
                    val t1 = ev.get("team1")?.get("name")?.asText()?.trim() ?: ""
                    val t2 = ev.get("team2")?.get("name")?.asText()?.trim() ?: ""
                    val title = if (t1.isNotEmpty() && t2.isNotEmpty()) "[$league] $t1 vs $t2" else "[$league] ${ev.get("name")?.asText() ?: "Match"}"
                    val logo = ev.get("team1")?.get("logo")?.asText() ?: ev.get("team2")?.get("logo")?.asText() ?: ev.get("icon")?.asText() ?: ""

                    results.add(Xr3edTvItem(
                        id = "kltra_$evId",
                        title = title,
                        logo = logo,
                        group = "🔴 Live Event",
                        servers = servers,
                        info = league
                    ))
                }
            }

            kltraCache = Pair(now, results)
        } catch (_: Exception) {}
        return results
    }

    // ─── Engine 2: OnDemand Direct Fetcher ────────────────────────────────────

    private fun fetchOnDemandItems(): List<Xr3edTvItem> {
        val now = System.currentTimeMillis()
        ondemandCache?.let { (ts, data) ->
            if (now - ts < LIVE_CACHE_TTL) return data
        }

        val ondemandApi = BuildConfig.XR3EDTV_ONDEMAND_API.trim()
        val ondemandReferer = BuildConfig.XR3EDTV_ONDEMAND_REFERER.trim()
        val workerBase = BuildConfig.WORKER_BASE_URL.trimEnd('/')
        val workerKey = BuildConfig.WORKER_AUTH_KEY.trim()
        if (ondemandApi.isEmpty() || workerBase.isEmpty()) return emptyList()

        val results = mutableListOf<Xr3edTvItem>()
        try {
            val jsonStr = httpGet(ondemandApi, referer = ondemandReferer) ?: return emptyList()
            val rootNode = mapper.readTree(jsonStr)

            val matchesNode = if (rootNode.isArray) rootNode else rootNode.get("matches") ?: rootNode.get("data")
            if (matchesNode != null && matchesNode.isArray) {
                for (m in matchesNode) {
                    val mid = m.get("id")?.asText() ?: m.get("match_id")?.asText() ?: continue
                    val name = m.get("name")?.asText()?.trim() ?: "Sports Match"
                    val league = m.get("league")?.asText()?.trim() ?: "Live Sports"
                    val logo = m.get("logo")?.asText() ?: m.get("poster")?.asText() ?: ""
                    val status = m.get("status")?.asText() ?: "live"

                    val isLive = status.equals("live", ignoreCase = true)
                    val isUpcoming = status.equals("upcoming", ignoreCase = true)

                    val servers = mutableListOf<StreamServer>()
                    val encPrimary = encryptMatchId(mid, workerKey)
                    val primaryUrl = "$workerBase/live/$encPrimary.m3u8"
                    val odHeaders = mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Referer" to if (ondemandReferer.isNotEmpty()) ondemandReferer else "https://messi.damitv.st/"
                    )
                    servers.add(StreamServer("Server 1 (Primary HLS)", primaryUrl, odHeaders))

                    val substreamsNode = m.get("substreams")
                    if (substreamsNode != null && substreamsNode.isArray) {
                        for ((idx, sub) in substreamsNode.withIndex()) {
                            val subId = sub.get("id")?.asText() ?: continue
                            val subName = sub.get("name")?.asText() ?: "Alt Stream ${idx + 2}"
                            val encSub = encryptMatchId(subId, workerKey)
                            val subUrl = "$workerBase/live/$encSub.m3u8"
                            servers.add(StreamServer("Server ${idx + 2} ($subName)", subUrl, odHeaders))
                        }
                    }

                    val groupTag = when {
                        isLive -> "🔴 Live Event"
                        isUpcoming -> "⏳ Upcoming Event"
                        else -> "🔴 Live Event"
                    }

                    results.add(Xr3edTvItem(
                        id = "od_$mid",
                        title = "[$league] $name",
                        logo = logo,
                        group = groupTag,
                        servers = servers,
                        info = league
                    ))
                }
            }

            ondemandCache = Pair(now, results)
        } catch (_: Exception) {}
        return results
    }

    // ─── Engine 3: Master M3U Categorized Fetcher ─────────────────────────────

    private fun fetchMasterM3uCategories(): Map<String, List<Xr3edTvItem>> {
        val now = System.currentTimeMillis()
        masterM3uCache?.let { (ts, data) ->
            if (now - ts < M3U_CACHE_TTL && data.isNotEmpty()) return data
        }

        val primarySource = BuildConfig.NASIONAL_SOURCE_URL.trim()
        val urlsToTry = if (primarySource.isNotEmpty()) {
            listOf(FALLBACK_MASTER_M3U, primarySource, FALLBACK_NASIONAL_M3U)
        } else {
            listOf(FALLBACK_MASTER_M3U, FALLBACK_NASIONAL_M3U)
        }

        var content: String? = null
        for (u in urlsToTry) {
            content = httpGet(u)
            if (!content.isNullOrEmpty() && content.contains("#EXTINF")) break
        }

        if (content.isNullOrEmpty()) return emptyMap()

        val categoryMap = mutableMapOf<String, MutableList<Xr3edTvItem>>()
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
                                val (cleanName, srvName) = parseServerAndTitle(rawTitle)

                                val list = categoryMap.getOrPut(currentGroup) { mutableListOf() }
                                val existingItem = list.find { it.title.equals(cleanName, ignoreCase = true) }

                                val srv = StreamServer(srvName, line, currentHeaders.toMap(), currentKodiProps.toMap())
                                if (existingItem != null) {
                                    val updatedServers = existingItem.servers + srv
                                    val idx = list.indexOf(existingItem)
                                    list[idx] = existingItem.copy(servers = updatedServers)
                                } else {
                                    list.add(Xr3edTvItem(
                                        id = "m3u_${cleanName.hashCode()}",
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

            masterM3uCache = Pair(now, categoryMap)
        } catch (_: Exception) {}
        return categoryMap
    }

    // ─── Cloudstream Main Pages ───────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val reqTag = request.data.trim()

        val itemsToDisplay = coroutineScope {
            val kltraDeferred = async { fetchKltraItems() }
            val odDeferred = async { fetchOnDemandItems() }
            val m3uDeferred = async { fetchMasterM3uCategories() }

            val kltra = kltraDeferred.await()
            val od = odDeferred.await()
            val m3uCategories = m3uDeferred.await()

            when {
                reqTag.contains("HOT", ignoreCase = true) || reqTag.contains("Hot Event", ignoreCase = true) -> {
                    val directHot = (kltra + od).filter { it.group.contains("HOT", ignoreCase = true) || it.group.contains("LIVE", ignoreCase = true) }.take(15)
                    if (directHot.isNotEmpty()) directHot else m3uCategories["🔥 Hot Event"] ?: m3uCategories["🔴 Live Event"] ?: emptyList()
                }
                reqTag.contains("FIGHT", ignoreCase = true) -> {
                    val directFight = (kltra + od).filter {
                        val t = "${it.title} ${it.info}".lowercase()
                        t.contains("ufc") || t.contains("fight") || t.contains("boxing") || t.contains("combat") || t.contains("mma")
                    }
                    if (directFight.isNotEmpty()) directFight else m3uCategories["🥊 FIGHT & COMBAT"] ?: emptyList()
                }
                reqTag.contains("UPCOMING", ignoreCase = true) -> {
                    val directUpcoming = (kltra + od).filter { it.group.contains("UPCOMING", ignoreCase = true) }
                    if (directUpcoming.isNotEmpty()) directUpcoming else m3uCategories["⏳ Upcoming Event"] ?: emptyList()
                }
                reqTag.contains("LIVE", ignoreCase = true) || reqTag.contains("Live Event", ignoreCase = true) -> {
                    val directLive = (kltra + od).filter { it.group.contains("LIVE", ignoreCase = true) }
                    if (directLive.isNotEmpty()) directLive else m3uCategories["🔴 Live Event"] ?: m3uCategories["🔥 Hot Event"] ?: emptyList()
                }
                else -> {
                    // Match category by exact name or substring
                    m3uCategories[reqTag]
                        ?: m3uCategories.entries.find { it.key.contains(reqTag, ignoreCase = true) || reqTag.contains(it.key, ignoreCase = true) }?.value
                        ?: emptyList()
                }
            }
        }

        val searchResponses = itemsToDisplay.map { item ->
            val payloadJson = mapper.writeValueAsString(item)
            val maskedData = MASK_PREFIX + Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            newLiveSearchResponse(item.title, maskedData, TvType.Live) {
                this.posterUrl = item.logo.ifEmpty { "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/live_icon.png" }
            }
        }

        return newHomePageResponse(request.name, searchResponses)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.lowercase().trim()
        val allItems = mutableListOf<Xr3edTvItem>()
        coroutineScope {
            val kltraDeferred = async { fetchKltraItems() }
            val odDeferred = async { fetchOnDemandItems() }
            val m3uDeferred = async { fetchMasterM3uCategories() }
            allItems.addAll(kltraDeferred.await())
            allItems.addAll(odDeferred.await())
            m3uDeferred.await().values.forEach { allItems.addAll(it) }
        }

        return allItems.filter { it.title.lowercase().contains(cleanQuery) }.map { item ->
            val payloadJson = mapper.writeValueAsString(item)
            val maskedData = MASK_PREFIX + Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            newLiveSearchResponse(item.title, maskedData, TvType.Live) {
                this.posterUrl = item.logo.ifEmpty { "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/live_icon.png" }
            }
        }
    }

    // ─── Details & Link Resolution ────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val cleanPayload = if (url.contains("lynk.id")) url.substringAfterLast("#", "") else url
        val item: Xr3edTvItem = try {
            val json = String(Base64.decode(cleanPayload, Base64.DEFAULT), Charsets.UTF_8)
            mapper.readValue(json)
        } catch (_: Exception) {
            Xr3edTvItem(
                id = "direct",
                title = "Live Stream",
                logo = "",
                group = "LIVE",
                servers = listOf(StreamServer("Direct Server", cleanPayload))
            )
        }

        val episodes = item.servers.mapIndexed { idx, srv ->
            val srvPayload = mapper.writeValueAsString(srv)
            val maskedSrv = MASK_PREFIX + Base64.encodeToString(srvPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            newEpisode(maskedSrv) {
                this.name = srv.name
                this.episode = idx + 1
            }
        }

        return newTvSeriesLoadResponse(item.title, url, TvType.Live, episodes) {
            this.posterUrl = item.logo.ifEmpty { "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/live_icon.png" }
            this.plot = "Siaran Langsung ${item.title} (${item.group}) • Multi-Server Failover"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanPayload = if (data.contains("lynk.id")) data.substringAfterLast("#", "") else data
        val srv: StreamServer = try {
            val json = String(Base64.decode(cleanPayload, Base64.DEFAULT), Charsets.UTF_8)
            mapper.readValue(json)
        } catch (_: Exception) {
            StreamServer("Server", cleanPayload)
        }

        val streamUrl = srv.url
        val isMpd = streamUrl.contains(".mpd", ignoreCase = true)
        val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
        val linkType = when {
            isMpd -> ExtractorLinkType.DASH
            isM3u8 -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        val headers = srv.headers.toMutableMap()
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = DESKTOP_UA
        }

        val kodiProps = srv.kodiProps
        val licenseType = kodiProps["inputstream.adaptive.license_type"]
        val licenseKey = kodiProps["inputstream.adaptive.license_key"]

        if (licenseType != null || licenseKey != null) {
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
                    source = this.name,
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
                    source = this.name,
                    name = srv.name,
                    url = streamUrl,
                    type = linkType
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }

        return true
    }
}
