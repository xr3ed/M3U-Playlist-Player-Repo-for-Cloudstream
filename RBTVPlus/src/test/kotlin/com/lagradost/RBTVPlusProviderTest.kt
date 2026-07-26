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
class RBTVPlusProviderTest {

    @Test
    fun testGetMainPage() = runBlocking {
        val provider = RBTVPlusProvider()
        val result = provider.getMainPage(1, MainPageRequest("Live Event", "Live Event", true))
        assertNotNull(result)
        println("Main page search results count: ${result?.items?.size}")
    }

    @Test
    fun testLoadAndLoadLinks() = runBlocking {
        val provider = RBTVPlusProvider()
        val mainPage = provider.getMainPage(1, MainPageRequest("Live Event", "Live Event", true))
        assertNotNull(mainPage)
        val detailUrls = mutableListOf<String>()
        for (category in mainPage!!.items) {
            for (match in category.list) {
                if (match.url.contains("detail.html")) {
                    detailUrls.add(match.url)
                }
            }
        }

        var anyMatchSucceeded = false
        for (detailUrl in detailUrls) {
            println("Trying match URL: $detailUrl")
            try {
                val loadResult = provider.load(detailUrl)
                if (loadResult is com.lagradost.cloudstream3.LiveStreamLoadResponse) {
                    val links = mutableListOf<String>()
                    val success = provider.loadLinks(loadResult.dataUrl, false, {}, { link ->
                        println("Extracted link: ${link.url}")
                        links.add(link.url)
                    })
                    if (success && links.isNotEmpty()) {
                        anyMatchSucceeded = true
                        println("Successfully verified link extraction for match: $detailUrl")
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (detailUrls.isNotEmpty()) {
            assertTrue("Should have loaded at least one link from any of the available matches", anyMatchSucceeded)
        } else {
            println("No matches found to test link extraction")
        }
    }
}
