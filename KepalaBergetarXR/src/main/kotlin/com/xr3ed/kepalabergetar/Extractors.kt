package com.xr3ed.kepalabergetar

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

open class VkSpeed : ExtractorApi() {
    override var name = "VkSpeed"
    override var mainUrl = "https://vkspeed.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val targetUrl = if (url.startsWith("//")) "https:$url" else url
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://kepalabergetar.cfd/")
        )
        val responseText = try {
            app.get(targetUrl, headers = headers).text
        } catch (e: Exception) {
            return
        }

        val unpacked = try {
            getAndUnpack(responseText)
        } catch (e: Exception) {
            ""
        }

        val combined = "$responseText\n$unpacked"

        // Extract sources from jwplayer config: sources: [{file:"...", label:"720p"}]
        val sourceRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["'](?:\s*,\s*["']?label["']?\s*:\s*["']([^"']+)["'])?""")
        sourceRegex.findAll(combined).forEach { match ->
            val videoUrl = match.groupValues[1]
            val label = match.groupValues.getOrNull(2) ?: ""
            if (videoUrl.startsWith("http") && !videoUrl.endsWith(".jpg") && !videoUrl.endsWith(".png") && !videoUrl.endsWith(".svg")) {
                val quality = when {
                    label.contains("1080") -> Qualities.P1080.value
                    label.contains("720") -> Qualities.P720.value
                    label.contains("480") -> Qualities.P480.value
                    label.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = if (label.isNotEmpty()) "$name $label" else name,
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = targetUrl
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to targetUrl,
                            "User-Agent" to (headers["User-Agent"] ?: "")
                        )
                    }
                )
            }
        }

        // Direct stream url fallback (.m3u8 / .mp4)
        val directStreamRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
        directStreamRegex.findAll(combined).forEach { match ->
            val streamUrl = match.groupValues[1]
            val isM3u8 = streamUrl.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = if (isM3u8) "$name HLS" else "$name Direct",
                    url = streamUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = targetUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to targetUrl,
                        "User-Agent" to (headers["User-Agent"] ?: "")
                    )
                }
            )
        }
    }
}
