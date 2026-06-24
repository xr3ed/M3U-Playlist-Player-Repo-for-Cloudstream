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
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.DataStore.getKey

class M3UPlaylistPlayer : MainAPI() {
    override var name: String
        get() = getM3uName()
        set(value) {}
    override val hasMainPage = true
    override var lang = ""
    override val supportedTypes = setOf(TvType.Live)
 
    companion object {
        var context: Context? = null
    }
 
    private fun getM3uUrl(): String? {
        return context?.getKey<String>("m3u_url")
    }
 
    private fun getM3uName(): String {
        val rawName = context?.getKey<String>("provider_name")
            ?: context?.getKey<String>("m3u_name")
            ?: "M3U Playlist Player"
        return " 📺 $rawName"
    }
 
    private fun getSavedPlaylists(): List<Pair<String, String>> {
        val raw = context?.getKey<String>("saved_playlists_list") ?: ""
        val list = if (raw.isBlank()) mutableListOf() else raw.split("\n").mapNotNull { line ->
            val parts = line.split("||")
            if (parts.size >= 2) {
                val name = parts[0].trim()
                val url = parts[1].trim()
                val enabled = if (parts.size >= 3) parts[2].trim().toBoolean() else true
                if (enabled) name to url else null
            } else null
        }.toMutableList()
 
        if (list.isEmpty()) {
            val activeUrl = context?.getKey<String>("m3u_url") ?: ""
            val activeName = context?.getKey<String>("m3u_name") ?: "M3U Playlist Player"
            if (activeUrl.isNotBlank()) {
                list.add(activeName to activeUrl)
            }
        }
        return list
    }

    private suspend fun fetchPlaylist(): Playlist = coroutineScope {
        val saved = getSavedPlaylists()
        if (saved.isEmpty()) {
            return@coroutineScope Playlist(emptyList())
        }
        
        val deferredPlaylists = saved.map { (playlistName, url) ->
            async {
                try {
                    val content = app.get(url).text
                    val parsed = IptvPlaylistParser().parseM3U(content)
                    playlistName to parsed
                } catch (e: Exception) {
                    playlistName to Playlist(emptyList())
                }
            }
        }
        
        val parsedPlaylists = deferredPlaylists.awaitAll()
        val allItems = mutableListOf<PlaylistItem>()
        
        parsedPlaylists.forEach { (playlistName, playlist) ->
            playlist.items.forEach { item ->
                val originalGroup = item.attributes["group-title"]
                val newGroup = if (originalGroup.isNullOrBlank()) {
                    playlistName
                } else {
                    "$playlistName - $originalGroup"
                }
                
                // Create a new map with the updated group-title so that they are categorized correctly on home screen
                val newAttributes = item.attributes.toMutableMap().apply {
                    put("group-title", newGroup)
                }
                
                allItems.add(item.copy(attributes = newAttributes))
            }
        }
        
        Playlist(allItems)
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

        val grouped = playlist.items.groupBy { it.attributes["group-title"] ?: "Channels" }

        val lists = grouped.map { (group, items) ->
            HomePageList(
                group,
                items.map { item ->
                    newLiveSearchResponse(
                        item.title,
                        item.url,
                        TvType.Live
                    ) {
                        this.posterUrl = item.attributes["tvg-logo"]
                    }
                }
            )
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val playlist = fetchPlaylist()
        return playlist.items
            .filter { it.title.contains(query, ignoreCase = true) }
            .map { item ->
                newLiveSearchResponse(
                    item.title,
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

        return newLiveStreamLoadResponse(
            title,
            url,
            url
        ) {
            this.posterUrl = logoUrl
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

        val headers = item?.headers ?: emptyMap()
        val kodiProps = item?.kodiProps ?: emptyMap()
        val licenseType = kodiProps["inputstream.adaptive.license_type"]
        val licenseKey = kodiProps["inputstream.adaptive.license_key"]

        if (licenseType != null || licenseKey != null) {
            var finalLicenseUrl: String? = kodiProps["inputstream.adaptive.license_url"]
            val keyRequestHeaders = java.util.HashMap<String, String>()

            // Copy stream headers to key request headers (very useful for User-Agent, Origin, authorization tokens)
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
                    
                    // It is a license server URL (Widevine/Clearkey URL)
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
                    // Clearkey inline kid:key
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
