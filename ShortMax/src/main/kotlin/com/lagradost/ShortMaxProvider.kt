package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import org.json.JSONObject
import org.json.JSONArray
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

class ShortMaxProvider : MainAPI() {
    companion object {
        private var cfDeferred: Deferred<Boolean>? = null
        private val PASSWORD = com.lagradost.ShortMax.BuildConfig.SHORTMAX_KEY

        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun getCfCookies(context: Context?): String? = context?.getKey<String>("SHORTMAX_CF_COOKIES")
        fun setCfCookies(context: Context?, value: String?) {
            context?.setKey("SHORTMAX_CF_COOKIES", value)
        }

        fun getCfUserAgent(context: Context?): String? = context?.getKey<String>("SHORTMAX_CF_USER_AGENT")
        fun setCfUserAgent(context: Context?, value: String?) {
            context?.setKey("SHORTMAX_CF_USER_AGENT", value)
        }

        fun decryptCryptoJS(encryptedText: String): String {
            val ciphertextBytes = Base64.decode(encryptedText, Base64.DEFAULT)
            val header = "Salted__".toByteArray(Charsets.US_ASCII)
            if (ciphertextBytes.size < 16 || !ciphertextBytes.sliceArray(0..7).contentEquals(header)) {
                throw IllegalArgumentException("Invalid CryptoJS ciphertext")
            }
            val salt = ciphertextBytes.sliceArray(8..15)
            val encrypted = ciphertextBytes.sliceArray(16 until ciphertextBytes.size)

            val passwordBytes = PASSWORD.toByteArray(Charsets.UTF_8)
            val keyIv = ByteArray(48)
            var prev = ByteArray(0)
            var keyIvOffset = 0
            val md = MessageDigest.getInstance("MD5")

            while (keyIvOffset < keyIv.size) {
                md.reset()
                md.update(prev)
                md.update(passwordBytes)
                md.update(salt)
                prev = md.digest()
                val copyLen = min(prev.size, keyIv.size - keyIvOffset)
                System.arraycopy(prev, 0, keyIv, keyIvOffset, copyLen)
                keyIvOffset += copyLen
            }

            val key = keyIv.sliceArray(0..31)
            val iv = keyIv.sliceArray(32..47)

            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            return String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }
    }

    override var mainUrl = com.lagradost.ShortMax.BuildConfig.SHORTMAX_URL
    override var name = "#Dracin ShortMax"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("ShortMax - For You", "foryou"),
        MainPageData("ShortMax - Rekomendasi", "rekomendasi"),
        MainPageData("ShortMax - Dub Indo", "dubindo")
    )

    private fun showToast(msg: String) {
        val act = CommonActivity.activity
        if (act != null) {
            act.runOnUiThread {
                android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkResponse(response: String) {
        val trimmed = response.trim()
        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            throw Exception("Harap selesaikan tantangan Cloudflare (Klik Buka di Peramban / Ikon Bumi di kanan atas)!")
        }
    }

    private suspend fun solveCloudflare(activity: AppCompatActivity, url: String): Boolean {
        val deferred = synchronized(this) {
            cfDeferred?.let { return@synchronized it }

            val newDeferred = CompletableDeferred<Boolean>()
            cfDeferred = newDeferred

            activity.runOnUiThread {
                var resumed = false
                fun safeResume(success: Boolean) {
                    if (!resumed) {
                        resumed = true
                        newDeferred.complete(success)
                    }
                }
                val dialog = CloudflareWebViewDialog(
                    targetUrl = url,
                    onFinished = { success -> safeResume(success) }
                )
                newDeferred.invokeOnCompletion {
                    activity.runOnUiThread {
                        runCatching { dialog.dismissAllowingStateLoss() }
                    }
                }
                dialog.show(activity.supportFragmentManager, "cf_bypass_shortmax")
            }
            newDeferred
        }

        val result = deferred.await()

        synchronized(this) {
            if (cfDeferred === deferred) {
                cfDeferred = null
            }
        }
        return result
    }

    private suspend fun requestWithCf(url: String, params: Map<String, String>? = null): String {
        val ctx = CommonActivity.activity
        val currentCookies = getCfCookies(ctx)
        val currentUA = getCfUserAgent(ctx)
        System.out.println("ShortMax request: $url | cfCookies: $currentCookies | cfUA: $currentUA")
        val headersMap = mutableMapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to (currentUA ?: USER_AGENT)
        )
        currentCookies?.let { headersMap["Cookie"] = it }

        val response = if (params != null) {
            app.get(url, params = params, headers = headersMap).text
        } else {
            app.get(url, headers = headersMap).text
        }
        System.out.println("ShortMax response: ${response.take(300)}")

        try {
            checkResponse(response)
            return response
        } catch (e: Exception) {
            val activity = (CommonActivity.activity as? AppCompatActivity) ?: throw e
            setCfCookies(ctx, null)
            setCfUserAgent(ctx, null)
            val solved = solveCloudflare(activity, mainUrl)
            if (solved) {
                val newCookies = getCfCookies(ctx)
                val newUA = getCfUserAgent(ctx)
                val newHeadersMap = mutableMapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to (newUA ?: USER_AGENT)
                )
                newCookies?.let { newHeadersMap["Cookie"] = it }
                val retryResponse = if (params != null) {
                    app.get(url, params = params, headers = newHeadersMap).text
                } else {
                    app.get(url, headers = newHeadersMap).text
                }
                checkResponse(retryResponse)
                return retryResponse
            } else {
                throw e
            }
        }
    }

    private suspend fun fetchForyou(page: Int): List<SearchResponse> {
        val path = "/api/shortmax/foryou?page=$page"
        val rawHome = try {
            requestWithCf("$mainUrl$path")
        } catch (e: Exception) {
            return emptyList()
        }

        val decrypted = try {
            val json = JSONObject(rawHome)
            if (json.has("data")) {
                decryptCryptoJS(json.getString("data"))
            } else {
                throw Exception("Respon server tidak valid.")
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val jsonObj = JSONObject(decrypted)
        val results = jsonObj.optJSONArray("data")
            ?: jsonObj.optJSONArray("results")
            ?: jsonObj.optJSONArray("list")
            ?: JSONArray()

        val list = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optString("shortPlayId").takeIf { it.isNotEmpty() }
                ?: item.optString("id").takeIf { it.isNotEmpty() }
                ?: continue
            val title = item.optString("title").takeIf { it.isNotEmpty() }
                ?: item.optString("name").takeIf { it.isNotEmpty() }
                ?: "Drama $id"
            val cover = item.optString("cover").takeIf { it.isNotEmpty() }
                ?: item.optString("image").takeIf { it.isNotEmpty() }
                ?: ""

            list.add(
                newTvSeriesSearchResponse(
                    name = title,
                    url = id,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = cover
                }
            )
        }
        return list
    }

    private suspend fun fetchRekomendasi(): List<SearchResponse> {
        val path = "/api/shortmax/rekomendasi"
        val rawHome = try {
            requestWithCf("$mainUrl$path")
        } catch (e: Exception) {
            return emptyList()
        }

        val decrypted = try {
            val json = JSONObject(rawHome)
            if (json.has("data")) {
                decryptCryptoJS(json.getString("data"))
            } else {
                throw Exception("Respon server tidak valid.")
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val results = if (decrypted.trim().startsWith("[")) {
            JSONArray(decrypted)
        } else {
            val jsonObj = JSONObject(decrypted)
            jsonObj.optJSONArray("results")
                ?: jsonObj.optJSONArray("data")
                ?: jsonObj.optJSONArray("list")
                ?: JSONArray()
        }

        val list = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optString("shortPlayId").takeIf { it.isNotEmpty() }
                ?: item.optString("id").takeIf { it.isNotEmpty() }
                ?: continue
            val title = item.optString("title").takeIf { it.isNotEmpty() }
                ?: item.optString("name").takeIf { it.isNotEmpty() }
                ?: "Drama $id"
            val cover = item.optString("cover").takeIf { it.isNotEmpty() }
                ?: item.optString("image").takeIf { it.isNotEmpty() }
                ?: ""

            list.add(
                newTvSeriesSearchResponse(
                    name = title,
                    url = id,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = cover
                }
            )
        }
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        fun isDubOrIndo(title: String): Boolean {
            val titleLower = title.lowercase()
            return titleLower.contains("dub") || titleLower.contains("indo") || titleLower.contains("sulih")
        }

        if (request.data == "dubindo") {
            val foryouItems = fetchForyou(1)
            val rekomendasiItems = fetchRekomendasi()
            
            val dubList = (foryouItems + rekomendasiItems)
                .filter { isDubOrIndo(it.name) }
                .distinctBy { it.url }

            return newHomePageResponse(request.name, dubList, hasNext = false)
        }

        val items = if (request.data == "foryou") {
            fetchForyou(page)
        } else {
            fetchRekomendasi()
        }

        val filteredList = items.filter { !isDubOrIndo(it.name) }

        return newHomePageResponse(request.name, filteredList, hasNext = request.data == "foryou" && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val rawSearch = try {
            requestWithCf("$mainUrl/api/shortmax/search", mapOf("query" to query))
        } catch (e: Exception) {
            showToast("Gagal mencari drama. Server sedang gangguan.")
            return emptyList()
        }

        val decrypted = try {
            val json = JSONObject(rawSearch)
            decryptCryptoJS(json.getString("data"))
        } catch (e: Exception) {
            showToast("Gagal mencari drama. Server sedang gangguan.")
            return emptyList()
        }
        val json = JSONObject(decrypted)
        val results = json.optJSONArray("results") ?: return emptyList()

        val list = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optString("shortPlayId").takeIf { it.isNotEmpty() }
                ?: item.optString("id").takeIf { it.isNotEmpty() }
                ?: continue
            val title = item.optString("title").takeIf { it.isNotEmpty() }
                ?: item.optString("name").takeIf { it.isNotEmpty() }
                ?: "Drama $id"
            val cover = item.optString("cover").takeIf { it.isNotEmpty() }
                ?: item.optString("image").takeIf { it.isNotEmpty() }
                ?: ""

            list.add(
                newTvSeriesSearchResponse(
                    name = title,
                    url = id,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = cover
                }
            )
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        val shortPlayId = when {
            url.contains("lynk.id") -> url.substringAfterLast("#", "")
            url.contains("/detail/") -> url.substringAfter("/detail/").substringBefore("?").substringBefore("/")
            else -> url.substringAfterLast("/").substringBefore("?")
        }
        if (shortPlayId.isEmpty()) return null

        val rawDetail = try {
            requestWithCf("$mainUrl/api/shortmax/detail?shortPlayId=$shortPlayId")
        } catch (e: Exception) {
            showToast("Gagal memuat detail drama. Server sedang gangguan.")
            return null
        }

        val decryptedDetail = try {
            val json = JSONObject(rawDetail)
            decryptCryptoJS(json.getString("data"))
        } catch (e: Exception) {
            showToast("Gagal memuat detail drama. Server sedang gangguan.")
            return null
        }
        val json = JSONObject(decryptedDetail)

        val title = json.optString("title").takeIf { it.isNotEmpty() } ?: "Drama $shortPlayId"
        val cover = json.optString("cover")
        val description = json.optString("description")

        val totalEpisodes = json.optInt("totalEpisodes").takeIf { it > 0 }
            ?: json.optInt("updateEpisode").takeIf { it > 0 }
            ?: 100

        val episodes = (1..totalEpisodes).map { num ->
            newEpisode(
                data = "$shortPlayId|$num"
            ) {
                this.name = "Episode $num"
                this.episode = num
            }
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = "https://lynk.id/xr3ed#$shortPlayId",
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl = cover
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) return false
        var shortPlayId = parts[0]
        if (shortPlayId.startsWith("http://") || shortPlayId.startsWith("https://")) {
            val parsedId = when {
                shortPlayId.contains("/play/") -> shortPlayId.substringAfter("/play/").substringBefore("?").substringBefore("/")
                shortPlayId.contains("/detail/") -> shortPlayId.substringAfter("/detail/").substringBefore("?").substringBefore("/")
                else -> shortPlayId.substringAfterLast("/").substringBefore("?")
            }
            if (parsedId.isNotEmpty()) {
                shortPlayId = parsedId
            }
        }
        val episodeNum = parts[1]

        val rawEpisode = try {
            requestWithCf("$mainUrl/api/shortmax/episode?shortPlayId=$shortPlayId&episodeNumber=$episodeNum")
        } catch (e: Exception) {
            showToast("Gagal memuat video. Server sedang gangguan.")
            return false
        }

        val decryptedEpisode = try {
            val json = JSONObject(rawEpisode)
            decryptCryptoJS(json.getString("data"))
        } catch (e: Exception) {
            showToast("Gagal memuat video. Server sedang gangguan.")
            return false
        }
        val json = JSONObject(decryptedEpisode)

        val episodeObj = json.optJSONObject("episode") ?: return false
        val videoUrlObj = episodeObj.optJSONObject("videoUrl") ?: return false

        // Siapkan header pemutar video (Cookie + User-Agent)
        val currentUA = getCfUserAgent(CommonActivity.activity) ?: USER_AGENT
        val currentCookies = getCfCookies(CommonActivity.activity)
        val headersMap = mutableMapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to currentUA
        )
        currentCookies?.let { headersMap["Cookie"] = it }

        var foundLink = false
        for (key in listOf("video_1080", "video_720", "video_480")) {
            val videoPath = videoUrlObj.optString(key)
            if (videoPath.isNotEmpty()) {
                val absoluteUrl = if (videoPath.startsWith("http")) videoPath else "$mainUrl$videoPath"
                val quality = when (key) {
                    "video_1080" -> Qualities.P1080.value
                    "video_720" -> Qualities.P720.value
                    else -> Qualities.P480.value
                }
                callback.invoke(
                    newExtractorLink(
                        name = "ShortMax - $key",
                        source = this.name,
                        url = absoluteUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = quality
                        this.headers = headersMap
                    }
                )
                foundLink = true
            }
        }
        return foundLink
    }
}
