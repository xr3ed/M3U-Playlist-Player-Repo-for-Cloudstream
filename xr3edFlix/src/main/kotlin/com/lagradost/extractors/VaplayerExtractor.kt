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
import java.net.URI

object VaplayerExtractor {
    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "https://nextgencloudfabric.com/",
        "Origin" to "https://nextgencloudfabric.com"
    )

    suspend fun invoke(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (tmdbId == null) return false
        return try {
            val vaplayerUrl = if (season == null || episode == null) {
                "https://streamdata.vaplayer.ru/api.php?tmdb=$tmdbId&type=movie"
            } else {
                "https://streamdata.vaplayer.ru/api.php?tmdb=$tmdbId&type=tv&season=$season&episode=$episode"
            }

            val apiRes = app.get(vaplayerUrl, headers = apiHeaders, timeout = 10)
            if (apiRes.code != 200) return false

            val root = mapper.readTree(apiRes.text)

            // Subtitles
            val defaultSubs = root.get("default_subs")
            if (defaultSubs != null && defaultSubs.isArray) {
                defaultSubs.forEach { subNode ->
                    val lang = subNode.get("lang")?.asText() ?: subNode.get("code")?.asText() ?: "Unknown"
                    val subUrl = subNode.get("url")?.asText()
                    if (!subUrl.isNullOrEmpty()) {
                        subCallback.invoke(newSubtitleFile(lang, subUrl))
                    }
                }
            }

            val dataNode = root.get("data") ?: return false
            val streamUrls = dataNode.get("stream_urls") ?: return false
            if (!streamUrls.isArray) return false

            var foundAny = false
            val addedUrls = mutableSetOf<String>()

            for (uNode in streamUrls) {
                val streamUrl = uNode.asText() ?: continue
                if (streamUrl.isBlank()) continue

                try {
                    val manifestRes = app.get(streamUrl, headers = apiHeaders, timeout = 6)
                    if (manifestRes.code == 200 && manifestRes.text.contains("#EXT-X-STREAM-INF")) {
                        val uri = URI(streamUrl)
                        val hostUrl = "${uri.scheme}://${uri.host}"
                        val lines = manifestRes.text.split("\n")
                        var currentRes = ""
                        var parsedAny = false

                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                                val resRegex = """RESOLUTION=(\d+)x(\d+)""".toRegex()
                                val match = resRegex.find(trimmed)
                                if (match != null) {
                                    val height = match.groupValues[2].toInt()
                                    val standardHeight = when {
                                        height >= 800 -> 1080
                                        height >= 500 -> 720
                                        height >= 350 -> 480
                                        else -> 360
                                    }
                                    currentRes = "${standardHeight}p"
                                }
                            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                if (currentRes.isNotEmpty()) {
                                    val absoluteUrl = if (trimmed.startsWith("/")) {
                                        hostUrl + trimmed
                                    } else if (trimmed.startsWith("http")) {
                                        trimmed
                                    } else {
                                        val parentPath = streamUrl.substring(0, streamUrl.lastIndexOf('/') + 1)
                                        parentPath + trimmed
                                    }

                                    val dedupKey = if (absoluteUrl.contains("/index.m3u8")) {
                                        absoluteUrl.substringBefore("/index.m3u8").substringAfterLast("/")
                                    } else {
                                        absoluteUrl
                                    }

                                    if (addedUrls.add(dedupKey)) {
                                        val q = currentRes.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value
                                        val link = newExtractorLink(
                                            name = "Vaplayer - $currentRes",
                                            source = "Vaplayer",
                                            url = absoluteUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.quality = q
                                            this.referer = "https://nextgencloudfabric.com/"
                                            this.headers = apiHeaders
                                        }
                                        callback.invoke(link)
                                        parsedAny = true
                                    }
                                    currentRes = ""
                                }
                            }
                        }

                        if (parsedAny) {
                            foundAny = true
                            break
                        }
                    }
                } catch (ex: Exception) {
                    Log.w("VaplayerExtractor", "Manifest parse error: ${ex.message}")
                }
            }

            return foundAny
        } catch (e: Exception) {
            Log.e("VaplayerExtractor", "Vaplayer invoke error", e)
            false
        }
    }
}
