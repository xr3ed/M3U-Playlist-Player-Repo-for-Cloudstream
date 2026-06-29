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
    fun testGetMainPage() = runBlocking {
        val provider = Xr3edtvProvider()
        val result = provider.getMainPage(1, MainPageRequest("https://xys1-depan.pages.dev", 1))
        assertNotNull(result)
        println("Main page categories loaded. Total rows/categories: ${result?.data?.size}")
        
        result?.data?.forEach { row ->
            println("Category Row: ${row.name} - Channels Count: ${row.list.size}")
            row.list.take(3).forEach { ch ->
                println("  Channel: ${ch.name} -> URL: ${ch.url}")
            }
        }
        assertTrue("Should have loaded at least one category row", result!!.data.isNotEmpty())
    }

    @Test
    fun testLoadAndLoadLinks() = runBlocking {
        val provider = Xr3edtvProvider()
        
        // Test loading links for RCTI
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
