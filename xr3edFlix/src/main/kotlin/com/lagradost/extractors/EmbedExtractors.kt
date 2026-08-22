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
                "https://vidlink.pro/movie/$tmdbId",
                "https://player.autoembed.cc/embed/movie/$tmdbId",
                "https://vidsrc.xyz/embed/movie/$tmdbId",
                "https://vidsrc.in/embed/movie/$tmdbId",
                "https://vidsrc.pm/embed/movie/$tmdbId",
                "https://vidsrc.net/embed/movie/$tmdbId",
                "https://www.2embed.cc/embed/$tmdbId",
                "https://player.smashy.stream/movie/$tmdbId"
            )
        } else {
            listOf(
                "https://vidlink.pro/tv/$tmdbId/$season/$episode",
                "https://player.autoembed.cc/embed/tv/$tmdbId/$season/$episode",
                "https://vidsrc.xyz/embed/tv/$tmdbId/$season-$episode",
                "https://vidsrc.in/embed/tv/$tmdbId/$season/$episode",
                "https://vidsrc.pm/embed/tv/$tmdbId/$season/$episode",
                "https://vidsrc.net/embed/tv/$tmdbId/$season/$episode",
                "https://www.2embed.cc/embed/$tmdbId?s=$season&e=$episode",
                "https://player.smashy.stream/tv/$tmdbId?s=$season&e=$episode"
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
                            resolved = loadExtractor(currentUrl, subCallback, callback)
                            if (resolved) break

                            val response = app.get(currentUrl, timeout = 6)
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
