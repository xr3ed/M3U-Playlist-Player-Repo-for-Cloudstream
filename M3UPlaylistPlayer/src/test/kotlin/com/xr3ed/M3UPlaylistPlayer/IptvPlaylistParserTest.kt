package com.xr3ed.M3UPlaylistPlayer

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class IptvPlaylistParserTest {

    @Test
    fun testParseM3U_valid() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1 tvg-id="TestChannel" tvg-logo="http://logo.png" group-title="Sports",Test Channel
            #EXTVLCOPT:http-user-agent=CustomUA
            #EXTVLCOPT:http-referer=https://referer.com
            #KODIPROP:inputstream=inputstream.adaptive
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            http://stream.url/live.mpd
        """.trimIndent()

        val parser = IptvPlaylistParser()
        val playlist = parser.parseM3U(m3uContent)

        assertEquals(1, playlist.items.size)
        val item = playlist.items[0]

        assertEquals("Test Channel", item.title)
        assertEquals("http://stream.url/live.mpd", item.url)

        // Attributes
        assertEquals("TestChannel", item.attributes["tvg-id"])
        assertEquals("http://logo.png", item.attributes["tvg-logo"])
        assertEquals("Sports", item.attributes["group-title"])

        // Headers
        assertEquals("CustomUA", item.headers["User-Agent"])
        assertEquals("https://referer.com", item.headers["Referer"])

        // Kodi Props
        assertEquals("inputstream.adaptive", item.kodiProps["inputstream"])
        assertEquals("mpd", item.kodiProps["inputstream.adaptive.manifest_type"])
    }

    @Test
    fun testParseM3U_emptyOrInvalid() {
        val parser = IptvPlaylistParser()
        val playlist = parser.parseM3U("")
        assertTrue(playlist.items.isEmpty())

        val invalidContent = """
            #SOMETHING_ELSE
            http://some.url
        """.trimIndent()
        val playlist2 = parser.parseM3U(invalidContent)
        assertTrue(playlist2.items.isEmpty())
    }
}
