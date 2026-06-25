package com.cncverse.M3UPlaylistPlayer

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey

class M3UPlaylistPlayer(
    private val playlistName: String,
    private val playlistUrl: String
) : MainAPI() {
    override var name: String = playlistName
    override val hasMainPage = true
    override var lang = ""
    override val supportedTypes = setOf(TvType.Live)
 
    companion object {
        var context: Context? = null
        var lastWorkingUserAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
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
  
    private suspend fun fetchPlaylist(): Playlist = coroutineScope {
        if (playlistUrl.isBlank()) {
            return@coroutineScope Playlist(emptyList())
        }
        
        val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "OTTNavigator/1.6.8.2 (Linux;Android 11)",
            "TiviMate/4.7.0 (Linux;Android 11)",
            "VLC/3.0.18",
            "okhttp/4.9.2"
        )
        
        var content = ""
        var success = false
        var fallbackContent = ""
        
        for (ua in userAgents) {
            try {
                val headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "*/*"
                )
                val response = app.get(playlistUrl, headers = headers, timeout = 15)
                val text = response.text
                if (text.isNotBlank()) {
                    val clean = if (text.startsWith("\uFEFF")) text.substring(1) else text
                    val trimmed = clean.trim()
                    if (trimmed.startsWith("#EXTM3U", ignoreCase = true) || trimmed.contains("#EXTINF", ignoreCase = true)) {
                        content = clean
                        success = true
                        lastWorkingUserAgent = ua
                        android.util.Log.d("M3UPlayer", "Successfully fetched playlist using User-Agent: $ua")
                        break
                    } else if (fallbackContent.isBlank()) {
                        fallbackContent = clean
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("M3UPlayer", "Failed to fetch playlist using User-Agent: $ua", e)
            }
        }
        
        val cleanContent = if (success) content else fallbackContent
        if (cleanContent.isBlank()) {
            return@coroutineScope Playlist(emptyList())
        }
        
        // Extract EPG URL from M3U header
        val extractedUrl = extractEpgUrl(cleanContent)
        if (extractedUrl != null) {
            context?.setKey("extracted_epg_${playlistUrl.hashCode()}", extractedUrl)
            android.util.Log.d("M3UPlayer", "Extracted EPG URL from M3U: $extractedUrl")
        }
        
        try {
            val parsed = IptvPlaylistParser().parseM3U(cleanContent)
            
            // Clean up group-title so we don't prepend the playlist name (fixes image 3)
            val cleanedItems = parsed.items.map { item ->
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

        return newHomePageResponse(listOf(homePageList), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val playlist = fetchPlaylist()
        val epgUrl = getEpgUrlToUse()
        val (epgData, nameToIdMap) = EpgHelper.getEpg(context, epgUrl)
        return playlist.items
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

    override suspend fun load(url: String): LoadResponse {
        val playlist = fetchPlaylist()
        val item = playlist.items.firstOrNull { it.url == url }
        val title = item?.title ?: "Live Channel"
        val logoUrl = item?.attributes["tvg-logo"]

        var description = "Siaran Langsung"
        if (item != null) {
            val epgUrl = getEpgUrlToUse()
            val (epgData, nameToIdMap) = EpgHelper.getEpg(context, epgUrl)
            val progs = EpgHelper.getProgramsForChannel(item, epgData, nameToIdMap)
            
            if (progs.isEmpty()) {
                val tvgId = item.attributes["tvg-id"]?.trim() ?: "tidak ada"
                val tvgName = item.attributes["tvg-name"]?.trim() ?: "tidak ada"
                val cleanTitle = EpgHelper.cleanChannelName(title)
                
                description = "Tidak ada data EPG untuk channel ini.\n\n" +
                              "--- INFO DEBUG ---\n" +
                              "• URL EPG: $epgUrl\n" +
                              "• Total ID Channel Terurai: ${nameToIdMap.size}\n" +
                              "• Total Program Terurai: ${epgData.values.sumOf { it.size }}\n" +
                              "• Atribut tvg-id M3U: '$tvgId'\n" +
                              "• Atribut tvg-name M3U: '$tvgName'\n" +
                              "• Judul Channel: '$title' (bersih: '$cleanTitle')\n" +
                              "• Cocok via ID M3U di EPG: ${epgData.containsKey(tvgId.lowercase())}\n" +
                              "• Cocok via Nama/Fuzzy di EPG: ${nameToIdMap.containsKey(title.lowercase()) || nameToIdMap.containsKey(cleanTitle)}"
            } else {
                val currentAndUpcoming = EpgHelper.getCurrentAndUpcomingText(progs)
                description = currentAndUpcoming.second
            }
        }

        return newLiveStreamLoadResponse(
            title,
            url,
            url
        ) {
            this.posterUrl = logoUrl
            this.plot = description
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
        val playlist = fetchPlaylist()
        val item = playlist.items.firstOrNull { it.url == data }
        val url = item?.url ?: data

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
