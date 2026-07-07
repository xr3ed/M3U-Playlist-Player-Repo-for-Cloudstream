package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import org.json.JSONObject
import org.json.JSONArray

class DramaBoxProvider : MainAPI() {
    override var mainUrl = "https://www.dramabox.com"
    override var name = "DramaBox"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("Trending", "trending"),
        MainPageData("Terbaru", "latest"),
        MainPageData("Rekomendasi", "recommended")
    )

    private val privateKey: PrivateKey by lazy {
        val modulusStr = "23892337117429496799189268361861321286415201777258550814083499915869153950838117038825935253758787814687321840457885973929816968657422899931883348252707957545782855902026814940012050457170178730908442069867995442121707429465362258603475912382154550428478002164076966878099338782058266760327513250435749808914795724477569963922790713861208807356695157037648134332899572941814434409242030683722702606440154881483529952113433020448891337135091960142567492326002321423993900544509052964596181886364700292093603080895515830809481703980874569205426370694029764906432054143426674329340746934765992654567997104932854187441633"
        val exponentStr = "19115109206679903042127209179814069289265731949692503392508661536060047745613851575396497599223866235676035614396880163435343288792814057892922159984958669728520263111081861967280343037075300691177843707362079512592390339032261765191510940597426027169635276064956400267320485074990632788260867941164497455342983990602084847481830516939268905132740930382999717221391061425733985672046562891847736857644184456131643709977947418694927441664550723859127905963401430274672304504972664711296085432716960937547893801111000225504767723687630217631905296739143855485668611562889065033934047051138440877352103263543054395159745"
        val modulus = java.math.BigInteger(modulusStr)
        val privateExponent = java.math.BigInteger(exponentStr)
        val spec = java.security.spec.RSAPrivateKeySpec(modulus, privateExponent)
        val kf = KeyFactory.getInstance("RSA")
        kf.generatePrivate(spec)
    }

    private val mapper = ObjectMapper().registerModule(
        KotlinModule.Builder().build()
    )

    private var tokenCache: TokenData? = null

    data class TokenData(
        val token: String,
        val deviceId: String,
        val androidId: String,
        val spoffer: String,
        val uid: String,
        val expiry: Long
    )

    private fun generateRandomIP(): String {
        val r = Random()
        return "${r.nextInt(254) + 1}.${r.nextInt(254) + 1}.${r.nextInt(254) + 1}.${r.nextInt(254) + 1}"
    }

    private fun generateUUID(): String {
        return UUID.randomUUID().toString()
    }

    private fun randomAndroidId(): String {
        val chars = "0123456789abcdef"
        val sb = java.lang.StringBuilder()
        val r = Random()
        for (i in 0 until 16) {
            sb.append(chars[r.nextInt(chars.length)])
        }
        return sb.toString()
    }

    private fun getTimeZoneOffset(): String {
        val tz = TimeZone.getDefault()
        val cal = Calendar.getInstance(tz)
        val offsetMs = tz.getOffset(cal.timeInMillis)
        val sign = if (offsetMs >= 0) "+" else "-"
        val absMs = Math.abs(offsetMs)
        val hh = String.format("%02d", absMs / 3600000)
        val mm = String.format("%02d", (absMs % 3600000) / 60000)
        return "$sign$hh$mm"
    }

    private fun sign(data: String, privateKey: PrivateKey): String {
        val privateSignature = Signature.getInstance("SHA256withRSA")
        privateSignature.initSign(privateKey)
        privateSignature.update(data.toByteArray(Charsets.UTF_8))
        val signature = privateSignature.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    private suspend fun getToken(): TokenData {
        val cached = tokenCache
        if (cached != null && cached.expiry > System.currentTimeMillis() + 300000) {
            return cached
        }

        val timestamp = System.currentTimeMillis()
        val spoffer = generateRandomIP()
        val deviceId = generateUUID()
        val androidId = randomAndroidId()
        val body = "{\"distinctId\":null}"
        val signData = "timestamp=$timestamp$body$deviceId$androidId"
        val sn = sign(signData, privateKey)

        val headers = mapOf(
            "tn" to "",
            "version" to "470",
            "vn" to "4.7.0",
            "cid" to "DAUAF1064291",
            "package-Name" to "com.storymatrix.drama",
            "Apn" to "1",
            "device-id" to deviceId,
            "language" to "in",
            "current-Language" to "in",
            "p" to "48",
            "Time-Zone" to getTimeZoneOffset(),
            "md" to "Redmi Note 8",
            "ov" to "9",
            "over-flow" to "new-fly",
            "android-id" to androidId,
            "X-Forwarded-For" to spoffer,
            "X-Real-IP" to spoffer,
            "mf" to "XIAOMI",
            "brand" to "Xiaomi",
            "Content-Type" to "application/json; charset=UTF-8",
            "User-Agent" to "okhttp/4.10.0",
            "sn" to sn
        )

        val url = "https://sapi.dramaboxdb.com/drama-box/ap001/bootstrap?timestamp=$timestamp"
        val resText = app.post(
            url,
            headers = headers,
            json = mapOf("distinctId" to null)
        ).text

        val json = JSONObject(resText)
        val dataObj = json.optJSONObject("data") ?: throw Exception("Invalid bootstrap response")
        val userObj = dataObj.optJSONObject("user") ?: throw Exception("No user data in bootstrap")
        
        val tokenData = TokenData(
            token = userObj.getString("token"),
            deviceId = deviceId,
            androidId = androidId,
            spoffer = spoffer,
            uid = userObj.opt("uid")?.toString() ?: "",
            expiry = System.currentTimeMillis() + 86400000
        )
        tokenCache = tokenData
        return tokenData
    }

    private suspend fun makeRequest(
        endpoint: String,
        payload: Map<String, Any?> = emptyMap(),
        isWebfic: Boolean = false,
        method: String = "POST"
    ): String {
        val timestamp = System.currentTimeMillis()
        val tokenData = getToken()
        val url = if (isWebfic) {
            "https://www.webfic.com$endpoint"
        } else {
            "https://sapi.dramaboxdb.com$endpoint?timestamp=$timestamp"
        }

        val headers = if (isWebfic) {
            mapOf(
                "Content-Type" to "application/json",
                "pline" to "DRAMABOX",
                "language" to "in"
            )
        } else {
            val bodyStr = mapper.writeValueAsString(payload)
            val signData = "timestamp=$timestamp$bodyStr${tokenData.deviceId}${tokenData.androidId}Bearer ${tokenData.token}"
            val sn = sign(signData, privateKey)
            mapOf(
                "tn" to "Bearer ${tokenData.token}",
                "version" to "451",
                "vn" to "4.5.1",
                "cid" to "DAUAF1064291",
                "package-Name" to "com.storymatrix.drama",
                "Apn" to "1",
                "device-id" to tokenData.deviceId,
                "language" to "in",
                "current-Language" to "in",
                "p" to "46",
                "Time-Zone" to getTimeZoneOffset(),
                "md" to "Redmi Note 8",
                "ov" to "14",
                "over-flow" to "new-fly",
                "android-id" to tokenData.androidId,
                "mf" to "XIAOMI",
                "brand" to "Xiaomi",
                "X-Forwarded-For" to tokenData.spoffer,
                "X-Real-IP" to tokenData.spoffer,
                "Content-Type" to "application/json; charset=UTF-8",
                "User-Agent" to "okhttp/4.10.0",
                "sn" to sn
            )
        }

        val response = if (method == "GET") {
            app.get(url, headers = headers)
        } else {
            app.post(url, headers = headers, json = payload)
        }
        return response.text
    }

    private suspend fun getWithRetry(url: String, params: Map<String, String> = emptyMap(), retries: Int = 3): String {
        var lastException: Exception? = null
        for (attempt in 1..retries) {
            try {
                return app.get(url, params = params, timeout = 30).text
            } catch (e: Exception) {
                lastException = e
                if (attempt < retries) {
                    try {
                        Thread.sleep(1000)
                    } catch (ie: InterruptedException) {
                        // ignore
                    }
                }
            }
        }
        throw lastException ?: Exception("Network request failed")
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val homePages = ArrayList<HomePageList>()

        try {
            if (page > 1) {
                // Hanya muat Kategori Lainnya saat scroll halaman berikutnya
                val forYouRes = getWithRetry("https://nax1.cc/api/dramabox/foryou", mapOf("page" to page.toString()))
                val forYouList = parseSekaiDramaList(forYouRes)
                if (forYouList.isNotEmpty()) {
                    homePages.add(HomePageList("Lainnya", forYouList))
                }
                return if (homePages.isNotEmpty()) {
                    newHomePageResponse(homePages, hasNext = page < 11)
                } else {
                    null
                }
            }

            // Halaman Pertama (page == 1): Muat semua kategori
            // Trending
            val trendingRes = getWithRetry("https://nax1.cc/api/dramabox/trending")
            val trendingList = parseSekaiDramaList(trendingRes)
            if (trendingList.isNotEmpty()) {
                homePages.add(HomePageList("Trending", trendingList))
            }

            // Terbaru
            val latestRes = getWithRetry("https://nax1.cc/api/dramabox/latest")
            val latestList = parseSekaiDramaList(latestRes)
            if (latestList.isNotEmpty()) {
                homePages.add(HomePageList("Terbaru", latestList))
            }

            // Sulih Suara Populer
            val voicePopRes = getWithRetry("https://nax1.cc/api/dramabox/dubindo", mapOf("classify" to "terpopuler"))
            val voicePopList = parseSekaiDramaList(voicePopRes)
            if (voicePopList.isNotEmpty()) {
                homePages.add(HomePageList("Sulih Suara Populer", voicePopList))
            }

            // Sulih Suara Terbaru
            val voiceNewRes = getWithRetry("https://nax1.cc/api/dramabox/dubindo", mapOf("classify" to "terbaru"))
            val voiceNewList = parseSekaiDramaList(voiceNewRes)
            if (voiceNewList.isNotEmpty()) {
                homePages.add(HomePageList("Sulih Suara Terbaru", voiceNewList))
            }

            // Lainnya (Halaman 1)
            val forYouRes = getWithRetry("https://nax1.cc/api/dramabox/foryou", mapOf("page" to "1"))
            val forYouList = parseSekaiDramaList(forYouRes)
            if (forYouList.isNotEmpty()) {
                homePages.add(HomePageList("Lainnya", forYouList))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return if (homePages.isNotEmpty()) {
            newHomePageResponse(homePages, hasNext = true)
        } else {
            null
        }
    }

    private fun parseSekaiDramaList(jsonStr: String): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val book = jsonArray.optJSONObject(i) ?: continue
                val bookId = book.optString("bookId")
                val bookName = book.optString("bookName")
                val cover = book.optString("coverWap").ifEmpty { book.optString("cover") }
                
                results.add(
                    newTvSeriesSearchResponse(
                        name = bookName,
                        url = "$mainUrl/play/$bookId",
                        type = TvType.TvSeries
                    ) {
                        this.posterUrl = cover
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val resStr = getWithRetry("https://nax1.cc/api/dramabox/search", mapOf("query" to query))
            return parseSekaiDramaList(resStr)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }
    override suspend fun load(url: String): LoadResponse? {
        val bookId = when {
            url.contains("/play/") -> url.substringAfter("/play/").substringBefore("?").substringBefore("/")
            url.contains("/detail/") -> url.substringAfter("/detail/").substringBefore("?").substringBefore("/")
            else -> url.substringAfterLast("/").substringBefore("?")
        }
        if (bookId.isEmpty()) return null

        try {
            // 1. Fetch metadata dari SekaiDrama API dengan retry
            val detailRes = getWithRetry("https://nax1.cc/api/dramabox/detail", mapOf("bookId" to bookId))
            val detailJson = JSONObject(detailRes)
            val bookName = detailJson.optString("bookName").ifEmpty { "DramaBox" }
            val cover = detailJson.optString("coverWap").ifEmpty { detailJson.optString("cover") }
            val introduction = detailJson.optString("introduction").ifEmpty { "Saksikan drama pendek menarik di DramaBox." }

            // 2. Fetch seluruh episode dari SekaiDrama API dengan retry
            val episodesRes = getWithRetry("https://nax1.cc/api/dramabox/allepisode", mapOf("bookId" to bookId))
            val chapterList = JSONArray(episodesRes)

            val episodes = ArrayList<Episode>()
            for (i in 0 until chapterList.length()) {
                val ch = chapterList.optJSONObject(i) ?: continue
                val chapterIndex = ch.optInt("chapterIndex")
                val chapterName = ch.optString("chapterName").ifEmpty { "EP ${chapterIndex + 1}" }
                val coverUrl = ch.optString("chapterImg").ifEmpty { ch.optString("chapterImgMap") }
                
                var videoPath: String? = null
                val cdnList = ch.optJSONArray("cdnList")
                if (cdnList != null) {
                    for (j in 0 until cdnList.length()) {
                        val cdn = cdnList.optJSONObject(j) ?: continue
                        if (cdn.optInt("isDefault") == 1 || videoPath == null) {
                            val videoPathList = cdn.optJSONArray("videoPathList")
                            if (videoPathList != null && videoPathList.length() > 0) {
                                var preferred = videoPathList.optJSONObject(0)
                                for (k in 0 until videoPathList.length()) {
                                    val v = videoPathList.optJSONObject(k) ?: continue
                                    if (v.optInt("isDefault") == 1) {
                                        preferred = v
                                        break
                                    }
                                }
                                videoPath = preferred?.optString("videoPath")
                            }
                        }
                    }
                }

                var finalVideoPath = videoPath ?: ""
                if (finalVideoPath.isNotEmpty() && (finalVideoPath.contains(".encrypt.mp4") || finalVideoPath.contains("etavirp_nuyila"))) {
                    finalVideoPath = "https://nax1.cc/api/dramabox/decrypt-stream?url=" + java.net.URLEncoder.encode(finalVideoPath, "UTF-8")
                }
                
                episodes.add(
                    newEpisode(
                        "$bookId|$chapterIndex|$finalVideoPath"
                    ) {
                        this.name = chapterName
                        this.episode = chapterIndex
                        this.season = 1
                        this.posterUrl = coverUrl
                    }
                )
            }

            return newTvSeriesLoadResponse(
                name = bookName,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = cover
                this.plot = introduction
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) return false
        val bookId = parts[0]
        val episodeIndex = parts[1]
        val cachedVideoPath = parts.getOrNull(2)

        if (!cachedVideoPath.isNullOrEmpty() && (cachedVideoPath.startsWith("http://") || cachedVideoPath.startsWith("https://"))) {
            val isM3u8 = cachedVideoPath.contains(".m3u8", ignoreCase = true)
            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(
                    name = "$name Resmi",
                    source = name,
                    url = cachedVideoPath,
                    type = linkType
                ) {
                    this.quality = Qualities.P720.value
                    this.headers = mapOf(
                        "Referer" to "https://drama.sansekai.my.id/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                    )
                }
            )
            return true
        }

        try {
            val url = "https://regexd.com/base.php"
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$url?bookId=$bookId"
            )
            val resText = app.get(
                url,
                params = mapOf(
                    "ajax" to "1",
                    "bookId" to bookId,
                    "lang" to "in",
                    "episode" to episodeIndex
                ),
                headers = headers,
                timeout = 20
            ).text

            val json = JSONObject(resText)
            val chapterObj = json.optJSONObject("chapter") ?: return false
            
            val m3u8Url = chapterObj.optString("m3u8Url").ifEmpty { chapterObj.optString("m3u8") }
            if (m3u8Url.isNotEmpty() && (m3u8Url.startsWith("http://") || m3u8Url.startsWith("https://"))) {
                callback.invoke(
                    newExtractorLink(
                        name = "$name Bypass HLS",
                        source = name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P720.value
                        this.headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                        )
                    }
                )
                return true
            }

            val mp4Url = chapterObj.optString("mp4")
            if (mp4Url.isNotEmpty() && (mp4Url.startsWith("http://") || mp4Url.startsWith("https://"))) {
                callback.invoke(
                    newExtractorLink(
                        name = "$name Bypass MP4",
                        source = name,
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P720.value
                        this.headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                        )
                    }
                )
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }
}
