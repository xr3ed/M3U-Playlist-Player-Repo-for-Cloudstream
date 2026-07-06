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
        val result = provider.getMainPage(1, MainPageRequest("https://www.rbtvplus18.hair/", 1))
        assertNotNull(result)
        println("Main page search results count: ${result?.data?.size}")
    }

    @Test
    fun testLoadAndLoadLinks() = runBlocking {
        val provider = RBTVPlusProvider()
        // Let's get the main page first to find a real match
        val mainPage = provider.getMainPage(1, MainPageRequest("https://www.rbtvplus18.hair/", 1))
        assertNotNull(mainPage)
        var detailUrl: String? = null
        for (category in mainPage!!.data) {
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
            
            // Now load links
            val links = mutableListOf<String>()
            val success = provider.loadLinks(loadResult!!.data, false, {}, { link ->
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
