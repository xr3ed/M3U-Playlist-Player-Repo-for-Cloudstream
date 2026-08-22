package com.lagradost.extractors

import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream

object VidnestExtractor {
    private const val API_BASE = "https://new.vidnest.fun"
    private const val ALPHABET = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="

    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val reqHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "https://vidnest.fun/",
        "Origin" to "https://vidnest.fun"
    )

    fun decrypt(cipherText: String): String {
        val charMap = IntArray(256) { 64 }
        for (i in ALPHABET.indices) {
            charMap[ALPHABET[i].code] = i
        }

        val decodedBytes = ByteArrayOutputStream()
        var i = 0
        val len = cipherText.length

        while (i < len) {
            val c1 = if (i < len) cipherText[i++].code else '='.code
            val c2 = if (i < len) cipherText[i++].code else '='.code
            val c3 = if (i < len) cipherText[i++].code else '='.code
            val c4 = if (i < len) cipherText[i++].code else '='.code

            val val0 = charMap[c1]
            val val1 = charMap[c2]
            val val2 = charMap[c3]
            val val3 = charMap[c4]

            val b1 = (val0 shl 2) or (val1 shr 4)
            decodedBytes.write(b1 and 0xFF)

            if (val2 != 64) {
                val b2 = ((val1 and 15) shl 4) or (val2 shr 2)
                decodedBytes.write(b2 and 0xFF)
            }

            if (val3 != 64) {
                val b3 = ((val2 and 3) shl 6) or val3
                decodedBytes.write(b3 and 0xFF)
            }
        }

        return decodedBytes.toString("UTF-8")
    }

    suspend fun invoke(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val subpath = if (season == null || episode == null) {
            "movie/$tmdbId"
        } else {
            "tv/$tmdbId/$season/$episode"
        }

        val endpoints = listOf(
            "Prime" to "$API_BASE/hollymoviehd/$subpath",
            "Gama" to "$API_BASE/moviebox/$subpath"
        )

        coroutineScope {
            endpoints.map { (serverName, url) ->
                async {
                    try {
                        val response = app.get(url, headers = reqHeaders, timeout = 8)
                        if (response.code == 200) {
                            val text = response.text
                            if (text.contains("data")) {
                                val rootEnc = mapper.readTree(text)
                                val cipher = rootEnc["data"]?.asText()
                                if (!cipher.isNullOrEmpty()) {
                                    val decrypted = decrypt(cipher)
                                    val root = mapper.readTree(decrypted)
                                    val dataNode = if (root.has("data") && root.get("data").isObject) root.get("data") else root

                                    // 1. Format URL array
                                    val urlNode = dataNode.get("url")
                                    if (urlNode != null && urlNode.isArray) {
                                        urlNode.forEach { item ->
                                            val link = item.get("link")?.asText() ?: return@forEach
                                            if (link.isBlank()) return@forEach
                                            val res = item.get("resolution")?.asText() ?: ""
                                            val typ = item.get("type")?.asText() ?: ""
                                            val lang = item.get("lang")?.asText() ?: ""
                                            val q = when {
                                                res.contains("1080") -> Qualities.P1080.value
                                                res.contains("720") -> Qualities.P720.value
                                                res.contains("480") -> Qualities.P480.value
                                                res.contains("360") -> Qualities.P360.value
                                                else -> Qualities.Unknown.value
                                            }
                                            val label = "$serverName${if (lang.isNotBlank()) " [$lang]" else ""}".trim()
                                            callback.invoke(
                                                newExtractorLink(
                                                    name = label,
                                                    source = serverName,
                                                    url = link,
                                                    type = if (typ == "hls" || link.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                                ) {
                                                    this.quality = q
                                                    if (serverName == "Gama") {
                                                        this.headers = mapOf("User-Agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Subsystem for Android(TM); Build/TQ3A.230901.001; Cronet/145.0.7582.0)")
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    // 2. Format Streams array
                                    val streamsNode = dataNode.get("streams")
                                    if (streamsNode != null && streamsNode.isArray) {
                                        streamsNode.forEach { str ->
                                            val streamUrl = str.get("url")?.asText() ?: return@forEach
                                            if (streamUrl.isBlank()) return@forEach
                                            val lang = str.get("language")?.asText()
                                            val typ = str.get("type")?.asText() ?: ""
                                            val isPrimeMain = serverName == "Prime" && (lang == "MAIN" || lang.isNullOrEmpty())
                                            val nameLabel = if (!lang.isNullOrEmpty() && lang != "MAIN") "$serverName [$lang]" else serverName
                                            val q = if (isPrimeMain) Qualities.P1080.value else Qualities.Unknown.value

                                            val headersObj = str.get("headers")
                                            val streamHeaders = mutableMapOf<String, String>()
                                            if (headersObj != null && headersObj.isObject) {
                                                headersObj.fieldNames().forEach { fn ->
                                                    streamHeaders[fn] = headersObj.get(fn).asText()
                                                }
                                            }

                                            callback.invoke(
                                                newExtractorLink(
                                                    name = nameLabel,
                                                    source = serverName,
                                                    url = streamUrl,
                                                    type = if (typ == "hls" || streamUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                                ) {
                                                    this.quality = q
                                                    if (streamHeaders.isNotEmpty()) {
                                                        this.headers = streamHeaders
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    // 3. Captions
                                    val captionsNode = dataNode.get("captions")
                                    if (captionsNode != null && captionsNode.isArray) {
                                        captionsNode.forEach { sub ->
                                            val subUrl = sub.get("url")?.asText() ?: return@forEach
                                            val lang = sub.get("lanName")?.asText() ?: sub.get("lan")?.asText() ?: "Unknown"
                                            subCallback.invoke(newSubtitleFile(lang, subUrl))
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("VidnestExtractor", "Vidnest $serverName error: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }
}
