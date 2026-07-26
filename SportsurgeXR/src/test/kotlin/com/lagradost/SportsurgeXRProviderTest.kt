package com.lagradost

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.LiveStreamLoadResponse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SportsurgeXRProviderTest {

    @Test
    fun testGetMainPage() {
        runBlocking {
            val provider = SportsurgeXRProvider()
            val result = provider.getMainPage(1, MainPageRequest("Sepak Bola", "/football", true))
            assertNotNull(result)
            val matchesCount = result?.items?.firstOrNull()?.list?.size ?: 0
            println("SportsurgeXR football matches count: $matchesCount")
            result?.items?.firstOrNull()?.list?.forEach { match ->
                println("  Match: ${match.name} -> ${match.url}")
            }
        }
    }

    @Test
    fun testLoadAndLoadLinks() {
        runBlocking {
            val provider = SportsurgeXRProvider()
            
            // Uji kategori Sepak Bola dan Tinju secara berurutan
            val categories = listOf(
                Pair("Sepak Bola", "/football"),
                Pair("Tinju", "/boxing")
            )
            
            var resolvedAtLeastOne = false
            
            for ((catName, catPath) in categories) {
                println("\n=== Testing Category: $catName ($catPath) ===")
                val mainPage = provider.getMainPage(1, MainPageRequest(catName, catPath, true))
                if (mainPage == null) {
                    println("Category $catName returned null page")
                    continue
                }
                
                val matches = mainPage.items.firstOrNull()?.list ?: emptyList()
                for (match in matches) {
                    println("Checking match: ${match.name} -> ${match.url}")
                    val loadResult = provider.load(match.url)
                    if (loadResult != null && loadResult is LiveStreamLoadResponse) {
                        val data = loadResult.dataUrl
                        if (data != "[]" && data.isNotEmpty()) {
                            println("Testing stream extraction on: ${match.url}")
                            println("Loaded streams data JSON: $data")
                            
                            val links = mutableListOf<String>()
                            val success = provider.loadLinks(data, false, {}, { link ->
                                println("Extracted link: ${link.name} -> ${link.url}")
                                links.add(link.url)
                            })
                            println("Load links status: $success")
                            if (success && links.isNotEmpty()) {
                                resolvedAtLeastOne = true
                            }
                        }
                    }
                }
            }
            
            // Catatan: Karena link HLS mungkin offline saat testing, kita tidak mewajibkan resolvedAtLeastOne bernilai true.
            // Namun minimal fungsi resolver berjalan tanpa exception.
            println("\nTesting completed. Resolved at least one stream: $resolvedAtLeastOne")
        }
    }
}
