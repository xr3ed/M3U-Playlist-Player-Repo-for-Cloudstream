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

    // Base Worker URL — dari BuildConfig
    private val BASE_WORKER_URL = BuildConfig.XR3EDTV_WORKER_URL
    private val WORKER_TOKEN = BuildConfig.XR3EDTV_WORKER_TOKEN

    private fun workerUrl(country: String): String {
        val cleanCountry = if (country == "all" || country.isBlank()) "SP" else country
        return "$BASE_WORKER_URL/?t=$WORKER_TOKEN&c=$cleanCountry&cb=${System.currentTimeMillis()}"
    }

    private fun logoUrl(id: String, country: String): String {
        val cleanCountry = if (country == "all" || country.isBlank()) "SP" else country
        return "$BASE_WORKER_URL/?t=$WORKER_TOKEN&logo=$id&c=$cleanCountry&v=1"
    }

    // ── Data class ────────────────────────────────────────────────
    data class Channel(
        val id: String,
        val name: String,
        val tagline: String,
        val hlsUrl: String,
        val jenis: String,       // hls / dash / dash-clearkey
        val urlLicense: String?,
        val headerIptv: Map<String, String>,
        val premium: Boolean,
        val fakeEvent: Boolean
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
    private suspend fun fetchChannels(country: String): List<Channel> {
        val cleanCountry = if (country == "all" || country.isBlank()) "SP" else country
        val url = workerUrl(cleanCountry)
        val raw = try {
            app.get(url).text
        } catch (e: Exception) {
            throw ErrorLoadingException("HTTP error: ${e.message}")
        }

        try {
            val obj = JSONObject(raw)
            val info: JSONArray = obj.getJSONArray("info")

            return (0 until info.length()).mapNotNull { i ->
                try {
                    val ch = info.getJSONObject(i)
                    val hls = ch.optString("hls", "")
                    if (hls.isBlank()) return@mapNotNull null
                    val rawName = ch.optString("name", "Channel $i")
                    val tagline = ch.optString("tagline", "")
                    val isFakeEvent = ch.optString("fake_event", "f") == "t"
                    val displayName = if (isFakeEvent && tagline.isNotBlank()) tagline else rawName
                    Channel(
                        id         = ch.optString("id", i.toString()),
                        name       = displayName,
                        tagline    = tagline,
                        hlsUrl     = hls,
                        jenis      = ch.optString("jenis", "hls"),
                        urlLicense = ch.optString("url_license", "").takeIf { it.isNotBlank() },
                        headerIptv = parseHeaders(ch.optString("header_iptv", "")),
                        premium    = ch.optString("premium", "f") == "t",
                        fakeEvent  = isFakeEvent
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            val preview = if (raw.length > 200) raw.substring(0, 200) else raw
            throw ErrorLoadingException("JSON error: ${e.message}. Raw: $preview")
        }
    }

    // ── Main Page (Dynamic Tabs / Categories) ─────────────────────
    override val mainPage = mainPageOf(
        "AN" to "🏆 FIFA World Cup 2026",
        "EV" to "📅 Events",
        "SP" to "⚽ Sports TV",
        "ID" to "🇮🇩 Indonesia",
        "RI" to "📺 TVRI",
        "LO" to "📡 TV Lokal"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val country = request.data
        var channels = fetchChannels(country)
        
        // Filter out fake events (except Events EV and World Cup AN)
        if (country != "EV" && country != "AN") {
            channels = channels.filter { !it.fakeEvent }
        }

        val items = channels.map { ch ->
            val label = if (ch.premium) "⭐ ${ch.name}" else ch.name
            // We embed country in URL so load() can retrieve it
            val uniqueUrl = "$country:::${ch.id}"
            newLiveSearchResponse(label, uniqueUrl, TvType.Live) {
                posterUrl = logoUrl(ch.id, country)
            }
        }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = false
        )
    }

    // ── Search ────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        // Search SP and ID in parallel to find matching channels
        val searchCountries = listOf("SP", "ID", "MY", "SG")
        val allChannels = searchCountries.flatMap { country ->
            try {
                fetchChannels(country).map { country to it }
            } catch (e: Exception) {
                emptyList()
            }
        }.distinctBy { it.second.id }

        return allChannels
            .filter { it.second.name.contains(query, ignoreCase = true) }
            .map { (country, ch) ->
                val uniqueUrl = "$country:::${ch.id}"
                newLiveSearchResponse(ch.name, uniqueUrl, TvType.Live) {
                    posterUrl = logoUrl(ch.id, country)
                }
            }
    }

    // ── Load ──────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringAfterLast("NONE/").substringAfterLast("none/")
        val parts = cleanUrl.split(":::")
        val country = if (parts.size > 1) parts[0] else "SP"
        val id = if (parts.size > 1) parts[1] else cleanUrl

        val channels = fetchChannels(country)
        val ch = channels.firstOrNull { it.id == id }
            ?: throw ErrorLoadingException("Channel tidak ditemukan")

        return newLiveStreamLoadResponse(ch.name, url, url) {
            posterUrl = logoUrl(ch.id, country)
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
        val cleanData = data.substringAfterLast("NONE/").substringAfterLast("none/")
        val parts = cleanData.split(":::")
        val country = if (parts.size > 1) parts[0] else "SP"
        val id = if (parts.size > 1) parts[1] else cleanData

        val channels = fetchChannels(country)
        val ch = channels.firstOrNull { it.id == id } ?: return false
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
            "dash" -> {
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
            "dash-clearkey" -> {
                var clearkeyKid: String? = null
                var clearkeyKey: String? = null
                val licenseStr = ch.urlLicense
                if (!licenseStr.isNullOrBlank()) {
                    try {
                        val decodedLicense = String(android.util.Base64.decode(licenseStr, android.util.Base64.DEFAULT))
                        val licenseObj = JSONObject(decodedLicense)
                        val keysArr = licenseObj.optJSONArray("keys")
                        if (keysArr != null && keysArr.length() > 0) {
                            val keyObj = keysArr.getJSONObject(0)
                            clearkeyKid = keyObj.getString("kid").replace('+', '-').replace('/', '_').replace("=", "")
                            clearkeyKey = keyObj.getString("k").replace('+', '-').replace('/', '_').replace("=", "")
                        }
                    } catch (e: Exception) {
                        // ignore or fallback
                    }
                }

                callback(
                    newDrmExtractorLink(
                        source  = this.name,
                        name    = ch.name,
                        url     = ch.hlsUrl,
                        type    = ExtractorLinkType.DASH,
                        uuid    = CLEARKEY_UUID
                    ) {
                        referer = headers["Referer"] ?: ""
                        quality = Qualities.Unknown.value
                        this.headers = headers
                        this.kty = "oct"
                        if (clearkeyKid != null) this.kid = clearkeyKid
                        if (clearkeyKey != null) this.key = clearkeyKey
                    }
                )
            }
        }
        return true
    }
}
