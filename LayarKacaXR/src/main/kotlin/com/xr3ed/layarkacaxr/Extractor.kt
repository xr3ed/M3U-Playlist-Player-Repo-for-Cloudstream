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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

class EmturbovidExtractor : ByseExtractor() {
    override var name = "TURBOVIP"
    override var mainUrl = "https://emturbovid.com"
}

class TurbovidhlsExtractor : ByseExtractor() {
    override var name = "TURBOVIP"
    override var mainUrl = "https://turbovidhls.com"
}

open class P2PExtractor : LayarKacaHtmlExtractor() {
    override var name = "P2P"
    override var mainUrl = "https://playcdn.de"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.contains("iframe/p2p/") && !url.contains("id=")) {
            val text = runCatching {
                app.get(
                    url,
                    referer = referer ?: mainUrl,
                    headers = defaultExtractorHeaders(referer ?: mainUrl),
                    timeout = 15L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            val nestedUrl = org.jsoup.Jsoup.parse(text).selectFirst("iframe[src]")?.attr("src")
                ?.cleanEscaped()
                ?.let { normalizeExtractorUrl(it, url) }

            if (!nestedUrl.isNullOrBlank()) {
                runCatching {
                    val custom = getCustomExtractor(nestedUrl)
                    if (custom != null) {
                        custom.getUrl(nestedUrl, url, subtitleCallback, callback)
                    } else {
                        com.lagradost.cloudstream3.utils.loadExtractor(
                            nestedUrl,
                            url,
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
            return
        }

        super.getUrl(url, referer, subtitleCallback, callback)

        var id = url.substringAfter("id=", "")
            .substringBefore("&")
            .substringBefore("?")
            .trim()

        if (id.isBlank() && url.contains("iframe/p2p/")) {
            id = url.substringAfter("iframe/p2p/")
                .substringBefore("&")
                .substringBefore("?")
                .trim()
        }

        if (id.isBlank()) return

        val apiDomain = if (url.contains("hownetwork.xyz")) "https://cloud.hownetwork.xyz" else "https://playcdn.de"
        val apiUrl = "$apiDomain/api2.php?id=$id"
        val text = runCatching {
            app.post(
                apiUrl,
                data = mapOf(
                    "r" to (referer ?: "https://videonode.de/"),
                    "d" to java.net.URI(apiDomain).host
                ),
                referer = url,
                headers = defaultExtractorHeaders(url) + mapOf("X-Requested-With" to "XMLHttpRequest"),
                timeout = 15L
            ).text.cleanEscaped()
        }.getOrNull().orEmpty()

        parseJsonStream(text)?.let { stream ->
            emitExtractorLink(name, normalizeExtractorUrl(stream, apiUrl), apiDomain, callback)
        }

        extractExtractorUrls(text).forEach { raw ->
            emitExtractorLink(name, normalizeExtractorUrl(raw, apiUrl), apiDomain, callback)
        }
    }
}

class VideonodeExtractor : P2PExtractor() {
    override var name = "P2P"
    override var mainUrl = "https://videonode.de"
}

class PlayerIframeExtractor : P2PExtractor() {
    override var name = "P2P"
    override var mainUrl = "https://playeriframe.sbs"
}

object BysePoW {
    private const val BE = 512
    private const val LT = BE - 1
    private const val DR = 2
    private val LR = BuildConfig.BYSE_POW_LR.toLong()
    private val HR = BuildConfig.BYSE_POW_HR.toLong()

    private fun reShift(t: Long, e: Int): Long {
        return (((t and 0xFFFFFFFFL) shl e) or ((t and 0xFFFFFFFFL) ushr (32 - e))) and 0xFFFFFFFFL
    }

    private fun htMul(t: Long, e: Long): Long {
        return (t * e) and 0xFFFFFFFFL
    }

    private fun yeMix(t: LongArray) {
        t[0] = (t[0] + t[1]) and 0xFFFFFFFFL
        t[3] = reShift(t[3] xor t[0], 16)
        t[2] = (t[2] + t[3]) and 0xFFFFFFFFL
        t[1] = reShift(t[1] xor t[2], 12)
        t[0] = (t[0] + t[1]) and 0xFFFFFFFFL
        t[3] = reShift(t[3] xor t[0], 8)
        t[2] = (t[2] + t[3]) and 0xFFFFFFFFL
        t[1] = reShift(t[1] xor t[2], 7)
    }

    private fun grHash(t: ByteArray): LongArray {
        val e = longArrayOf(1779033703L, 3144134277L, 1013904242L, 2773480762L)
        for (i in t.indices) {
            val byteVal = (t[i].toInt() and 0xFF).toLong()
            e[0] = (e[0] + byteVal) and 0xFFFFFFFFL
            e[0] = reShift(e[0], 7)
            yeMix(e)
        }
        for (i in 0 until 8) {
            yeMix(e)
        }
        val r = LongArray(BE)
        for (i in 0 until BE) {
            yeMix(e)
            r[i] = (e[0] xor e[2]) and 0xFFFFFFFFL
        }
        for (i in 0 until DR) {
            for (s in 0 until BE) {
                val a = (r[s] and LT.toLong()).toInt()
                var c = (r[s] + r[a]) and 0xFFFFFFFFL
                c = reShift(c, 13)
                val idx = (s + 1) and LT
                c = (c xor htMul(r[idx], LR)) and 0xFFFFFFFFL
                r[s] = c
                e[0] = (e[0] xor c) and 0xFFFFFFFFL
                yeMix(e)
            }
        }
        val n = LongArray(8)
        val oVal = BE / 8
        for (i in 0 until 8) {
            yeMix(e)
            var sVal = e[0]
            val a = i * oVal
            for (c in 0 until oVal) {
                val d = r[a + c]
                sVal = (sVal + d) and 0xFFFFFFFFL
                sVal = reShift(sVal, 5)
                sVal = (sVal xor htMul(d, HR)) and 0xFFFFFFFFL
            }
            n[i] = (sVal xor e[2]) and 0xFFFFFFFFL
        }
        return n
    }

    private fun wrZeros(t: LongArray): Int {
        var eVal = 0
        for (r in t.indices) {
            val n = t[r]
            if (n == 0L) {
                eVal += 32
                continue
            }
            return eVal + java.lang.Long.numberOfLeadingZeros(n) - 32
        }
        return eVal
    }

    private fun base64Decode(str: String): ByteArray {
        val cleaned = str.replace("-", "+").replace("_", "/")
        return runCatching {
            android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
        }.getOrElse {
            java.util.Base64.getDecoder().decode(cleaned)
        }
    }

    fun solve(nonce: String, difficulty: Int, timeoutMs: Long = 10000L): String? {
        if (difficulty <= 0) return "0"
        val prefix = "$nonce:"
        val startTime = System.currentTimeMillis()
        var s = 0L
        while (true) {
            val inputStr = prefix + s
            val bytes = inputStr.toByteArray(Charsets.UTF_8)
            val d = grHash(bytes)
            if (wrZeros(d) >= difficulty) {
                return s.toString()
            }
            s++
            if (s % 1024L == 0L && (System.currentTimeMillis() - startTime) > timeoutMs) {
                return null
            }
        }
    }

    fun decrypt(ivB64: String, payloadB64: String, keyParts: List<String>, version: String): String {
        var selectedParts = keyParts
        val vInt = version.trim().toIntOrNull()
        if (vInt != null) {
            val a = vInt
            val i = 31 - vInt
            if (a in 1..keyParts.size && i in 1..keyParts.size) {
                selectedParts = listOf(keyParts[a - 1], keyParts[i - 1])
            }
        }
        
        val keyBytesBuilder = java.io.ByteArrayOutputStream()
        for (part in selectedParts) {
            keyBytesBuilder.write(base64Decode(part))
        }
        val keyBytes = keyBytesBuilder.toByteArray()
        
        val ivBytes = base64Decode(ivB64)
        val payloadBytes = base64Decode(payloadB64)
        
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val decryptedBytes = cipher.doFinal(payloadBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}

open class ByseExtractor : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = ""
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalReferer = referer ?: "https://videonode.de/"
        val response = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to finalReferer,
                    "Origin" to (runCatching { java.net.URI(finalReferer).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: "https://videonode.de")
                ),
                timeout = 15L
            )
        }.getOrNull() ?: return

        val finalUrl = response.url
        val html = response.text.cleanEscaped()

        // 1. Try to extract direct m3u8 url from JS variable (like urlPlay = '...')
        val directPlayUrl = Regex("""var\s+urlPlay\s*=\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(html)
            ?.groupValues?.getOrNull(1)
            ?.cleanEscaped()

        if (!directPlayUrl.isNullOrBlank()) {
            val streamUrl = normalizeExtractorUrl(directPlayUrl, finalUrl)
            generateM3u8(
                source = name,
                streamUrl = streamUrl,
                referer = finalUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to finalUrl,
                    "Origin" to (runCatching { java.net.URI(finalUrl).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: "https://videonode.de")
                )
            ).forEach(callback)
            return
        }

        // 2. Fall back to PoW solver flow using finalUrl's domain
        val id = finalUrl.substringAfter("/e/").substringAfter("/t/").substringAfterLast("/").substringBefore("?").substringBefore("&").trim()
        if (id.isBlank()) return

        val domain = java.net.URI(finalUrl).let { "${it.scheme}://${it.host}" }
        val captchaUrl = "$domain" + BuildConfig.BYSE_API_CAPTCHA.format(id)
        val verifyUrl = "$domain" + BuildConfig.BYSE_API_VERIFY.format(id)
        val playbackUrl = "$domain" + BuildConfig.BYSE_API_SETTINGS.format(id)

        val originHost = referer?.let { java.net.URI(it).host } ?: "videonode.de"
        val originUrl = referer ?: "https://videonode.de/"

        val baseHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Embed-Origin" to originHost,
            "X-Embed-Referer" to originUrl
        )

        val captchaResText = runCatching {
            app.post(
                captchaUrl,
                json = mapOf("fingerprint" to mapOf<String, Any>()),
                headers = baseHeaders,
                referer = finalUrl,
                timeout = 15L
            ).text
        }.getOrNull() ?: return

        val captchaJson = runCatching { JSONObject(captchaResText) }.getOrNull() ?: return
        val powNonce = captchaJson.optString("pow_nonce")
        val powDifficulty = captchaJson.optInt("pow_difficulty", 0)
        val powToken = captchaJson.optString("pow_token")

        if (powNonce.isBlank() || powDifficulty <= 0 || powToken.isBlank()) return

        val solution = BysePoW.solve(powNonce, powDifficulty) ?: return

        val verifyResText = runCatching {
            app.post(
                verifyUrl,
                json = mapOf(
                    "pow_token" to powToken,
                    "solution" to solution,
                    "fingerprint" to mapOf<String, Any>()
                ),
                headers = baseHeaders,
                referer = finalUrl,
                timeout = 15L
            ).text
        }.getOrNull() ?: return

        val verifyJson = runCatching { JSONObject(verifyResText) }.getOrNull() ?: return
        if (verifyJson.optString("status") != "ok") return
        val token = verifyJson.optString("token")
        if (token.isBlank()) return

        val playbackHeaders = baseHeaders + mapOf("X-Captcha-Token" to token)
        val playbackResText = runCatching {
            app.post(
                playbackUrl,
                json = mapOf("fingerprint" to mapOf<String, Any>()),
                headers = playbackHeaders,
                referer = finalUrl,
                timeout = 15L
            ).text
        }.getOrNull() ?: return

        val playbackJson = runCatching { JSONObject(playbackResText) }.getOrNull() ?: return
        val playbackEncrypted = playbackJson.optJSONObject("playback") ?: return

        val decryptedText = runCatching {
            val keyPartsJson = playbackEncrypted.optJSONArray("key_parts")
            val keyParts = mutableListOf<String>()
            if (keyPartsJson != null) {
                for (i in 0 until keyPartsJson.length()) {
                    keyParts.add(keyPartsJson.getString(i))
                }
            }
            val iv = playbackEncrypted.optString("iv")
            val payload = playbackEncrypted.optString("payload")
            val version = playbackEncrypted.optString("version")
            
            BysePoW.decrypt(iv, payload, keyParts, version)
        }.getOrNull() ?: return

        val decryptedJson = runCatching { JSONObject(decryptedText) }.getOrNull() ?: return
        val sourcesJson = decryptedJson.optJSONArray("sources")
        if (sourcesJson != null) {
            for (i in 0 until sourcesJson.length()) {
                val src = sourcesJson.getJSONObject(i)
                val srcUrl = src.optString("url")
                val label = src.optString("label", "Source")
                if (srcUrl.isNotBlank()) {
                    emitExtractorLink(name, normalizeExtractorUrl(srcUrl, playbackUrl), finalUrl, callback)
                }
            }
        }
    }
}

class F16Extractor : ByseExtractor() {
    override var name = "CAST"
    override var mainUrl = "https://f16px.com"
}

class Gn1r5nExtractor : ByseExtractor() {
    override var name = "CAST"
    override var mainUrl = "https://gn1r5n.org"
}

class AbyssplayerExtractor : ExtractorApi() {
    override var name = "HYDRAX"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val text = runCatching {
            app.get(
                url,
                referer = referer ?: "https://videonode.de/",
                headers = defaultExtractorHeaders(referer ?: "https://videonode.de/"),
                timeout = 15L
            ).text
        }.getOrNull() ?: return

        val datasBase64 = text.substringAfter("const datas = \"").substringBefore("\"")
        if (datasBase64 == text || datasBase64.isBlank()) return

        val decodedPayloadBytes = runCatching {
            android.util.Base64.decode(datasBase64, android.util.Base64.DEFAULT)
        }.getOrElse {
            java.util.Base64.getDecoder().decode(datasBase64)
        }

        val decodedPayloadJson = String(decodedPayloadBytes, java.nio.charset.StandardCharsets.ISO_8859_1)
        val payload = runCatching { JSONObject(decodedPayloadJson) }.getOrNull() ?: return

        val userId = payload.optString("user_id")
        val slug = payload.optString("slug")
        val md5Id = payload.optString("md5_id")
        val media = payload.optString("media")

        if (userId.isBlank() || slug.isBlank() || md5Id.isBlank() || media.isBlank()) return

        val keyString = BuildConfig.ABYSS_KEY_FORMAT.format(userId, slug, md5Id)
        val md5Bytes = getMd5HexBytes(keyString)
        val key = md5Bytes
        val iv = md5Bytes.sliceArray(0 until 16)

        val ciphertext = ByteArray(media.length) { i -> media[i].code.toByte() }
        val decryptedBytes = decryptAesCtr(ciphertext, key, iv)
        val decryptedJson = String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8)

        val mediaJson = runCatching { JSONObject(decryptedJson) }.getOrNull() ?: return
        val mp4 = mediaJson.optJSONObject("mp4") ?: return
        val sources = mp4.optJSONArray("sources") ?: return
        val domains = mp4.optJSONArray("domains") ?: return

        val domainList = mutableListOf<String>()
        for (i in 0 until domains.length()) {
            domainList.add(domains.getString(i))
        }

        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            val codec = source.optString("codec")
            if (codec.equals("av1", ignoreCase = true)) continue

            val label = source.optString("label")
            val resId = source.optString("res_id")
            val size = source.optString("size")
            val sub = source.optString("sub")

            val domain = domainList.firstOrNull { it.contains(sub) } ?: continue
            val path = BuildConfig.ABYSS_PATH_FORMAT.format(md5Id, resId, size, slug)

            val sizeKeyBytes = getAbyssSizeMd5HexBytes(size)
            val sizeIvBytes = sizeKeyBytes.sliceArray(0 until 16)

            val encryptedPathBytes = encryptAesCtr(path.toByteArray(java.nio.charset.StandardCharsets.UTF_8), sizeKeyBytes, sizeIvBytes)
            val token = doubleBase64Encode(encryptedPathBytes)

            val finalUrl = BuildConfig.ABYSS_FINAL_FORMAT.format(domain, size, token)

            val qualityVal = getQualityFromName(label)
            val linkLabel = when (qualityVal) {
                Qualities.P2160.value, Qualities.P1080.value -> "FHD"
                Qualities.P720.value -> "HD"
                else -> "SD"
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name $linkLabel",
                    url = finalUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = url
                    this.quality = qualityVal
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url)
                }
            )
        }
    }

    private fun getMd5HexBytes(input: String): ByteArray {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val hexString = digest.joinToString("") { "%02x".format(it) }
        return hexString.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
    }

    private fun getAbyssSizeMd5HexBytes(sizeStr: String): ByteArray {
        val bytes = ByteArray(sizeStr.length) { i ->
            (sizeStr[i] - '0').toByte()
        }
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        val hexString = digest.joinToString("") { "%02x".format(it) }
        return hexString.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
    }

    private fun decryptAesCtr(ciphertext: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
        val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        val ivSpec = javax.crypto.spec.IvParameterSpec(ivBytes)
        val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(ciphertext)
    }

    private fun encryptAesCtr(plaintext: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
        val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        val ivSpec = javax.crypto.spec.IvParameterSpec(ivBytes)
        val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(plaintext)
    }

    private fun doubleBase64Encode(data: ByteArray): String {
        val b1 = runCatching {
            android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP).replace("=", "")
        }.getOrElse {
            java.util.Base64.getEncoder().encodeToString(data).replace("=", "")
        }
        val b2 = runCatching {
            android.util.Base64.encodeToString(b1.toByteArray(java.nio.charset.StandardCharsets.US_ASCII), android.util.Base64.NO_WRAP).replace("=", "")
        }.getOrElse {
            java.util.Base64.getEncoder().encodeToString(b1.toByteArray(java.nio.charset.StandardCharsets.US_ASCII)).replace("=", "")
        }
        return b2
    }
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

    val cleanSource = when {
        source.contains("Videonode", true) || source.contains("P2P", true) || source.contains("PlayerIframe", true) -> "P2P"
        source.contains("Emturbovid", true) || source.contains("TURBOVIP", true) -> "TURBOVIP"
        source.contains("Gn1r5n", true) || source.contains("F16", true) || source.contains("CAST", true) -> "CAST"
        source.contains("Abyssplayer", true) || source.contains("HYDRAX", true) -> "HYDRAX"
        else -> source
    }

    if (cleanSource == "P2P") {
        callback(
            newExtractorLink(
                source = "P2P",
                name = "P2P",
                url = fixed,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.P480.value
                this.headers = defaultExtractorHeaders(referer)
            }
        )
        return
    }

    if (fixed.contains(".m3u8", true)) {
        generateM3u8(
            source = cleanSource,
            streamUrl = fixed,
            referer = referer,
            headers = defaultExtractorHeaders(referer)
        ).forEach { link ->
            val linkLabel = when (link.quality) {
                Qualities.P2160.value, Qualities.P1080.value -> "FHD"
                Qualities.P720.value -> "HD"
                else -> "SD"
            }
            callback(
                newExtractorLink(
                    source = cleanSource,
                    name = "$cleanSource $linkLabel",
                    url = link.url,
                    type = link.type
                ) {
                    this.referer = link.referer
                    this.quality = link.quality
                    this.headers = link.headers
                }
            )
        }
    } else {
        val qualityVal = getQualityFromName(fixed).takeIf {
            it != Qualities.Unknown.value
        } ?: qualityFromUrl(fixed)
        val label = when (qualityVal) {
            Qualities.P2160.value, Qualities.P1080.value -> "FHD"
            Qualities.P720.value -> "HD"
            else -> "SD"
        }
        callback(
            newExtractorLink(
                source = cleanSource,
                name = "$cleanSource $label",
                url = fixed,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = qualityVal
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
        """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt|emturbovid|hownetwork|f16|jeniusplay|majorplay|streamwish|filemoon|dood|streamtape|vidhide|voe|mixdrop|videonode|playcdn)[^"'\\\s<>]*""",
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
        "hglink",
        "videonode",
        "playcdn",
        "gn1r5n",
        "abyssplayer"
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

fun getCustomExtractor(url: String): ExtractorApi? {
    return when {
        url.contains("abyssplayer.com", true) || url.contains("abyss.to", true) -> AbyssplayerExtractor()
        url.contains("emturbovid.com", true) -> EmturbovidExtractor()
        url.contains("turbovidhls.com", true) -> TurbovidhlsExtractor()
        url.contains("f16px.com", true) -> F16Extractor()
        url.contains("gn1r5n.org", true) -> Gn1r5nExtractor()
        url.contains("videonode.de", true) -> VideonodeExtractor()
        url.contains("playeriframe.sbs", true) -> PlayerIframeExtractor()
        else -> null
    }
}
