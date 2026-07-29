package com.xr3ed.support

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.Plugin

class SupportProvider(val plugin: Plugin) : MainAPI() {

    companion object {
        var context: Context? = null

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

        fun openUrlDirectly(url: String) {
            val act = getResumedActivity()
            val ctx = act ?: Companion.context
            if (ctx != null) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override var name = "💻 Support Cloudstream XR"
    override var mainUrl = "https://t.me/CloudstreamXR"
    override val supportedTypes = setOf(TvType.Torrent)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("📢 KOMUNITAS & DUKUNGAN", "support_page")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val list = ArrayList<SearchResponse>()

        val telegramPoster = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/support_telegram.webp"
        val donasiPoster = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/support_donasi.webp"

        list.add(
            newMovieSearchResponse(
                name = "Grup Telegram Cloudstream XR",
                url = "https://t.me/CloudstreamXR",
                type = TvType.Torrent
            ) {
                this.posterUrl = telegramPoster
            }
        )

        list.add(
            newMovieSearchResponse(
                name = "Donasi & Support Pengembang",
                url = "https://lynk.id/xr3ed",
                type = TvType.Torrent
            ) {
                this.posterUrl = donasiPoster
            }
        )

        return newHomePageResponse(request.name, list, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        // Direct intent trigger to open link in browser or Telegram app
        openUrlDirectly(url)

        return newMovieLoadResponse(
            name = if (url.contains("telegram") || url.contains("t.me")) "Grup Telegram" else "Donasi & Support",
            url = url,
            type = TvType.Torrent,
            dataUrl = url
        ) {
            this.posterUrl = if (url.contains("telegram") || url.contains("t.me")) {
                "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/support_telegram.webp"
            } else {
                "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/support_donasi.webp"
            }
            this.plot = "Membuka link $url..."
        }
    }
}
