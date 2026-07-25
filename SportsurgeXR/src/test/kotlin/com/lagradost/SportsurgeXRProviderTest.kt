package com.lagradost

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.lagradost.cloudstream3.MainPageRequest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SportsurgeXRProviderTest {

    @Test
    fun testGetMainPage() = runBlocking {
        val provider = SportsurgeXRProvider()
        val result = provider.getMainPage(1, MainPageRequest("Live Event", "Live Event", true))
        assertNotNull(result)
        println("SportsurgeXR main page results count: ${result?.items?.size}")
    }

    @Test
    fun testLoadAndLoadLinks() = runBlocking {
        val provider = SportsurgeXRProvider()
        val mainPage = provider.getMainPage(1, MainPageRequest("Live Event", "Live Event", true))
        assertNotNull(mainPage)
        var detailUrl: String? = null
        for (category in mainPage!!.items) {
            for (match in category.list) {
                if (match.url.contains("detail.html")) {
                    detailUrl = match.url
                    break
                }
            }
            if (detailUrl != null) break
        }

        if (detailUrl != null) {
            println("Found match URL: $detailUrl")
            val loadResult = provider.load(detailUrl)
            assertNotNull(loadResult)
            
            val links = mutableListOf<String>()
            val success = provider.loadLinks((loadResult as com.lagradost.cloudstream3.LiveStreamLoadResponse).dataUrl, false, {}, { link ->
                println("Extracted link: ${link.url}")
                links.add(link.url)
            })
            println("Load links status: $success")
            assertTrue("Should succeed loading links", success)
            assertTrue("Should have loaded at least one link", links.isNotEmpty())
        } else {
            println("No live matches found to test link extraction")
        }
    }
}
