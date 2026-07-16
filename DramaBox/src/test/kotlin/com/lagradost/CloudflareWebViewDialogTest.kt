package com.lagradost

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CloudflareWebViewDialogTest {

    @Test
    fun testIsChallengeTitle() {
        // True cases
        assertTrue(CloudflareWebViewDialog.isChallengeTitle("Just a moment..."))
        assertTrue(CloudflareWebViewDialog.isChallengeTitle("Checking your browser"))
        assertTrue(CloudflareWebViewDialog.isChallengeTitle("Attention Required"))
        assertTrue(CloudflareWebViewDialog.isChallengeTitle("DDoS-Guard"))
        assertTrue(CloudflareWebViewDialog.isChallengeTitle("One more step"))
        assertTrue(CloudflareWebViewDialog.isChallengeTitle("just a moment"))
        
        // False cases
        assertFalse(CloudflareWebViewDialog.isChallengeTitle("Homepage Title"))
        assertFalse(CloudflareWebViewDialog.isChallengeTitle("Search Results"))
        assertFalse(CloudflareWebViewDialog.isChallengeTitle(""))
    }
}
