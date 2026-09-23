package com.lagradost.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI

object EmbedExtractors {
    suspend fun invoke(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return

        val isMovie = season == null || episode == null
        val embedUrls = if (isMovie) {
            listOf(
                "https://vidsrc.me/embed/movie?tmdb=$tmdbId",
                "https://vidsrc.pm/embed/movie/$tmdbId",
                "https://vidsrc.in/embed/movie/$tmdbId",
                "https://vidsrc.rip/embed/movie/$tmdbId"
            )
        } else {
            listOf(
                "https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode",
                "https://vidsrc.pm/embed/tv/$tmdbId/$season/$episode",
                "https://vidsrc.in/embed/tv/$tmdbId/$season/$episode",
                "https://vidsrc.rip/embed/tv/$tmdbId/$season/$episode"
            )
        }

        coroutineScope {
            embedUrls.map { embedUrl ->
                async {
                    try {
                        var currentUrl = embedUrl
                        var resolved = false
                        var hops = 0
                        while (hops < 3 && !resolved) {
                            if (currentUrl.contains("2embed.cc") || currentUrl.contains("streamcash")) break

                            resolved = runCatching {
                                loadExtractor(currentUrl, subCallback) { link ->
                                    val isDeadStreamcash = link.name.contains("streamcash", ignoreCase = true) ||
                                            link.url.contains("streamcash", ignoreCase = true) ||
                                            link.url.contains("cdn.streamcash.to", ignoreCase = true)
                                    if (!isDeadStreamcash) {
                                        callback(link)
                                    }
                                }
                            }.getOrDefault(false)

                            if (resolved) break

                            val response = runCatching {
                                app.get(currentUrl, timeout = 5)
                            }.getOrNull() ?: break

                            if (response.code != 200) break

                            val html = response.text
                            val iframeRegex = """iframe[^>]+src=["']([^"']+)["']""".toRegex()
                            val iframeSrc = iframeRegex.find(html)?.groups?.get(1)?.value
                            if (iframeSrc.isNullOrEmpty()) break

                            currentUrl = when {
                                iframeSrc.startsWith("//") -> "https:$iframeSrc"
                                iframeSrc.startsWith("/") -> {
                                    val uri = URI(currentUrl)
                                    "${uri.scheme}://${uri.host}$iframeSrc"
                                }
                                else -> iframeSrc
                            }
                            if (currentUrl.contains("2embed.cc") || currentUrl.contains("streamcash")) break
                            hops++
                        }
                    } catch (e: Exception) {
                        Log.d("EmbedExtractors", "Embed $embedUrl resolve failed: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }
}
