package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.xr3edTV.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

class Xr3edTVProvider : MainAPI() {

    override var name = "xr3ed TV"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // Worker URL & token — dari GitHub Secrets via BuildConfig, tidak hardcode
    private val WORKER_URL = "${BuildConfig.XR3EDTV_WORKER_URL}/?t=${BuildConfig.XR3EDTV_WORKER_TOKEN}"

    // ── Data class ────────────────────────────────────────────────
    data class Channel(
        val id: String,
        val name: String,
        val tagline: String,
        val hlsUrl: String,
        val jenis: String,       // hls / dash / dash-clearkey
        val urlLicense: String?,
        val headerIptv: Map<String, String>,
        val premium: Boolean
    )

    // ── Parse header_iptv (JSON string) ───────────────────────────
    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank() || raw == "none") return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { k ->
                val v = obj.getString(k)
                if (v.isNotBlank() && v != "none") map[k] = v
            }
            map
        } catch (e: Exception) { emptyMap() }
    }

    // ── Fetch & parse playlist dari Worker ────────────────────────
    private suspend fun fetchChannels(): List<Channel> {
        val raw = app.get(WORKER_URL).text
        val obj = JSONObject(raw)
        val info: JSONArray = obj.getJSONArray("info")

        return (0 until info.length()).mapNotNull { i ->
            try {
                val ch = info.getJSONObject(i)
                val hls = ch.optString("hls", "")
                if (hls.isBlank()) return@mapNotNull null
                Channel(
                    id         = ch.optString("id", i.toString()),
                    name       = ch.optString("name", "Channel $i"),
                    tagline    = ch.optString("tagline", ""),
                    hlsUrl     = hls,
                    jenis      = ch.optString("jenis", "hls"),
                    urlLicense = ch.optString("url_license", "").takeIf { it.isNotBlank() },
                    headerIptv = parseHeaders(ch.optString("header_iptv", "")),
                    premium    = ch.optString("premium", "f") == "t"
                )
            } catch (e: Exception) { null }
        }
    }

    private val ICON = "https://cdn.jsdelivr.net/gh/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream@main/live_icon.png"

    // ── Main Page ─────────────────────────────────────────────────
    override val mainPage = mainPageOf("all" to "Sports TV Live")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = fetchChannels()
        val items = channels.map { ch ->
            val label = if (ch.premium) "⭐ ${ch.name}" else ch.name
            newLiveSearchResponse(label, ch.id, TvType.Live) {
                posterUrl = ICON
            }
        }
        return newHomePageResponse(
            HomePageList("Sports TV Live (${channels.size} channel)", items, isHorizontalImages = false),
            hasNext = false
        )
    }

    // ── Search ────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val channels = fetchChannels()
        return channels
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { ch ->
                newLiveSearchResponse(ch.name, ch.id, TvType.Live) {
                    posterUrl = ICON
                }
            }
    }

    // ── Load ──────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val channels = fetchChannels()
        val ch = channels.firstOrNull { it.id == url }
            ?: throw ErrorLoadingException("Channel tidak ditemukan")

        return newLiveStreamLoadResponse(ch.name, url, ch.id) {
            posterUrl = ICON
            plot = ch.tagline.takeIf { it.isNotBlank() }
        }
    }

    // ── Load Links ────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channels = fetchChannels()
        val ch = channels.firstOrNull { it.id == data } ?: return false
        val headers = ch.headerIptv

        when (ch.jenis) {
            "hls" -> {
                callback(
                    newExtractorLink(
                        source  = this.name,
                        name    = ch.name,
                        url     = ch.hlsUrl,
                        type    = ExtractorLinkType.M3U8
                    ) {
                        referer = headers["Referer"] ?: ""
                        quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
            }
            "dash", "dash-clearkey" -> {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name   = ch.name,
                        url    = ch.hlsUrl,
                        type   = ExtractorLinkType.DASH
                    ) {
                        referer = headers["Referer"] ?: ""
                        quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
            }
        }
        return true
    }
}
