package com.lagradost.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

object XpassExtractor {
    private const val BASE_URL = "https://play.xpass.top"
    private val headers = mapOf(
        "Referer" to "$BASE_URL/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )

    suspend fun invoke(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        try {
            val embedUrl = if (season == null || episode == null) {
                "$BASE_URL/e/movie/$tmdbId"
            } else {
                "$BASE_URL/e/tv/$tmdbId/$season/$episode"
            }

            val htmlResponse = app.get(embedUrl, headers = headers, timeout = 8)
            if (htmlResponse.code != 200) return
            val html = htmlResponse.text

            // Subtitles dari script suburl
            val subUrlMatch = Regex("""var\s+suburl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (!subUrlMatch.isNullOrEmpty()) {
                try {
                    val subRes = app.get(subUrlMatch, headers = headers, timeout = 6)
                    if (subRes.code == 200) {
                        val subArr = JSONArray(subRes.text)
                        for (i in 0 until subArr.length()) {
                            val item = subArr.getJSONObject(i)
                            val lang = item.optString("label", item.optString("lang", "Unknown"))
                            val file = item.optString("file")
                            if (file.isNotEmpty()) {
                                subtitleCallback.invoke(newSubtitleFile(lang, file))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("XpassExtractor", "Subtitles fetch failed: ${e.message}")
                }
            }

            // Ambil semua backup server tanpa filter VIP saja
            if (!html.contains("var backups=")) return
            val rawBackups = try {
                html.substringAfter("var backups=").substringBefore("</script>").substringBefore(";\n").trim().removeSuffix(";")
            } catch (e: Exception) { "" }

            if (rawBackups.isBlank()) return
            val array = JSONArray(rawBackups)
            val backups = (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Pair(name, url)
            }

            coroutineScope {
                backups.map { (name, url) ->
                    async {
                        try {
                            val fullUrl = if (url.startsWith("http")) url else "$BASE_URL$url"
                            val response = app.get(fullUrl, headers = headers, timeout = 8)
                            if (response.code == 200) {
                                val root = JSONObject(response.text)
                                val playlist = root.optJSONArray("playlist")
                                val firstPlay = playlist?.optJSONObject(0)
                                val sources = firstPlay?.optJSONArray("sources") ?: return@async

                                for (j in 0 until sources.length()) {
                                    val src = sources.optJSONObject(j) ?: continue
                                    val fileUrl = src.optString("file")
                                    if (fileUrl.isNotBlank() && fileUrl.startsWith("http") && !fileUrl.contains("/video/error")) {
                                        val type = src.optString("type")
                                        val isHls = type.contains("hls", true) || fileUrl.contains(".m3u8")
                                        val serverLabel = "Xpass - $name"

                                        if (isHls) {
                                            val m3u8Links = M3u8Helper.generateM3u8(
                                                source = serverLabel,
                                                streamUrl = fileUrl,
                                                referer = "$BASE_URL/",
                                                headers = headers
                                            )
                                            m3u8Links.forEach { link ->
                                                callback.invoke(link)
                                            }
                                        } else {
                                            callback.invoke(
                                                newExtractorLink(
                                                    name = serverLabel,
                                                    source = serverLabel,
                                                    url = fileUrl,
                                                    type = ExtractorLinkType.VIDEO
                                                ) {
                                                    this.referer = "$BASE_URL/"
                                                    this.headers = headers
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        } catch (ex: Exception) {
                            Log.e("XpassExtractor", "Backup $name failed: ${ex.message}")
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.e("XpassExtractor", "Xpass invoke failed", e)
        }
    }
}
