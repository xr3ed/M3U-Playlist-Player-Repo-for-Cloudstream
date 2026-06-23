package com.cncverse.M3UPlaylistPlayer

import java.io.InputStream

data class Playlist(
    val items: List<PlaylistItem>
)

data class PlaylistItem(
    val title: String,
    val url: String,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val kodiProps: Map<String, String> = emptyMap()
)

class IptvPlaylistParser {
    fun parseM3U(content: String): Playlist {
        return parseM3U(content.byteInputStream())
    }

    fun parseM3U(input: InputStream): Playlist {
        val allLines = input.bufferedReader().readLines()
        val playlistItems = mutableListOf<PlaylistItem>()
        var i = 0

        var bufferedTitle: String? = null
        var bufferedAttributes = emptyMap<String, String>()
        var bufferedHeaders = emptyMap<String, String>()
        var bufferedKodiProps = emptyMap<String, String>()

        while (i < allLines.size) {
            val line = allLines[i].trim()

            if (line.isNotEmpty()) {
                when {
                    line.startsWith("#EXTINF:") -> {
                        bufferedTitle = getTitle(line)
                        bufferedAttributes = getAttributes(line)
                    }
                    line.startsWith("#EXTVLCOPT:") -> {
                        val userAgent = getTagValue(line, "http-user-agent")
                        val referrer = getTagValue(line, "http-referrer") ?: getTagValue(line, "http-referer")
                        
                        if (userAgent != null) {
                            bufferedHeaders = bufferedHeaders + mapOf("User-Agent" to userAgent)
                        }
                        if (referrer != null) {
                            bufferedHeaders = bufferedHeaders + mapOf("Referer" to referrer)
                        }
                    }
                    line.startsWith("#KODIPROP:") -> {
                        val propValue = line.substringAfter("#KODIPROP:").trim()
                        val parts = propValue.split('=', limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim()
                            val value = parts[1].trim()
                            bufferedKodiProps = bufferedKodiProps + mapOf(key to value)
                        }
                    }
                    !line.startsWith("#") -> {
                        if (bufferedTitle != null) {
                            playlistItems.add(
                                PlaylistItem(
                                    title = bufferedTitle,
                                    url = line,
                                    attributes = bufferedAttributes,
                                    headers = bufferedHeaders,
                                    kodiProps = bufferedKodiProps
                                )
                            )
                        }
                        bufferedTitle = null
                        bufferedAttributes = emptyMap()
                        bufferedHeaders = emptyMap()
                        bufferedKodiProps = emptyMap()
                    }
                }
            }
            i++
        }
        return Playlist(playlistItems)
    }

    private fun getTitle(line: String): String {
        return line.substringAfterLast(",").trim()
    }

    private fun getAttributes(line: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        val regex = Regex("(\\S+?)=\"(.+?)\"")
        regex.findAll(line).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            attributes[key] = value
        }
        return attributes
    }

    private fun getTagValue(line: String, tag: String): String? {
        val prefix = "#EXTVLCOPT:$tag="
        return if (line.startsWith(prefix)) {
            line.removePrefix(prefix).trim()
        } else null
    }
}
