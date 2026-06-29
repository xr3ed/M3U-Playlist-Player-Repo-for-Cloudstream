package com.cncverse.xr3edtv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.lagradost.cloudstream3.MainPageRequest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class Xr3edtvProviderTest {

    @Test
    fun testGetMainPageAndLoadCategory() = runBlocking {
        val provider = Xr3edtvProvider()
        val result = provider.getMainPage(1, MainPageRequest("https://xys1-depan.pages.dev", 1))
        assertNotNull(result)
        println("Main page categories loaded. Total: ${result?.data?.size}")
        
        // Coba load salah satu kategori, misalnya TV Indonesia (group:tvnasional)
        val categoryLoadResult = provider.load("group:tvnasional")
        assertNotNull(categoryLoadResult)
        
        // Verifikasi daftar channel
        if (categoryLoadResult is com.lagradost.cloudstream3.TvSeriesLoadResponse) {
            println("Successfully loaded TvSeriesLoadResponse. Total channels in tvnasional: ${categoryLoadResult.episodes.size}")
            assertTrue("Should contain channels", categoryLoadResult.episodes.isNotEmpty())
            categoryLoadResult.episodes.take(5).forEach {
                println("Channel: ${it.name} -> ${it.data}")
            }
        }
    }

    @Test
    fun testLoadAndLoadLinks() = runBlocking {
        val provider = Xr3edtvProvider()
        
        // Test memuat link dari rctivp (RCTI)
        val loadResult = provider.load("go:rctivp")
        assertNotNull(loadResult)
        
        val links = mutableListOf<String>()
        val success = provider.loadLinks("go:rctivp", false, {}, { link ->
            println("Extracted Link URL: ${link.url}")
            links.add(link.url)
        })
        
        println("Load links status: $success")
        assertTrue("Should succeed loading links", success)
        assertTrue("Should have loaded at least one link", links.isNotEmpty())
    }
}
