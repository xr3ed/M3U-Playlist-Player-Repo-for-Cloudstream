package com.sad25kag.anichinxr

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.*
import kotlin.text.Regex

open class StreamRuby : ExtractorApi() {
    override val name = "StreamRuby"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    private val cleanClient = app.baseClient.newBuilder()
        .dns { hostname ->
            app.baseClient.dns.lookup(hostname).filter { it is java.net.Inet4Address }
        }
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()
    private val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = "embed-([a-zA-Z0-9]+)\\.html".toRegex().find(url)?.groupValues?.get(1) ?: return
        val formBody = okhttp3.FormBody.Builder()
            .add("op", "embed")
            .add("file_code", id)
            .add("auto", "1")
            .add("referer", "")
            .build()
        val request = okhttp3.Request.Builder()
            .url("$mainUrl/dl")
            .post(formBody)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Referer", referer ?: url)
            .build()
        val responseBody = cleanClient.newCall(request).execute().body?.string() ?: ""
        val script = if (!getPacked(responseBody).isNullOrEmpty()) {
            getAndUnpack(responseBody)
        } else {
            org.jsoup.Jsoup.parse(responseBody).selectFirst("script:containsData(sources:)")?.data()
        }
        val m3u8 = Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script ?: return)?.groupValues?.getOrNull(1)
//        Log.d("streamrubby", "m3u8 = $m3u8")
        callback.invoke(newExtractorLink(
            source = this.name,
            name = this.name,
            url  = m3u8.toString(),
            type = ExtractorLinkType.M3U8,
        ) {
            this.quality = Qualities.Unknown.value
            this.referer = url
            this.headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to url
            )
        })
    }
}

class svanila : StreamRuby() {
    override var name = "svanila"
    override var mainUrl = "https://streamruby.net"
}

class svilla : StreamRuby() {
    override var name = "svilla"
    override var mainUrl = "https://streamruby.com"
}
