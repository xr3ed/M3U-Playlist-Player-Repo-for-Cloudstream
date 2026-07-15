package com.sad25kag.gudangfilmxr

import com.lagradost.cloudstream3.extractors.VidhideExtractor

class Morencius : VidhideExtractor() {
    override var name = "Morencius"
    override var mainUrl = "https://morencius.com"
}

class Turbovidhls : VidhideExtractor() {
    override var name = "Turbovidhls"
    override var mainUrl = "https://turbovidhls.com"
}
