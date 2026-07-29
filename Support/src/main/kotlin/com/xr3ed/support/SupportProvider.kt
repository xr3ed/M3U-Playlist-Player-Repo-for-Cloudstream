package com.xr3ed.support

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink

class SupportProvider(val plugin: Plugin) : MainAPI() {

    companion object {
        var context: Context? = null
        @Volatile var isDialogShowing = false
        @Volatile var getMainPageFinishedAt = 0L

        private fun getResumedActivity(): Activity? {
            try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null) ?: return null
                val mActivitiesField = activityThreadClass.getDeclaredField("mActivities")
                mActivitiesField.isAccessible = true
                val activities = mActivitiesField.get(activityThread) as? Map<*, *> ?: return null
                for (activityRecord in activities.values) {
                    if (activityRecord == null) continue
                    val activityField = activityRecord.javaClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    val act = activityField.get(activityRecord) as? Activity ?: continue
                    if (!act.isFinishing) return act
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }
    }

    override var name = "💻 Support CloudstreamXR"
    override var mainUrl = "https://cloudstream.xr/support"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("📢 KOMUNITAS & DUKUNGAN", "support_page"),
        MainPageData("", "support_hero")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        isDialogShowing = false
        getMainPageFinishedAt = System.currentTimeMillis()

        val telegramPoster = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/support_telegram.webp"
        val donasiPoster = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/support_donasi.webp"

        if (request.data == "support_hero") {
            val blank = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/blank.png"
            val heroList = listOf(
                newMovieSearchResponse("", "$mainUrl/#hero_telegram", TvType.Others) { this.posterUrl = blank },
                newMovieSearchResponse("", "$mainUrl/#hero_donasi", TvType.Others) { this.posterUrl = blank }
            )
            return newHomePageResponse(request.name, heroList, hasNext = false)
        }

        val list = listOf(
            newMovieSearchResponse("Grup Telegram CloudstreamXR", "$mainUrl/#telegram", TvType.Others) { this.posterUrl = telegramPoster },
            newMovieSearchResponse("Donasi & Support Pengembang", "$mainUrl/#donasi", TvType.Others) { this.posterUrl = donasiPoster }
        )
        return newHomePageResponse(request.name, list, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val isTelegram = url.contains("telegram") || url.contains("t.me")
        val isDonasi = url.contains("donasi") || url.contains("lynk.id")

        val title = if (isTelegram) "Grup Telegram CloudstreamXR" else "Donasi & Support Pengembang"
        val poster = if (isTelegram) {
            "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/support_telegram.webp"
        } else {
            "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/support_donasi.webp"
        }

        // URL #hero_* → return LoadResponse (di-cache Cloudstream, untuk hero banner)
        // URL #telegram/#donasi → return null (tidak di-cache, load() dipanggil ulang saat klik)
        if (url.contains("#hero_")) {
            return newMovieLoadResponse(title, url, TvType.Others, url) {
                this.posterUrl = poster
                this.plot = "Klik untuk membuka link."
            }
        }

        // Poster baris kedua: tampilkan dialog lalu return null
        val isStartupPrefetch = (System.currentTimeMillis() - getMainPageFinishedAt) < 500
        if (!isStartupPrefetch && !isDialogShowing && (isTelegram || isDonasi)) {
            val act = getResumedActivity()
            if (act != null) {
                isDialogShowing = true
                act.runOnUiThread {
                    try {
                        act.window?.decorView?.clearAnimation()
                        act.overridePendingTransition(0, 0)
                        act.onBackPressed()
                    } catch (e: Exception) {}
                }
                if (isTelegram) {
                    SupportDialogHelper.showTelegramDialog(act) { isDialogShowing = false }
                } else {
                    SupportDialogHelper.showDonasiDialog(act) { isDialogShowing = false }
                }
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
