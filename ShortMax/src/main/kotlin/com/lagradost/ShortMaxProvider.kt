package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import org.json.JSONArray
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import android.content.Context

class ShortMaxProvider : MainAPI() {
    companion object {
        var context: Context? = null
        private val PASSWORD = com.lagradost.ShortMax.BuildConfig.SHORTMAX_KEY

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
    override var name = "ShortMax"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("ShortMax - For You", "foryou"),
        MainPageData("ShortMax - Rekomendasi", "rekomendasi")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = if (request.data == "foryou") {
            "/api/shortmax/foryou?page=$page"
        } else {
            "/api/shortmax/rekomendasi"
        }

        val rawHome = app.get("$mainUrl$path").text
        val decrypted = decryptCryptoJS(JSONObject(rawHome).getString("data"))

        val results = if (request.data == "foryou") {
            val jsonObj = JSONObject(decrypted)
            jsonObj.optJSONArray("data")
                ?: jsonObj.optJSONArray("results")
                ?: jsonObj.optJSONArray("list")
                ?: JSONArray()
        } else {
            if (decrypted.trim().startsWith("[")) {
                JSONArray(decrypted)
            } else {
                val jsonObj = JSONObject(decrypted)
                jsonObj.optJSONArray("results")
                    ?: jsonObj.optJSONArray("data")
                    ?: jsonObj.optJSONArray("list")
                    ?: JSONArray()
            }
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

        return newHomePageResponse(request.name, list, hasNext = request.data == "foryou" && results.length() > 0)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val rawSearch = app.get("$mainUrl/api/shortmax/search", params = mapOf("query" to query)).text
        val decrypted = decryptCryptoJS(JSONObject(rawSearch).getString("data"))
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
        val shortPlayId = url
        val rawDetail = app.get("$mainUrl/api/shortmax/detail?shortPlayId=$shortPlayId").text
        val decryptedDetail = decryptCryptoJS(JSONObject(rawDetail).getString("data"))
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
            url = shortPlayId,
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
        val shortPlayId = parts[0]
        val episodeNum = parts[1]

        val rawEpisode = app.get("$mainUrl/api/shortmax/episode?shortPlayId=$shortPlayId&episodeNumber=$episodeNum").text
        val decryptedEpisode = decryptCryptoJS(JSONObject(rawEpisode).getString("data"))
        val json = JSONObject(decryptedEpisode)

        val episodeObj = json.optJSONObject("episode") ?: return false
        val videoUrlObj = episodeObj.optJSONObject("videoUrl") ?: return false

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
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    }
                )
                foundLink = true
            }
        }
        return foundLink
    }
}
