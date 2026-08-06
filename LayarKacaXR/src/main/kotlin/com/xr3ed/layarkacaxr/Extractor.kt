package com.xr3ed.layarkacaxr

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

open class LayarKacaHtmlExtractor : ExtractorApi() {
    override var name = "LayarKaca HTML"
    override var mainUrl = ""
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = url.replace(" ", "%20")
        val domain = runCatching {
            "https://${URI(pageUrl).host}"
        }.getOrDefault(mainUrl.ifBlank { pageUrl })

        val response = runCatching {
            app.get(
                pageUrl,
                referer = referer ?: domain,
                headers = defaultExtractorHeaders(referer ?: domain),
                timeout = 20L
            )
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        if (html.trimStart().startsWith("#EXTM3U")) {
            emitExtractorLink(name, pageUrl, referer ?: domain, callback)
            return
        }

        response.document.select(
            "meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], " +
                "meta[name=twitter:player], iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "video[src], video[data-src], video source[src], source[src], embed[src], object[data], " +
                "a[href], [data-src], [data-file], [data-video], [data-url], [data-embed]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            addExtractorCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        extractExtractorUrls(html).forEach { raw ->
            addExtractorCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractExtractorUrls(unpacked.cleanEscaped()).forEach { raw ->
                addExtractorCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        extractSubtitles(html, pageUrl).forEach(subtitleCallback)

        directLinks.distinct().forEach { link ->
            emitExtractorLink(name, link, pageUrl, callback)
        }

        if (directLinks.isNotEmpty()) return

        embedLinks
            .filterNot { it == pageUrl }
            .filterNot { isJunkExtractorUrl(it) }
            .distinct()
            .take(6)
            .forEach { embed ->
                val nested = runCatching {
                    app.get(
                        embed,
                        referer = pageUrl,
                        headers = defaultExtractorHeaders(pageUrl),
                        timeout = 15L
                    ).text.cleanEscaped()
                }.getOrNull().orEmpty()

                extractExtractorUrls(nested).forEach { raw ->
                    val fixed = normalizeExtractorUrl(raw, embed).replace(".txt", ".m3u8")
                    if (fixed.isDirectVideoUrl()) {
                        emitExtractorLink(name, fixed, embed, callback)
                    }
                }

                val nestedUnpacked = runCatching {
                    if (!getPacked(nested).isNullOrEmpty()) getAndUnpack(nested) else null
                }.getOrNull()

                if (!nestedUnpacked.isNullOrBlank()) {
                    extractExtractorUrls(nestedUnpacked.cleanEscaped()).forEach { raw ->
                        val fixed = normalizeExtractorUrl(raw, embed).replace(".txt", ".m3u8")
                        if (fixed.isDirectVideoUrl()) {
                            emitExtractorLink(name, fixed, embed, callback)
                        }
                    }
                }

                extractSubtitles(nested, embed).forEach(subtitleCallback)
            }
    }
}

class EmturbovidExtractor : LayarKacaHtmlExtractor() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
}

class P2PExtractor : LayarKacaHtmlExtractor() {
    override var name = "P2P"
    override var mainUrl = "https://cloud.hownetwork.xyz"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        super.getUrl(url, referer, subtitleCallback, callback)

        val id = url.substringAfter("id=", "")
            .substringBefore("&")
            .substringBefore("?")
            .trim()

        if (id.isBlank()) return

        val apiUrl = "$mainUrl/api2.php?id=$id"
        val text = runCatching {
            app.post(
                apiUrl,
                data = mapOf(
                    "r" to (referer ?: "https://playeriframe.sbs/"),
                    "d" to "cloud.hownetwork.xyz"
                ),
                referer = url,
                headers = defaultExtractorHeaders(url) + mapOf("X-Requested-With" to "XMLHttpRequest"),
                timeout = 15L
            ).text.cleanEscaped()
        }.getOrNull().orEmpty()

        parseJsonStream(text)?.let { stream ->
            emitExtractorLink(name, normalizeExtractorUrl(stream, url), mainUrl, callback)
        }

        extractExtractorUrls(text).forEach { raw ->
            emitExtractorLink(name, normalizeExtractorUrl(raw, url), mainUrl, callback)
        }
    }
}

class F16Extractor : LayarKacaHtmlExtractor() {
    override var name = "F16"
    override var mainUrl = "https://f16px.com"
}

class Jeniusplay : LayarKacaHtmlExtractor() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        super.getUrl(url, referer, subtitleCallback, callback)

        val pageUrl = url.replace(" ", "%20")
        val hash = pageUrl.substringAfter("data=", pageUrl.substringAfterLast("/"))
            .substringBefore("&")
            .substringBefore("?")
            .trim()

        if (hash.isBlank()) return

        val endpoints = listOf(
            "$mainUrl/player/ajax.php?data=$hash&do=getVideo",
            "$mainUrl/player/index.php?data=$hash&do=getVideo"
        )

        endpoints.forEach { endpoint ->
            val text = runCatching {
                app.post(
                    url = endpoint,
                    data = mapOf(
                        "hash" to hash,
                        "r" to (referer ?: "")
                    ),
                    referer = pageUrl,
                    headers = defaultExtractorHeaders(pageUrl) + mapOf("X-Requested-With" to "XMLHttpRequest"),
                    timeout = 15L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            parseJsonStream(text)?.let { stream ->
                emitExtractorLink(name, normalizeExtractorUrl(stream, pageUrl), pageUrl, callback)
            }

            extractExtractorUrls(text).forEach { raw ->
                emitExtractorLink(name, normalizeExtractorUrl(raw, pageUrl), pageUrl, callback)
            }

            extractSubtitles(text, pageUrl).forEach(subtitleCallback)
        }
    }
}

class Majorplay : LayarKacaHtmlExtractor() {
    override var name = "Majorplay"
    override var mainUrl = "https://majorplay.net"
}

class E2eMajorplay : LayarKacaHtmlExtractor() {
    override var name = "Majorplay E2E"
    override var mainUrl = "https://e2e.majorplay.net"
}

class M3u8Majorplay : LayarKacaHtmlExtractor() {
    override var name = "Majorplay M3U8"
    override var mainUrl = "https://m3u8.majorplay.net"
}

private fun addExtractorCandidate(
    raw: String,
    baseUrl: String,
    directLinks: MutableSet<String>,
    embedLinks: MutableSet<String>
) {
    if (raw.isBlank()) return

    val fixed = normalizeExtractorUrl(raw.cleanEscaped(), baseUrl)
        .replace(".txt", ".m3u8")
        .trim()

    if (fixed.isBlank() || isJunkExtractorUrl(fixed)) return

    when {
        fixed.isDirectVideoUrl() -> directLinks.add(fixed)
        fixed.startsWith("http", true) && isKnownExtractorHost(fixed) -> embedLinks.add(fixed)
        fixed.startsWith("http", true) && fixed.contains("embed", true) -> embedLinks.add(fixed)
        fixed.startsWith("http", true) && fixed.contains("player", true) -> embedLinks.add(fixed)
        fixed.startsWith("http", true) && fixed.contains("stream", true) -> embedLinks.add(fixed)
    }
}

private suspend fun emitExtractorLink(
    source: String,
    streamUrl: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
) {
    val fixed = streamUrl.cleanEscaped().replace(".txt", ".m3u8")
    if (isJunkExtractorUrl(fixed)) return

    if (fixed.contains(".m3u8", true)) {
        generateM3u8(
            source = source,
            streamUrl = fixed,
            referer = referer,
            headers = defaultExtractorHeaders(referer)
        ).forEach(callback)
    } else {
        callback(
            newExtractorLink(
                source = source,
                name = source,
                url = fixed,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(fixed).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(fixed)
                this.headers = defaultExtractorHeaders(referer)
            }
        )
    }
}

private fun parseJsonStream(text: String): String? {
    return runCatching {
        val json = JSONObject(text)
        listOf(
            json.optString("file"),
            json.optString("link"),
            json.optString("videoSource"),
            json.optString("securedLink"),
            json.optString("url"),
            json.optString("src")
        ).firstOrNull { it.isNotBlank() }
    }.getOrNull()
}

private fun extractExtractorUrls(text: String): List<String> {
    val clean = text.cleanEscaped()
    val urls = linkedSetOf<String>()

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { isJunkExtractorUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
        .filterNot { isJunkExtractorUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt|emturbovid|hownetwork|f16|jeniusplay|majorplay|streamwish|filemoon|dood|streamtape|vidhide|voe|mixdrop)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map {
            runCatching {
                URLDecoder.decode(it.value, "UTF-8")
            }.getOrDefault(it.value)
        }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { isJunkExtractorUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """(?:file|src|source|url|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|stream|streamUrl|stream_url|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filter {
            it.isDirectVideoUrl() ||
                isKnownExtractorHost(it) ||
                it.contains("embed", true) ||
                it.contains("player", true)
        }
        .filterNot { isJunkExtractorUrl(it) }
        .forEach { urls.add(it) }

    return urls.toList()
}

private suspend fun extractSubtitles(
    text: String,
    baseUrl: String
): List<SubtitleFile> {
    val clean = text.cleanEscaped()
    val results = mutableListOf<SubtitleFile>()

    Regex(
        """"(?:label|lang|language)"\s*:\s*"([^"]+)"[^}]*?"(?:file|url|path)"\s*:\s*"([^"]+\.(?:vtt|srt|ass)[^"]*)"""",
        RegexOption.IGNORE_CASE
    ).findAll(clean).forEach { match ->
        val label = match.groupValues[1].ifBlank { "Subtitle" }
        val url = normalizeExtractorUrl(match.groupValues[2], baseUrl)

        results.add(newSubtitleFile(label, url))
    }

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:vtt|srt|ass)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean).forEach { match ->
        results.add(newSubtitleFile("Subtitle", match.value.cleanEscaped()))
    }

    return results.distinctBy { it.url }
}

private fun isKnownExtractorHost(url: String): Boolean {
    val value = url.lowercase()

    return listOf(
        "emturbovid",
        "hownetwork",
        "playeriframe",
        "cloud.",
        "p2p",
        "f16",
        "jeniusplay",
        "majorplay",
        "e2e.majorplay",
        "m3u8.majorplay",
        "streamwish",
        "filemoon",
        "dood",
        "streamtape",
        "vidhide",
        "voe",
        "mixdrop",
        "hglink"
    ).any { value.contains(it) }
}

private fun isJunkExtractorUrl(url: String): Boolean {
    val value = url.lowercase()

    return value.isBlank() ||
        value.contains("facebook.com") ||
        value.contains("twitter.com") ||
        value.contains("telegram") ||
        value.contains("whatsapp") ||
        value.contains("mailto:") ||
        value.contains("trailer") ||
        value.contains("youtube.com") ||
        value.contains("youtu.be") ||
        value.contains("googletagmanager") ||
        value.contains("cloudflareinsights") ||
        value.contains("recaptcha") ||
        value.contains("doubleclick") ||
        value.contains("googlesyndication") ||
        value.contains("/ads/") ||
        value.contains("banner") ||
        value.contains("tracking") ||
        value.contains("analytics")
}

private fun String.isDirectVideoUrl(): Boolean {
    return contains(".m3u8", true) ||
        contains(".mp4", true) ||
        contains(".webm", true)
}

private fun normalizeExtractorUrl(
    url: String,
    baseUrl: String
): String {
    val clean = url.cleanEscaped().trim()

    return when {
        clean.isBlank() -> ""
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> "${getOrigin(baseUrl)}$clean"
        else -> runCatching {
            URI(baseUrl).resolve(clean).toString()
        }.getOrDefault("${getOrigin(baseUrl)}/${clean.trimStart('/')}")
    }
}

private fun getOrigin(url: String): String {
    return runCatching {
        URI(url).let { "${it.scheme}://${it.host}" }
    }.getOrDefault("")
}

private fun defaultExtractorHeaders(referer: String): Map<String, String> {
    return mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Referer" to referer,
        "Origin" to getOrigin(referer)
    )
}

private fun qualityFromUrl(url: String): Int {
    return when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

private fun String.cleanEscaped(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .trim()
}
