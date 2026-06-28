package com.cncverse.M3UPlaylistPlayer

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import java.text.SimpleDateFormat
import java.util.Locale
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class M3UPlaylistPlayer(
    private val playlistName: String,
    private val playlistUrl: String
) : MainAPI() {
    override var name: String = playlistName
    override val hasMainPage = true
    override var lang = ""
    override val supportedTypes = setOf(TvType.Live)
    
    // Set mainUrl ke URL Facebook user agar badge provider (seperti dhanytv/nama playlist)
    // mengarah ke Facebook saat diklik di halaman detail channel.
    override var mainUrl: String = "https://www.facebook.com/pesbuk.ibal"
 
    companion object {
        var context: Context? = null
        var lastWorkingUserAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        private val cachedPlaylists = mutableMapOf<String, Playlist>()
        private val lastFetchTimes = mutableMapOf<String, Long>()
        private val playlistMutexes = mutableMapOf<String, Mutex>()
        private val globalMutex = Mutex()
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L // Cache 5 menit

        suspend fun getMutexForUrl(url: String): Mutex {
            return globalMutex.withLock {
                playlistMutexes.getOrPut(url) { Mutex() }
            }
        }

        fun clearMemoryCache() {
            synchronized(cachedPlaylists) {
                cachedPlaylists.clear()
                lastFetchTimes.clear()
            }
        }
    }

    private fun extractEpgUrl(m3uContent: String): String? {
        val firstLine = m3uContent.lineSequence().firstOrNull { it.startsWith("#EXTM3U", ignoreCase = true) } ?: return null
        val regex = Regex("""(?:x-tvg-url|url-tvg)\s*=\s*["']?([^"'\s>]+)["']?""", RegexOption.IGNORE_CASE)
        val match = regex.find(firstLine)
        if (match != null) {
            val urls = match.groupValues[1]
            return urls.split(',').firstOrNull { it.isNotBlank() }?.trim()
        }
        return null
    }

    private fun getEpgUrlToUse(): String {
        val ctx = context ?: return "https://raw.githubusercontent.com/dhasap/dhanytv/main/epg.xml"
        
        // 1. Try custom EPG URL from settings
        val playlists = PlaylistHelper.getSavedPlaylists(ctx)
        val savedPlaylist = playlists.firstOrNull { it.url == playlistUrl }
        if (savedPlaylist != null && !savedPlaylist.epgUrl.isNullOrBlank()) {
            return savedPlaylist.epgUrl!!
        }
        
        // 2. Try extracted EPG URL from M3U header
        val extracted = ctx.getKey<String>("extracted_epg_${playlistUrl.hashCode()}")
        if (!extracted.isNullOrBlank()) {
            return extracted
        }
        
        // 3. Default fallback
        return "https://raw.githubusercontent.com/dhasap/dhanytv/main/epg.xml"
    }

    override val mainPage: List<MainPageData>
        get() {
            val groups = PlaylistHelper.getCachedGroups(context, playlistUrl)
            if (groups.isEmpty()) {
                return listOf(MainPageData("Live Channels", "Live Channels"))
            }
            return groups.map { MainPageData(it, it) }
        }
  
    private suspend fun fetchPlaylist(): Playlist {
        if (playlistUrl.isBlank()) {
            return Playlist(emptyList())
        }
        
        val now = System.currentTimeMillis()
        val cached = synchronized(cachedPlaylists) { cachedPlaylists[playlistUrl] }
        val lastFetch = synchronized(lastFetchTimes) { lastFetchTimes[playlistUrl] ?: 0L }
        
        if (cached != null && (now - lastFetch) < CACHE_DURATION_MS) {
            android.util.Log.d("M3UPlayer", "Using cached playlist for $playlistUrl")
            return cached
        }
        
        val mutex = getMutexForUrl(playlistUrl)
        return mutex.withLock {
            val cachedSecond = synchronized(cachedPlaylists) { cachedPlaylists[playlistUrl] }
            val lastFetchSecond = synchronized(lastFetchTimes) { lastFetchTimes[playlistUrl] ?: 0L }
            if (cachedSecond != null && (now - lastFetchSecond) < CACHE_DURATION_MS) {
                android.util.Log.d("M3UPlayer", "Using cached playlist for $playlistUrl (after lock)")
                return@withLock cachedSecond
            }
            
            withContext(Dispatchers.IO) {
                val userAgents = listOf(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
                    "OTTNavigator/1.6.8.2 (Linux;Android 11)",
                    "TiviMate/4.7.0 (Linux;Android 11)",
                    "VLC/3.0.18",
                    "okhttp/4.9.2"
                )
                
                val urlsToTry = EpgHelper.getGithubMirrors(playlistUrl)
                var content = ""
                var success = false
                var fallbackContent = ""
                
                for (url in urlsToTry) {
                    if (success) break
                    for (ua in userAgents) {
                        try {
                            val headers = mapOf(
                                "User-Agent" to ua,
                                "Accept" to "*/*"
                            )
                            val response = app.get(url, headers = headers, timeout = 12)
                            val isGzip = url.endsWith(".gz", ignoreCase = true) || 
                                         response.headers["Content-Encoding"]?.contains("gzip", ignoreCase = true) == true
                            val text = if (isGzip) {
                                java.util.zip.GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
                            } else {
                                response.textLarge
                            }
                            if (text.isNotBlank()) {
                                val clean = if (text.startsWith("\uFEFF")) text.substring(1) else text
                                val trimmed = clean.trim()
                                if (trimmed.startsWith("#EXTM3U", ignoreCase = true) || trimmed.contains("#EXTINF", ignoreCase = true)) {
                                    content = clean
                                    success = true
                                    lastWorkingUserAgent = ua
                                    android.util.Log.d("M3UPlayer", "Successfully fetched playlist from $url using User-Agent: $ua")
                                    break
                                } else if (fallbackContent.isBlank()) {
                                    fallbackContent = clean
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("M3UPlayer", "Failed to fetch playlist from $url using User-Agent: $ua", e)
                        }
                    }
                }
                
                val cleanContent = if (success) content else fallbackContent
                if (cleanContent.isBlank()) {
                    return@withContext Playlist(emptyList())
                }
                
                // Extract EPG URL from M3U header
                val extractedUrl = extractEpgUrl(cleanContent)
                if (extractedUrl != null) {
                    context?.setKey("extracted_epg_${playlistUrl.hashCode()}", extractedUrl)
                    android.util.Log.d("M3UPlayer", "Extracted EPG URL from M3U: $extractedUrl")
                }
                
                val result = try {
                    val parsed = IptvPlaylistParser().parseM3U(cleanContent)
                    
                    // Lakukan deduplikasi link streaming untuk mencegah Cloudstream berat/lag akibat ratusan channel duplikat
                    val uniqueItems = parsed.items.distinctBy { it.url }
                    
                    // Clean up group-title so we don't prepend the playlist name (fixes image 3)
                    val cleanedItems = uniqueItems.map { item ->
                        val originalGroup = item.attributes["group-title"]
                        val newGroup = if (originalGroup.isNullOrBlank()) {
                            "Live Channels"
                        } else {
                            originalGroup.trim()
                        }
                        
                        val newAttributes = item.attributes.toMutableMap().apply {
                            put("group-title", newGroup)
                        }
                        
                        item.copy(attributes = newAttributes)
                    }
                    
                    val groups = cleanedItems.map { it.attributes["group-title"] ?: "Live Channels" }.distinct()
                    PlaylistHelper.saveCachedGroups(context, playlistUrl, groups)
                    
                    Playlist(cleanedItems)
                } catch (e: Exception) {
                    android.util.Log.e("M3UPlayer", "Error parsing playlist from $playlistUrl", e)
                    Playlist(emptyList())
                }
                
                if (result.items.isNotEmpty()) {
                    synchronized(cachedPlaylists) {
                        cachedPlaylists[playlistUrl] = result
                        lastFetchTimes[playlistUrl] = System.currentTimeMillis()
                    }
                }
                
                result
            }
        }
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val playlist = fetchPlaylist()
        if (playlist.items.isEmpty()) {
            return newHomePageResponse(
                listOf(
                    HomePageList(
                        "Silakan konfigurasi URL M3U di pengaturan",
                        emptyList()
                    )
                ),
                hasNext = false
            )
        }

        val grouped = playlist.items.groupBy { it.attributes["group-title"] ?: "Live Channels" }
        
        val groupName = request.data
        val items = grouped[groupName] ?: emptyList()
        
        if (items.isEmpty()) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        return withContext(Dispatchers.IO) {
            // Fetch EPG data
            val epgUrl = getEpgUrlToUse()
            val (epgData, nameToIdMap) = EpgHelper.getEpg(context, epgUrl)

            val homePageList = HomePageList(
                groupName,
                items.map { item ->
                    val progs = EpgHelper.getProgramsForChannel(item, epgData, nameToIdMap)
                    val currentAndUpcoming = EpgHelper.getCurrentAndUpcomingText(progs)
                    val displayName = currentAndUpcoming.first?.let { "${item.title} ($it)" } ?: item.title
                    
                    newLiveSearchResponse(
                        displayName,
                        item.url,
                        TvType.Live
                    ) {
                        this.posterUrl = item.attributes["tvg-logo"]
                    }
                }
            )

            newHomePageResponse(listOf(homePageList), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val playlist = fetchPlaylist()
        return withContext(Dispatchers.IO) {
            val epgUrl = getEpgUrlToUse()
            val (epgData, nameToIdMap) = EpgHelper.getEpg(context, epgUrl)
            playlist.items
                .filter { it.title.contains(query, ignoreCase = true) }
                .map { item ->
                    val progs = EpgHelper.getProgramsForChannel(item, epgData, nameToIdMap)
                    val currentAndUpcoming = EpgHelper.getCurrentAndUpcomingText(progs)
                    val displayName = currentAndUpcoming.first?.let { "${item.title} ($it)" } ?: item.title
                    
                    newLiveSearchResponse(
                        displayName,
                        item.url,
                        TvType.Live
                    ) {
                        this.posterUrl = item.attributes["tvg-logo"]
                    }
                }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = if (url.startsWith("https://www.facebook.com/pesbuk.ibal#")) {
            url.substringAfter("https://www.facebook.com/pesbuk.ibal#")
        } else {
            url
        }
        return withContext(Dispatchers.IO) {
            val playlist = fetchPlaylist()
            val item = playlist.items.firstOrNull { it.url == cleanUrl }
            val title = item?.title ?: "Live Channel"
            val logoUrl = item?.attributes["tvg-logo"]

            var description = "Siaran Langsung"
            val actorsList = mutableListOf<ActorData>()

            if (item != null) {
                val epgUrl = getEpgUrlToUse()
                val (epgData, nameToIdMap) = EpgHelper.getEpg(context, epgUrl)
                val progs = EpgHelper.getProgramsForChannel(item, epgData, nameToIdMap)
                
                if (progs.isEmpty()) {
                    val tvgId = item.attributes["tvg-id"]?.trim() ?: "tidak ada"
                    val tvgName = item.attributes["tvg-name"]?.trim() ?: "tidak ada"
                    val cleanTitle = EpgHelper.cleanChannelName(title)
                    
                    description = "Tidak ada data jadwal acara (EPG) untuk channel ini. \n\n ── " +
                                  "📢 PEMBERITAHUAN: Playlist & aplikasi ini 100% GRATIS! " +
                                  "Jika Anda membeli playlist atau aplikasi ini, Anda telah ditipu. ──"
                } else {
                    // Sembunyikan daftar jadwal teks panjang yang dilingkari merah dari deskripsi
                    // Cukup tampilkan deskripsi singkat dari program yang sedang tayang (jika ada), atau "Siaran Langsung"
                    val currentAndUpcoming = EpgHelper.getCurrentAndUpcomingText(progs)
                    val currentProgText = currentAndUpcoming.first // format: "Sedang Tayang: Judul (Start - Stop)[Sisa Xm]"
                    
                    val now = System.currentTimeMillis()
                    val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                    
                    var currentProgram: EpgProgram? = null
                    val upcomingPrograms = mutableListOf<EpgProgram>()
                    for (p in progs) {
                        if (now in p.startUnixMs until p.stopUnixMs) {
                            currentProgram = p
                        } else if (p.startUnixMs >= now) {
                            upcomingPrograms.add(p)
                        }
                    }
                    if (currentProgram == null) {
                        currentProgram = progs.lastOrNull { it.stopUnixMs <= now }
                    }

                    val baseDesc = if (currentProgram != null && currentProgram.desc.isNotEmpty()) {
                        "🔴 Sedang Tayang: ${currentProgram.title}\nDeskripsi: ${currentProgram.desc}"
                    } else if (currentProgram != null) {
                        "🔴 Sedang Tayang: ${currentProgram.title}"
                    } else {
                        val progText = currentProgText
                        if (progText != null) {
                            "🔴 $progText"
                        } else {
                            "🔴 Sedang Tayang: Siaran Langsung"
                        }
                    }
                    description = baseDesc + " \n\n ── " +
                                  "📢 PEMBERITAHUAN: Playlist & aplikasi ini 100% GRATIS! " +
                                  "Jika Anda membeli playlist atau aplikasi ini, Anda telah ditipu. ──"
                    
                    val liveIconUrl = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/live_icon.png"
                    val scheduleIconUrl = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/schedule_icon.png"
                    
                    if (currentProgram != null) {
                        val startStr = if (currentProgram.startUnixMs > 0) timeSdf.format(currentProgram.startUnixMs) else "--:--"
                        val stopStr = if (currentProgram.stopUnixMs > 0) timeSdf.format(currentProgram.stopUnixMs) else "--:--"
                        val remainingMs = currentProgram.stopUnixMs - now
                        val remainingMin = remainingMs / (60 * 1000)
                        val remainingText = if (remainingMin > 0) " (${remainingMin}m)" else ""
                        
                        actorsList.add(
                            ActorData(
                                Actor(
                                    name = "🔴 $startStr-$stopStr$remainingText",
                                    image = liveIconUrl
                                ),
                                roleString = currentProgram.title,
                                role = null
                            )
                        )
                    }
                    
                    for (p in upcomingPrograms.take(9)) {
                        val startStr = if (p.startUnixMs > 0) timeSdf.format(p.startUnixMs) else "--:--"
                        val stopStr = if (p.stopUnixMs > 0) timeSdf.format(p.stopUnixMs) else "--:--"
                        
                        actorsList.add(
                            ActorData(
                                Actor(
                                    name = "⏰ $startStr-$stopStr",
                                    image = scheduleIconUrl
                                ),
                                roleString = p.title,
                                role = null
                            )
                        )
                    }
                }
            }

            newLiveStreamLoadResponse(
                title,
                "https://www.facebook.com/pesbuk.ibal#$cleanUrl",
                cleanUrl
            ) {
                this.posterUrl = logoUrl
                this.plot = description
                this.actors = actorsList
            }
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
            android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        } catch (e: Exception) {
            clean
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = if (data.startsWith("https://www.facebook.com/pesbuk.ibal#")) {
            data.substringAfter("https://www.facebook.com/pesbuk.ibal#")
        } else {
            data
        }
        val playlist = fetchPlaylist()
        val item = playlist.items.firstOrNull { it.url == cleanData }
        val rawUrl = item?.url ?: cleanData

        val isFlv = rawUrl.contains(".flv", ignoreCase = true)
        val url = if (isFlv) {
            if (!rawUrl.contains("#")) "$rawUrl#.flv" else rawUrl
        } else if (!rawUrl.contains(".m3u8", ignoreCase = true) && 
                      !rawUrl.contains("m3u8", ignoreCase = true) && 
                      !rawUrl.contains(".mpd", ignoreCase = true) && 
                      !rawUrl.contains("mpd", ignoreCase = true) && 
                      !rawUrl.contains("#") && 
                      (rawUrl.contains("live.php") || rawUrl.contains("play.php") || rawUrl.contains("/live/"))) {
            "$rawUrl#.m3u8"
        } else {
            rawUrl
        }

        val isM3u8 = url.contains(".m3u8", ignoreCase = true) || url.contains("m3u8", ignoreCase = true)
        val isDash = url.contains(".mpd", ignoreCase = true) || url.contains("mpd", ignoreCase = true)
        val type = when {
            isM3u8 -> ExtractorLinkType.M3U8
            isDash -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        val parsedHeaders = item?.headers ?: emptyMap()
        val headers = if (parsedHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
            parsedHeaders
        } else {
            parsedHeaders + mapOf("User-Agent" to lastWorkingUserAgent)
        }
        val kodiProps = item?.kodiProps ?: emptyMap()
        val licenseType = kodiProps["inputstream.adaptive.license_type"]
        val licenseKey = kodiProps["inputstream.adaptive.license_key"]

        if (licenseType != null || licenseKey != null) {
            var finalLicenseUrl: String? = kodiProps["inputstream.adaptive.license_url"]
            val keyRequestHeaders = java.util.HashMap<String, String>()

            headers.forEach { (k, v) ->
                keyRequestHeaders[k] = v
            }

            var isClearkeyDrm = false
            var clearkeyKid: String? = null
            var clearkeyKey: String? = null

            if (licenseKey != null) {
                val trimmedKey = licenseKey.trim()
                if (trimmedKey.startsWith("http://", ignoreCase = true) || 
                    trimmedKey.startsWith("https://", ignoreCase = true) || 
                    trimmedKey.contains("://")) {
                    
                    val pipeIndex = trimmedKey.indexOf('|')
                    val rawUrl = if (pipeIndex != -1) trimmedKey.substring(0, pipeIndex).trim() else trimmedKey
                    if (finalLicenseUrl == null) {
                        finalLicenseUrl = rawUrl
                    }
                    
                    if (pipeIndex != -1) {
                        val headersStr = trimmedKey.substring(pipeIndex + 1).trim()
                        headersStr.split('&').forEach { pair ->
                            val parts = pair.split('=', limit = 2)
                            if (parts.size == 2) {
                                val name = parts[0].trim()
                                val value = parts[1].trim()
                                keyRequestHeaders[name] = value
                            }
                        }
                    }
                } else {
                    isClearkeyDrm = true
                    val parts = trimmedKey.split(':')
                    if (parts.size == 2) {
                        clearkeyKid = hexToBase64Url(parts[0].trim())
                        clearkeyKey = hexToBase64Url(parts[1].trim())
                    } else {
                        clearkeyKey = hexToBase64Url(trimmedKey)
                    }
                }
            }

            val isClearkey = isClearkeyDrm || licenseType?.contains("clearkey", ignoreCase = true) == true
            val drmUuid = if (isClearkey) CLEARKEY_UUID else WIDEVINE_UUID

            callback.invoke(
                newDrmExtractorLink(
                    this.name,
                    this.name,
                    url,
                    type,
                    drmUuid
                ) {
                    this.quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) {
                        this.headers = headers
                    }
                    if (isClearkey) {
                        this.kty = "oct"
                        if (clearkeyKid != null) {
                            this.kid = clearkeyKid
                        }
                        if (clearkeyKey != null) {
                            this.key = clearkeyKey
                        }
                    }
                    if (finalLicenseUrl != null) {
                        this.licenseUrl = finalLicenseUrl
                    }
                    if (keyRequestHeaders.isNotEmpty()) {
                        this.keyRequestParameters = keyRequestHeaders
                    }
                }
            )
        } else {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    url,
                    type
                ) {
                    this.quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) {
                        this.headers = headers
                    }
                }
            )
        }
        return true
    }
}
