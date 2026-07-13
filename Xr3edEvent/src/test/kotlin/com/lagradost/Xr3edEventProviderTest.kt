package com.lagradost

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.lagradost.cloudstream3.utils.ExtractorLink

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class Xr3edEventProviderTest {

    @Test
    fun testLoadLinksTVRI() = runBlocking {
        val provider = Xr3edEventProvider(org.robolectric.RuntimeEnvironment.getApplication())
        val links = mutableListOf<ExtractorLink>()
        
        // Simulasikan data play untuk TVRI biasa (tvrivp)
        val testData = "https://xys1-2-player.pages.dev/bitmovin/?id=tvrivp"
        
        println("=== MEMULAI UNIT TEST TVRI ===")
        val success = provider.loadLinks(testData, false, {}, { link ->
            println("Ditemukan link: ${link.url}")
            links.add(link)
        })
        
        println("Status loadLinks: $success")
        println("Jumlah link yang didapat: ${links.size}")
        
        assertTrue("Harus sukses mengekstrak link", success)
        assertTrue("Harus menghasilkan setidaknya satu link", links.isNotEmpty())
        
        val firstLink = links.first()
        assertTrue("Link harus mengarah ke localhost proxy", firstLink.url.contains("http://127.0.0.1:"))
        assertTrue("Link harus berformat .mpd", firstLink.url.contains(".mpd"))
        
        // Menguji akses ke localhost manifest server
        println("Menguji unduhan manifest lokal: ${firstLink.url}")
        val client = com.lagradost.cloudstream3.app
        val response = client.get(firstLink.url, timeout = 15)
        println("Local Manifest HTTP Code: ${response.code}")
        println("Local Manifest Length: ${response.text.length}")
        
        assertTrue("Local server manifest harus mengembalikan HTTP 200", response.code == 200)
        assertTrue("Manifest XML harus valid", response.text.contains("<MPD") && response.text.contains("</MPD>"))
        println("=== UNIT TEST TVRI SUKSES 100% ===")
    }
}

