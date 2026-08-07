package com.xr3ed.klikxxixr

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
data class ResolvedPlayerLink(val url: String, val referer: String, val source: String)

object KlikXXiExtractors {

    private val sf21Key = "kiemtienmua911ca".toByteArray()
    private val sf21Iv = "1234567890oiuytr".toByteArray()

    fun cleanPlayerUrl(url: String): String {
        val uri = try { URI(url) } catch (_: Throwable) { null } ?: return ""
        val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        val path = uri.path.orEmpty().trimEnd('/')
        val fragment = uri.fragment.orEmpty().substringBefore("&").substringBefore("?")
        val query = uri.query.orEmpty().substringBefore("dl=")
        return "$host$path#$fragment?$query".trimEnd('#', '?')
    }

    fun String.isM3u8Like(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".m3u8") || lower.contains("m3u8") || lower.contains("/hls/") || lower.contains("/stream/") || lower.contains("/play/token_hash") || lower.contains("master.txt") || lower.contains(".urlset/master")
    }

    fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("facebook.com") || lower.contains("telegram") || lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("whatsapp") || lower.contains("mailto:") || lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("doubleclick") || lower.contains("googlesyndication") || lower.contains("google-analytics") || lower.contains("/wp-content/") || lower.contains("/wp-json/") || lower.contains(".css") || lower.contains(".js") || lower.contains("favicon") || lower.contains("logo") || lower.contains("arwana") || lower.contains("slot") || lower.contains("togel") || lower.contains("bet")
    }

    fun isPlayableMedia(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.isM3u8Like() || lower.contains(".mp4") || lower.contains(".webm") || lower.contains("videoplayback") || lower.contains("mime=video") || (lower.contains("googlevideo.com") && lower.contains("videoplayback")) || lower.contains("321watch.workers.dev")
    }

    fun qualityFromUrl(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1440") || lower.contains("2k") -> Qualities.P1440.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    fun origin(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Throwable) { "" }

    fun urlDecode(value: String): String = try { URLDecoder.decode(value, "UTF-8") } catch (_: Throwable) { value }

    fun normalize(value: String): String = urlDecode(value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&"))

    fun decodeBase64(value: String): String? {
        val raw = value.trim()
        val data = try {
            Base64.decode(raw, Base64.DEFAULT)
        } catch (_: Throwable) {
            try {
                Base64.decode(raw, Base64.URL_SAFE)
            } catch (_: Throwable) {
                null
            }
        } ?: return null
        return try { String(data, StandardCharsets.UTF_8) } catch (_: Throwable) { null }
    }

    fun fixUrl(value: String?, baseUrl: String): String? {
        val raw = urlDecode(value.orEmpty().replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim().trim('"', '\'', ',', ';'))
        if (raw.isBlank() || raw == "#" || raw.equals("null", true) || raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true) || raw.startsWith("tel:", true) || raw.startsWith("data:", true) || raw.startsWith("blob:", true) || raw.startsWith("about:", true)) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> origin(baseUrl) + raw
            else -> try { URI(baseUrl).resolve(raw).toString() } catch (_: Throwable) { origin(baseUrl) + "/" + raw.trimStart('/') }
        }
    }

    suspend fun resolvePlayerLinks(
        url: String,
        referer: String,
        tabName: String,
        headers: Map<String, String>,
        mainUrl: String
    ): List<ResolvedPlayerLink> {
        val fixed = fixUrl(url, referer) ?: return emptyList()
        val host = try { URI(fixed).host.orEmpty().lowercase(Locale.ROOT) } catch (_: Throwable) { return emptyList() }
        val sourceName = if (tabName.isNotEmpty()) tabName else "KlikXXiXR"
        return when {
            host.contains("sf21.vidplayer.live") -> resolveSf21Player(fixed, referer, sourceName, headers, mainUrl)
            host.contains("upload18.org") || host.contains("upload18.cc") -> resolveUpload18Player(fixed, referer, sourceName, headers)
            host.contains("hgcloud.to") || host.contains("audinifer.com") || host.contains("vibuxer.com") || host.contains("streamhg.co") -> resolveVibuxerPlayer(fixed, referer, sourceName)
            host.contains("strp2p.site") || host.contains("upns.one") -> resolveUpnsInfoPlayer(fixed, sourceName, mainUrl, headers)
            host.contains("hexload.com") -> resolveHexloadPlayer(fixed, sourceName, headers, mainUrl)
            else -> emptyList()
        }
    }

    private suspend fun resolveSf21Player(url: String, referer: String, sourceName: String, headers: Map<String, String>, mainUrl: String): List<ResolvedPlayerLink> {
        val uri = try { URI(url) } catch (_: Throwable) { return emptyList() }
        val id = uri.rawFragment?.substringBefore("&")?.substringBefore("?")?.trim().orEmpty()
            .ifBlank {
                Regex("""(?i)(?:[?&]id=|/)([a-z0-9]{4,12})(?:[&#/?]|$)""").find(url)?.groupValues?.getOrNull(1).orEmpty()
            }
        if (id.isBlank()) return emptyList()
        val playerOrigin = "https://sf21.vidplayer.live"
        val sourceHost = runCatching { URI(referer).host.orEmpty().removePrefix("www.") }.getOrNull().orEmpty().ifBlank { try { URI(mainUrl).host.orEmpty() } catch (_: Throwable) { "klikxxi.shop" } }
        val apiUrl = "$playerOrigin/api/v1/video?id=$id&w=1280&h=720&r=$sourceHost"
        val encrypted = try {
            app.get(apiUrl, headers = headers + mapOf("Accept" to "*/*", "Referer" to "$playerOrigin/"), referer = "$playerOrigin/").text
        } catch (_: Throwable) { return emptyList() }
        val json = decryptSf21Payload(encrypted) ?: return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val links = linkedSetOf<String>()
        val hls = obj.optString("hlsVideoTiktok").takeIf { it.isNotBlank() }?.let { fixUrl(it, playerOrigin) }
        if (hls != null) {
            links.add(hls)
        } else {
            obj.optString("source").takeIf { it.isNotBlank() }?.let { fixUrl(it, playerOrigin)?.let(links::add) }
        }
        return links.filter { isPlayableMedia(it) }.map { ResolvedPlayerLink(it, "$playerOrigin/", sourceName) }
    }

    private suspend fun resolveUpload18Player(url: String, referer: String, sourceName: String, headers: Map<String, String>): List<ResolvedPlayerLink> {
        val fixed = fixUrl(url, referer) ?: return emptyList()
        val playerOrigin = origin(fixed)
        if (isPlayableMedia(fixed)) {
            return listOf(ResolvedPlayerLink(fixed, "$playerOrigin/", sourceName))
        }
        val html = try {
            val response = app.get(
                fixed,
                headers = headers + mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to referer
                ),
                referer = referer
            )
            normalize(response.text.ifBlank { response.document.html() })
        } catch (_: Throwable) {
            return emptyList()
        }
        val links = linkedSetOf<String>()
        collectLinksFromHtml(html, fixed).filter { isPlayableMedia(it) }.forEach { links.add(it) }
        Regex("""(?i)(?:m3u8|file|source)\s*[:=]\s*['"]([^'"]+)['"]""")
            .findAll(html)
            .mapNotNull { decodePossibleUrl(it.groupValues[1], fixed) }
            .filter { isPlayableMedia(it) }
            .forEach { links.add(it) }
        Regex("""(?i)PLAYER_CONFIG[\s\S]{0,3000}?/play/token_hash\?[^'"]+""")
            .findAll(html)
            .mapNotNull { Regex("""/play/token_hash\?[^'"]+""").find(it.value)?.value }
            .mapNotNull { fixUrl(it, fixed) }
            .filter { isPlayableMedia(it) }
            .forEach { links.add(it) }
        return links.map { ResolvedPlayerLink(it, "$playerOrigin/", sourceName) }
    }

    private suspend fun resolveVibuxerPlayer(url: String, referer: String, sourceName: String): List<ResolvedPlayerLink> {
        val rewritten = url.replace("hgcloud.to", "audinifer.com")
        val fixed = fixUrl(rewritten, referer) ?: return emptyList()
        val host = try { URI(fixed).host.orEmpty().lowercase(Locale.ROOT) } catch (_: Throwable) { return emptyList() }
        val response = try {
            app.get(fixed, headers = mapOf("Referer" to referer), referer = referer)
        } catch (_: Throwable) { return emptyList() }
        val finalUrl = response.url
        val finalHost = try { URI(finalUrl).host.orEmpty().lowercase(Locale.ROOT) } catch (_: Throwable) { host }
        val html = response.text
        val pattern = Regex("""eval\(function\(p,a,c,k,e,[rd]\)\{.*return p;?\}\('(.*?)',(\d+),(\d+),'([^']*)'\.split\('\|'\)\)""", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(html) ?: return emptyList()
        val p = match.groupValues[1]
        val a = match.groupValues[2].toIntOrNull() ?: 36
        val c = match.groupValues[3].toIntOrNull() ?: 0
        val k = match.groupValues[4].split("|")
        val wordPattern = Regex("""\b[0-9a-zA-Z_]+\b""")
        val unpacked = wordPattern.replace(p) { m ->
            val idx = m.value.toIntOrNull(a) ?: -1
            if (idx in 0 until k.size && k[idx].isNotEmpty()) {
                k[idx]
            } else {
                m.value
            }
        }
        val streamUrl = Regex("""https?://[a-zA-Z0-9.-]+/[^"']*?\.urlset/master\.(?:txt|m3u8)""").find(unpacked)?.value
            ?: Regex("""https?://[a-zA-Z0-9.-]+/[^"']*?/master\.(?:txt|m3u8)""").find(unpacked)?.value
            ?: Regex("""["'](https?://[^"']*?\.m3u8[^"']*?)["']""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return emptyList()
        val finalSource = if (sourceName.isNotEmpty()) sourceName else if (finalHost.contains("vibuxer")) "Vibuxer" else if (finalHost.contains("hgcloud")) "Hgcloud" else "Streamwish"
        return listOf(ResolvedPlayerLink(streamUrl, "https://$finalHost/", finalSource))
    }

    private suspend fun resolveUpnsInfoPlayer(url: String, sourceName: String, mainUrl: String, headers: Map<String, String>): List<ResolvedPlayerLink> {
        val fixed = fixUrl(url, "") ?: return emptyList()
        val uri = try { URI(fixed) } catch (_: Throwable) { return emptyList() }
        val host = uri.host.orEmpty()
        val id = uri.rawFragment?.substringBefore("&")?.substringBefore("?")?.trim().orEmpty()
            .ifBlank {
                Regex("""(?i)(?:[?&]id=|/)([a-z0-9]{4,12})(?:[&#/?]|$)""").find(fixed)?.groupValues?.getOrNull(1).orEmpty()
            }
        if (id.isBlank()) return emptyList()
        val apiUrl = "https://$host/api/v1/video?id=$id&w=1280&h=720&r=klikxxi.shop"
        val encrypted = try {
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url(apiUrl)
                .header("Referer", mainUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .build()
            client.newCall(request).execute().body?.string().orEmpty()
        } catch (_: Throwable) { return emptyList() }
        if (encrypted.isBlank()) return emptyList()
        val decryptedJson = try {
            val keyStr = com.xr3ed.klikxxixr.BuildConfig.KLIKXXI_AES_KEY.takeIf { it.isNotEmpty() } ?: "kiemtienmua911ca"
            val keyBytes = keyStr.toByteArray()
            val ivBytes = getUpnsIv(fixed)
            val cipherBytes = encrypted.trim().replace("\"", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(cipherBytes))
        } catch (e: Throwable) {
            return emptyList()
        }
        val obj = try { JSONObject(decryptedJson) } catch (_: Throwable) { return emptyList() }
        val links = linkedSetOf<String>()
        obj.optString("cfNative").takeIf { it.isNotBlank() }?.let { fixUrl(it, "https://$host/")?.let(links::add) }
        obj.optString("source").takeIf { it.isNotBlank() }?.let { fixUrl(it, "https://$host/")?.let(links::add) }
        obj.optString("cf").takeIf { it.isNotBlank() }?.let { fixUrl(it, "https://$host/")?.let(links::add) }
        val hls = obj.optString("hls").takeIf { it.isNotBlank() }?.let { fixUrl(it, "https://$host/") }
        if (hls != null) {
            links.add(hls)
        } else {
            obj.optString("mp4").takeIf { it.isNotBlank() }?.let { fixUrl(it, "https://$host/")?.let(links::add) }
            obj.optString("stream").takeIf { it.isNotBlank() }?.let { fixUrl(it, "https://$host/")?.let(links::add) }
            obj.optString("direct").takeIf { it.isNotBlank() }?.let { fixUrl(it, "https://$host/")?.let(links::add) }
            val sources = obj.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val sObj = sources.optJSONObject(i) ?: continue
                    sObj.optString("file").takeIf { it.isNotBlank() }?.let { fixUrl(it, "https://$host/")?.let(links::add) }
                }
            }
        }
        val hostName = if (host.contains("strp2p")) "Strp2p" else "Upns"
        val finalSource = if (sourceName.isNotEmpty()) sourceName else hostName
        return links.filter { isPlayableMedia(it) }.map { ResolvedPlayerLink(it, "https://$host/", finalSource) }
    }

    private suspend fun resolveHexloadPlayer(url: String, sourceName: String, headers: Map<String, String>, mainUrl: String): List<ResolvedPlayerLink> {
        val fixed = fixUrl(url, mainUrl) ?: return emptyList()
        val id = Regex("""/embed-([a-zA-Z0-9]+)""").find(fixed)?.groupValues?.getOrNull(1) ?: return emptyList()
        val response = try {
            app.post(
                "https://hexload.com/download",
                data = mapOf(
                    "op" to "download3",
                    "id" to id,
                    "ajax" to "1",
                    "method_free" to "1",
                    "dataType" to "json"
                ),
                headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = fixed
            ).text
        } catch (_: Throwable) { "" }
        val obj = try { JSONObject(response) } catch (_: Throwable) { null } ?: return emptyList()
        if (obj.optString("msg") == "OK") {
            val result = obj.optJSONObject("result") ?: return emptyList()
            val videoUrl = result.optString("url")
            if (videoUrl.isNotBlank()) {
                val finalSource = if (sourceName.isNotEmpty()) sourceName else "Hexload"
                return listOf(ResolvedPlayerLink(videoUrl, "https://hexload.com/", finalSource))
            }
        }
        return emptyList()
    }

    private fun decryptSf21Payload(value: String): String? = runCatching {
        val cipherBytes = value.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sf21Key, "AES"), IvParameterSpec(sf21Iv))
        String(cipher.doFinal(cipherBytes))
    }.getOrNull()

    private fun getUpnsIv(url: String): ByteArray {
        val E = "https:"
        val R = E + "//"
        val hostname = try { URI(url).host.orEmpty() } catch (_: Throwable) { "" }
        val G = E.length * R.length
        val F = 1
        var B = ""
        for (Se in F until 10) {
            B += (Se + G).toChar()
        }
        val oe = "111"
        val ye = 3 * (hostname.firstOrNull()?.code ?: 0)
        val Ve = 111 * F + E.length
        val P = Ve + 4
        val X = E.getOrNull(F)?.code ?: 0
        val me = X * F - 2
        val suffix = stringFromCharCodes(G, oe.toInt(), ye, Ve, P, X, me)
        val fullB = B + suffix
        return fullB.toByteArray(Charsets.UTF_8).sliceArray(0 until 16)
    }

    private fun stringFromCharCodes(vararg codes: Int): String {
        val sb = StringBuilder()
        for (code in codes) {
            sb.append(code.toChar())
        }
        return sb.toString()
    }

    fun collectLinksFromHtml(html: String, baseUrl: String): List<String> {
        val normalized = normalize(html)
        val links = linkedSetOf<String>()
        val parsed = try { Jsoup.parse(normalized, baseUrl) } catch (_: Throwable) { null }
        parsed?.let { collectElementLinks(it, baseUrl).forEach { link -> links.add(link) } }
        directMedia(normalized, baseUrl).forEach { links.add(it) }
        iframeLinks(normalized, baseUrl).forEach { links.add(it) }
        embeddedLinks(normalized, baseUrl).forEach { links.add(it) }
        base64Links(normalized, baseUrl).forEach { links.add(it) }
        Regex("(?i)\"(?:embed_url|iframe_url|player_url|url|src|file|source|link|m3u8|hls|hlsVideoTiktok)\"\\s*:\\s*\"([^\"]+)\"").findAll(normalized).mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        Regex("""(?i)(?:embed_url|iframe_url|player_url|url|src|file|source|link|m3u8|hls|hlsVideoTiktok)\s*[:=]\s*['"]([^'"]+)['"]""").findAll(normalized).mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        Regex("""(?i)['"]([^'"]*/play/token_hash\?[^'"]+)['"]""").findAll(normalized).mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        buildXFileShareStream(normalized, baseUrl)?.let { links.add(it) }
        return links.toList()
    }

    fun collectElementLinks(document: Document, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select(
            "#player iframe[src], #player iframe[data-src], .player iframe[src], .player iframe[data-src], [id*=player] iframe[src], [class*=player] iframe[src], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], video[src], video source[src], source[src], " +
                "a[href*='embed'], a[href*='player'], a[href*='play/index'], a[href*='stream'], a[href*='drive'], a[href*='gofile'], a[href*='dood'], a[href*='streamtape'], " +
                "a[href*='filemoon'], a[href*='vidhide'], a[href*='vidguard'], a[href*='voe'], a[href*='mp4upload'], a[href*='uqload'], a[href*='krakenfiles'], " +
                "a[href*='filelions'], a[href*='hubcloud'], a[href*='gdplayer'], a[href*='gdriveplayer'], a[href*='upload18'], a[href*='workers.dev'], a[href*='sht'], a[href*='short'], a[href*='morencius.com'], a[href*='turbovidhls.com'], a[href*='.mp4'], a[href*='.m3u8']"
        ).forEach { element ->
            val src = element.attr("data-src").ifBlank { element.attr("src") }.ifBlank { element.attr("data-litespeed-src") }.ifBlank { element.attr("href") }
            fixUrl(src, baseUrl)?.let { links.add(it) }
        }
        return links.toList()
    }

    fun iframeLinks(html: String, baseUrl: String): List<String> {
        return Regex("""<iframe[^>]+(?:src|data-src)=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .toList()
    }

    fun embeddedLinks(html: String, baseUrl: String): List<String> {
        return Regex("""<embed[^>]+src=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .toList()
    }

    fun directMedia(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        Regex("""(?i)['"]((?:https?:)?//[^'"]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^'"]+|videoplayback[^'"]*|/hls/[^'"]+|/stream/[^'"]+|/play/token_hash\?[^'"]+)(?:\?[^'"]*)?)['"]""").findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }.filter { isPlayableMedia(it) }.forEach { links.add(it) }
        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^\s'"<>\\]+|videoplayback[^\s'"<>\\]*|/hls/[^\s'"<>\\]+|/stream/[^\s'"<>\\]+|/play/token_hash\?[^\s'"<>\\]+)(?:\?[^\s'"<>\\]*)?"""").findAll(html)
            .mapNotNull { fixUrl(it.value, baseUrl) }.filter { isPlayableMedia(it) }.forEach { links.add(it) }
        Regex("""https?%3A%2F%2F[^\s'"<>]+""", RegexOption.IGNORE_CASE).findAll(html)
            .mapNotNull { fixUrl(urlDecode(it.value), baseUrl) }.filter { isPlayableMedia(it) }.forEach { links.add(it) }
        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+?321watch\.workers\.dev/[^\s'"<>\\]+""").findAll(html)
            .mapNotNull { fixUrl(it.value, baseUrl) }.filter { isPlayableMedia(it) }.forEach { links.add(it) }
        return links.toList()
    }

    fun base64Links(html: String, baseUrl: String): List<String> {
        return Regex("""[\s'"=]([A-Za-z0-9+/=]{20,})""").findAll(html)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .flatMap { collectLinksFromHtml(it, baseUrl) }
            .toList()
    }

    fun decodePossibleUrl(value: String, baseUrl: String): String? {
        val decoded = urlDecode(value).replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim().trim('"', '\'', ',', ';')
        fixUrl(decoded, baseUrl)?.let { return it }
        decodeBase64(decoded)?.let { html ->
            directMedia(html, baseUrl).firstOrNull()?.let { return it }
            iframeLinks(html, baseUrl).firstOrNull()?.let { return it }
            embeddedLinks(html, baseUrl).firstOrNull()?.let { return it }
            if (html.startsWith("http", true) || html.startsWith("//")) fixUrl(html, baseUrl)?.let { return it }
        }
        return null
    }

    fun buildXFileShareStream(html: String, baseUrl: String): String? {
        val host = runCatching { URI(baseUrl).host.orEmpty() }.getOrNull().orEmpty()
        if (!host.contains("minochinos.com", true) && !host.contains("earnvidjav.online", true)) return null
        val fileId = Regex("""\$\.cookie\(['"]file_id['"]\s*,\s*['"](\d+)['"]""").find(html)?.groupValues?.getOrNull(1) ?: return null
        val stream = Regex("""\|(\d{10})\|([a-z0-9]+)\|([A-Za-z0-9_-]{16,})\|""").findAll(html)
            .map { it.groupValues }
            .firstOrNull { it[3].length >= 20 } ?: return null
        return "${origin(baseUrl)}/stream/${stream[3]}/${stream[2]}/${stream[1]}/$fileId/master.m3u8"
    }
}
