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
        println("Main page categories loaded. Total: ${result?.data?.size}")
        if (!result?.data.isNullOrEmpty()) {
            println("Category 1 items count: ${result?.data?.get(0)?.list?.size}")
            result?.data?.get(0)?.list?.take(5)?.forEach {
                println("Item: ${it.name} -> ${it.url}")
            }
        }
    }

    @Test
    fun testLoadAndLoadLinks() = runBlocking {
        val provider = Xr3edtvProvider()
        val loadResult = provider.load("go:rcti")
        assertNotNull(loadResult)
        
        val links = mutableListOf<String>()
        val success = provider.loadLinks(loadResult!!.data, false, {}, { link ->
            println("Extracted Link URL: ${link.url}")
            links.add(link.url)
        })
        
        println("Load links status: $success")
        assertTrue("Should succeed loading links", success)
        assertTrue("Should have loaded at least one link", links.isNotEmpty())
    }
}
