package com.sad25kag.filmboxoffice

import android.app.Activity
import android.webkit.CookieManager
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import org.jsoup.Jsoup
import java.net.URLDecoder

class FilmBoxOfficeProvider(val plugin: Plugin) : MainAPI() {

    companion object {
        var isIgnored = false

        private fun getResumedActivity(): Activity? {
            try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
                val activityThread = currentActivityThreadMethod.invoke(null) ?: return null
                val mActivitiesField = activityThreadClass.getDeclaredField("mActivities")
                mActivitiesField.isAccessible = true
                val activities = mActivitiesField.get(activityThread) as? Map<*, *> ?: return null
                for (activityRecord in activities.values) {
                    if (activityRecord == null) continue
                    val pausedField = activityRecord.javaClass.getDeclaredField("paused")
                    pausedField.isAccessible = true
                    val paused = pausedField.get(activityRecord) as? Boolean ?: true
                    if (!paused) {
                        val activityField = activityRecord.javaClass.getDeclaredField("activity")
                        activityField.isAccessible = true
                        val activity = activityField.get(activityRecord) as? Activity
                        if (activity != null && !activity.isFinishing) {
                            return activity
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }
    }

    override var name = "Film Box Office Baru"
    override var mainUrl = "https://www.filmboxoffice.web.id"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("STATUS PREMIUM", "premium_status"),
        MainPageData("Content Random", "random"),
        MainPageData("Terbaru", "latest"),
        MainPageData("Action", "action"),
        MainPageData("Adventure", "adventure"),
        MainPageData("Comedy", "comedy"),
        MainPageData("Drama", "drama"),
        MainPageData("Horror", "horror"),
        MainPageData("Sci-Fi", "sci-fi")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val list = ArrayList<SearchResponse>()
        val activity = getResumedActivity()

        // Trigger startup dialog only when entering the provider main page
        if (activity != null) {
            val isLoggedIn = activity.getKey<Boolean>("FBOX_IS_LOGGED_IN") ?: false
            if (!isLoggedIn && !isIgnored) {
                LoginDialogHelper.showMainDialog(
                    activity,
                    onLoginClick = {
                        LoginDialogHelper.showGoogleLoginWebView(activity) {
                            activity.setKey("FBOX_IS_LOGGED_IN", true)
                            isIgnored = false
                        }
                    },
                    onIgnoreClick = {
                        isIgnored = true
                    }
                )
            }
        }

        if (request.data == "premium_status") {
            val isLoggedIn = activity?.getKey<Boolean>("FBOX_IS_LOGGED_IN") ?: false
            if (isLoggedIn) {
                list.add(
                    newMovieSearchResponse(
                        name = "✓ Status: Premium Aktif",
                        url = "$mainUrl/#status_premium",
                        type = TvType.Movie
                    ) {
                        this.posterUrl = "https://img.icons8.com/flat-round/200/checked-checkbox.png"
                    }
                )
            } else {
                list.add(
                    newMovieSearchResponse(
                        name = "⚠️ Login Akun Google Premium",
                        url = "$mainUrl/#status_premium",
                        type = TvType.Movie
                    ) {
                        this.posterUrl = "https://img.icons8.com/flat-round/200/delete-sign.png"
                    }
                )
            }
            return newHomePageResponse(request.name, list, hasNext = false)
        }

        val url = when (request.data) {
            "random" -> mainUrl
            "latest" -> if (page == 1) mainUrl else "$mainUrl/page/$page/"
            else -> if (page == 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        }

        val doc = app.get(url).document

        if (request.data == "random") {
            doc.select("div#slider1 div.item").forEach { element ->
                val link = element.selectFirst("a")?.attr("href") ?: return@forEach
                val title = element.selectFirst("img")?.attr("alt") ?: ""
                val poster = element.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotEmpty() }
                    ?: element.selectFirst("img")?.attr("src") ?: ""
                val rating = element.selectFirst("span.imdb")?.text()?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()

                 list.add(
                    newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                        rating?.let { this.score = Score.from10(it) }
                    }
                )
            }
        } else {
            doc.select("div.item").forEach { element ->
                val link = element.selectFirst("a")?.attr("href") ?: return@forEach
                val title = element.selectFirst("div.fixyear h2")?.text() 
                    ?: element.selectFirst("img")?.attr("alt") ?: ""
                val poster = element.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotEmpty() }
                    ?: element.selectFirst("img")?.attr("src") ?: ""
                val rating = element.selectFirst("span.imdb")?.text()?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()

                list.add(
                    newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                        rating?.let { this.score = Score.from10(it) }
                    }
                )
            }
        }

        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        val list = ArrayList<SearchResponse>()

        doc.select("div.item").forEach { element ->
            val link = element.selectFirst("a")?.attr("href") ?: return@forEach
            val title = element.selectFirst("div.fixyear h2")?.text() 
                ?: element.selectFirst("img")?.attr("alt") ?: ""
            val poster = element.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: element.selectFirst("img")?.attr("src") ?: ""
            val rating = element.selectFirst("span.imdb")?.text()?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()

            list.add(
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    rating?.let { this.score = Score.from10(it) }
                }
            )
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        val activity = getResumedActivity()

        // Intercept Premium status card click
        if (url.endsWith("#status_premium")) {
            activity?.let { act ->
                val isLoggedIn = act.getKey<Boolean>("FBOX_IS_LOGGED_IN") ?: false
                if (isLoggedIn) {
                    LoginDialogHelper.showAccountStatusDialog(act) {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        act.setKey("FBOX_IS_LOGGED_IN", false)
                        isIgnored = false
                        Toast.makeText(act, "Akun Google berhasil dikeluarkan!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    LoginDialogHelper.showMainDialog(
                        act,
                        onLoginClick = {
                            LoginDialogHelper.showGoogleLoginWebView(act) {
                                act.setKey("FBOX_IS_LOGGED_IN", true)
                                isIgnored = false
                            }
                        },
                        onIgnoreClick = {
                            isIgnored = true
                        }
                    )
                }
            }
            return null
        }

        val doc = app.get(url).document

        val title = doc.selectFirst("h1[itemprop='name']")?.text() ?: ""
        val poster = doc.selectFirst("div.imagen img")?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("div.imagen img")?.attr("src") ?: ""
        val plot = doc.selectFirst("div[itemprop='description']")?.text() ?: ""
        val rating = doc.selectFirst("div.imdb_r span[itemprop='ratingValue']")?.text()?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
        
        // Year
        val year = doc.selectFirst("span.titulo_o i[itemprop='datePublished']")?.text()
            ?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        // Find Google Drive link inside iframe (prefer data-litespeed-src first, then fallback to src)
        var driveUrl = doc.selectFirst("iframe[data-litespeed-src*='drive.google.com']")?.attr("data-litespeed-src")
            ?: doc.selectFirst("iframe[src*='drive.google.com']")?.attr("src")

        // URL decoding in case it is double encoded
        if (driveUrl != null) {
            try {
                driveUrl = URLDecoder.decode(driveUrl, "UTF-8")
            } catch (_: Exception) {}
        }

        return newMovieLoadResponse(title, url, TvType.Movie, driveUrl ?: "") {
            this.posterUrl = poster
            this.plot = plot
            rating?.let { this.score = Score.from10(it) }
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isEmpty() || !data.contains("drive.google.com")) {
            return false
        }

        val activity = getResumedActivity()
        val isLoggedIn = activity?.getKey<Boolean>("FBOX_IS_LOGGED_IN") ?: false

        if (!isLoggedIn) {
            activity?.let { act ->
                act.runOnUiThread {
                    Toast.makeText(
                        act, 
                        "Harap login dengan akun Google Premium untuk memutar film ini!", 
                        Toast.LENGTH_LONG
                    ).show()

                    // Automatically trigger Dialog
                    LoginDialogHelper.showMainDialog(
                        act,
                        onLoginClick = {
                            LoginDialogHelper.showGoogleLoginWebView(act) {
                                act.setKey("FBOX_IS_LOGGED_IN", true)
                                isIgnored = false
                            }
                        },
                        onIgnoreClick = {
                            isIgnored = true
                        }
                    )
                }
            }
            return false
        }

        // Parse File ID from Google Drive URL
        // Format can be: drive.google.com/file/d/FILE_ID/preview or drive.google.com/open?id=FILE_ID
        val fileId = Regex("d/([a-zA-Z0-9_-]+)").find(data)?.groupValues?.get(1)
            ?: Regex("id=([a-zA-Z0-9_-]+)").find(data)?.groupValues?.get(1)
            ?: return false

        try {
            val cookieStr = CookieManager.getInstance().getCookie("https://drive.google.com") ?: ""
            val headers = mapOf("Cookie" to cookieStr)

            val downloadUrl = "https://drive.google.com/uc?export=download&id=$fileId"
            
            // Check large file confirmation screen & check premium authorization status
            val res = app.get(downloadUrl, headers = headers)
            if (res.code == 403 || res.code == 404) {
                activity?.runOnUiThread {
                    Toast.makeText(
                        activity, 
                        "Akses Ditolak: Akun Anda belum disetujui sebagai Premium oleh Admin. Hubungi WA!", 
                        Toast.LENGTH_LONG
                    ).show()
                }
                return false
            }

            val confirmToken = Regex("confirm=([a-zA-Z0-9_-]+)").find(res.text)?.groupValues?.get(1)

            val finalUrl = if (confirmToken != null) {
                "$downloadUrl&confirm=$confirmToken"
            } else {
                downloadUrl
            }

            callback(
                newExtractorLink(
                    source = "Google Drive (Premium)",
                    name = "Google Drive (Premium)",
                    url = finalUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://drive.google.com/"
                    this.quality = Qualities.P720.value
                    this.headers = headers
                }
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            activity?.runOnUiThread {
                Toast.makeText(activity, "Gagal memutar video: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return false
        }
    }
}
