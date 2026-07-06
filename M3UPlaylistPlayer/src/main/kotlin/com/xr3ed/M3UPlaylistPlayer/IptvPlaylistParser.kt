package com.xr3ed.M3UPlaylistPlayer

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
        val playlistItems = mutableListOf<PlaylistItem>()

        var bufferedTitle: String? = null
        var bufferedAttributes = emptyMap<String, String>()
        var bufferedHeaders: MutableMap<String, String>? = null
        var bufferedKodiProps: MutableMap<String, String>? = null

        input.bufferedReader().forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isNotEmpty()) {
                when {
                    line.startsWith("#EXTINF:", ignoreCase = true) || line.startsWith("#EXTINF ", ignoreCase = true) -> {
                        bufferedTitle = getTitle(line)
                        bufferedAttributes = getAttributes(line)
                    }
                    line.startsWith("#EXTVLCOPT:") -> {
                        val userAgent = getTagValue(line, "http-user-agent")
                        val referrer = getTagValue(line, "http-referrer") ?: getTagValue(line, "http-referer")
                        
                        if (userAgent != null) {
                            if (bufferedHeaders == null) bufferedHeaders = mutableMapOf()
                            bufferedHeaders!!["User-Agent"] = userAgent
                        }
                        if (referrer != null) {
                            if (bufferedHeaders == null) bufferedHeaders = mutableMapOf()
                            bufferedHeaders!!["Referer"] = referrer
                        }
                    }
                    line.startsWith("#KODIPROP:") -> {
                        val propValue = line.substringAfter("#KODIPROP:").trim()
                        val parts = propValue.split('=', limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim()
                            val value = parts[1].trim()
                            if (bufferedKodiProps == null) bufferedKodiProps = mutableMapOf()
                            bufferedKodiProps!![key] = value
                        }
                    }
                    !line.startsWith("#") -> {
                        val currentTitle = bufferedTitle
                        if (currentTitle != null) {
                            playlistItems.add(
                                PlaylistItem(
                                    title = currentTitle,
                                    url = line,
                                    attributes = bufferedAttributes,
                                    headers = bufferedHeaders ?: emptyMap(),
                                    kodiProps = bufferedKodiProps ?: emptyMap()
                                )
                            )
                        }
                        bufferedTitle = null
                        bufferedAttributes = emptyMap()
                        bufferedHeaders = null
                        bufferedKodiProps = null
                    }
                }
            }
        }
        return Playlist(playlistItems)
    }

    private fun getTitle(line: String): String {
        val lastQuoteIndex = line.lastIndexOf('"')
        return if (lastQuoteIndex != -1 && lastQuoteIndex < line.length - 1) {
            val afterQuote = line.substring(lastQuoteIndex + 1)
            afterQuote.substringAfter(",").trim()
        } else {
            line.substringAfterLast(",").trim()
        }
    }

    private fun getAttributes(line: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        val limitIndex = line.lastIndexOf(',')
        val attrPart = if (limitIndex != -1) line.substring(0, limitIndex) else line
        
        var i = 0
        val len = attrPart.length
        while (i < len) {
            // Lewati spasi
            while (i < len && attrPart[i].isWhitespace()) {
                i++
            }
            if (i >= len) break
            
            // Cari kunci (key)
            val keyStart = i
            while (i < len && attrPart[i] != '=' && !attrPart[i].isWhitespace()) {
                i++
            }
            val keyEnd = i
            if (keyStart == keyEnd) {
                i++
                continue
            }
            val key = attrPart.substring(keyStart, keyEnd).trim()
            
            // Lewati spasi atau '='
            while (i < len && attrPart[i].isWhitespace()) {
                i++
            }
            if (i < len && attrPart[i] == '=') {
                i++
            } else {
                continue
            }
            while (i < len && attrPart[i].isWhitespace()) {
                i++
            }
            
            // Cari nilai (value) di dalam tanda kutip "value"
            if (i < len && attrPart[i] == '"') {
                i++ // lewati kutip buka
                val valStart = i
                while (i < len && attrPart[i] != '"') {
                    i++
                }
                val valEnd = i
                val value = attrPart.substring(valStart, valEnd)
                if (i < len && attrPart[i] == '"') {
                    i++ // lewati kutip tutup
                }
                attributes[key] = value
            } else {
                // Nilai tanpa tanda kutip (misal key=val)
                val valStart = i
                while (i < len && !attrPart[i].isWhitespace()) {
                    i++
                }
                val valEnd = i
                val value = attrPart.substring(valStart, valEnd)
                attributes[key] = value
            }
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
