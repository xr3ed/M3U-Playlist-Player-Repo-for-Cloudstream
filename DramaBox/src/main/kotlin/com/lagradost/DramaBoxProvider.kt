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
        val base64Key = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC9Q4Y5QX5j08Hr" +
                "nbY3irfKdkEllAU2OORnAjlXDyCzcm2Z6ZRrGvtTZUAMelfU5PWS6XGEm3d4kJEK" +
                "bXi4Crl8o2E/E3YJPk1lQD1d0JTdrvZleETN1ViHZFSQwS3L94Woh0E3TPebaEYq" +
                "88eExvKu1tDdjSoFjBbgMezySnas5Nc2xF28XhPuC8m15u+dectsrJl+ALGcTDX3" +
                "Lv3FURuwV/dN7WMEkgcseIKVMdJxzUB0PeSqCNftfxmdBV/U4yXFRxPhnSFSXCrk" +
                "j6uJjickiYq1pQ1aZfrQe1eLD3MB2hKq7crhMcA3kpggQlnmy1wRR4BAttmSU4fP" +
                "b/yF8D3hAgMBAAECggEBAJdru6p5RLZ3h/GLF2rud8bqv4piF51e/RWQyPFnMAGB" +
                "rkByiYT7bFI3cnvJMhYpLHRigqjWfUofV3thRDDym54lVLtTRZ91khRMxgwVwdRu" +
                "k8Fw7JNFenOwCJxbgdlq6iuAMuQclwll7qWUrm8DgMvzH93xf8o6X171cp4Sh0og" +
                "1Ra7E9GZ37dzBlX2aJBK8VBfctZntuDPx52e71nafqfbjXxZuEtpu92oJd6A9mWb" +
                "d0BZTk72ZHUmDcKcqjfcEH19SWOphMJFYkxU5FRoIEr3/zisyTO4Mt33ZmwELOrY" +
                "9PdlyAAyed7ZoH+hlTr7c025QROvb2LmqgRiUT56tMECgYEA+jH5m6iMRK6XjiBh" +
                "SUnlr3DzRybwlQrtIj5sZprWe2my5uYHG3jbViYIO7GtQvMTnDrBCxNhuM6dPrL0" +
                "cRnbsp/iBMXe3pyjT/aWveBkn4R+UpBsnbtDn28r1MZpCDtr5UNc0TPj4KFJvjnV" +
                "/e8oGoyYEroECqcw1LqNOGDiLhkCgYEAwaemNePYrXW+MVX/hatfLQ96tpxwf7yu" +
                "HdENZ2q5AFw73GJWYvC8VY+TcoKPAmeoCUMltI3TrS6K5Q/GoLd5K2BsoJrSxQNQ" +
                "Fd3ehWAtdOuPDvQ5rn/2fsvgvc3rOvJh7uNnwEZCI/45WQg+UFWref4PPc+ArNtp" +
                "9Xj2y7LndwkCgYARojIQeXmhYZjG6JtSugWZLuHGkwUDzChYcIPdW25ndluokG/R" +
                "zNvQn4+W/XfTryQjr7RpXm1VxCIrCBvYWNU2KrSYV4XUtL+B5ERNj6In6AOrOAif" +
                "uVITy5cQQQeoD+AT4YKKMBkQfO2gnZzqb8+ox130e+3K/mufoqJPZeyrCQKBgC2f" +
                "objwhQvYwYY+DIUharri+rYrBRYTDbJYnh/PNOaw1CmHwXJt5PEDcml3+NlIMn58" +
                "I1X2U/hpDrAIl3MlxpZBkVYFI8LmlOeR7ereTddN59ZOE4jY/OnCfqA480Jf+FKf" +
                "oMHby5lPO5OOLaAfjtae1FhrmpUe3EfIx9wVuhKBAoGBAPFzHKQZbGhkqmyPW2ct" +
                "TEIWLdUHyO37fm8dj1WjN4wjRAI4ohNiKQJRh3QE11E1PzBTl9lZVWT8QtEsSjnr" +
                "A/tpGr378fcUT7WGBgTmBRaAnv1P1n/Tp0TSvh5XpIhhMuxcitIgrhYMIG3GbP9J" +
                "NAarxO/qPW6Gi0xWaF7il7Or"
        val decoded = Base64.decode(base64Key, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(decoded)
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val homePages = ArrayList<HomePageList>()

        try {
            // Trending
            val trendingRes = makeRequest("/drama-box/he001/theater", mapOf(
                "newChannelStyle" to 1,
                "isNeedRank" to 1,
                "pageNo" to 1,
                "index" to 0,
                "channelId" to 175
            ))
            val trendingList = parseDramaList(trendingRes)
            if (trendingList.isNotEmpty()) {
                homePages.add(HomePageList("Trending", trendingList))
            }

            // Terbaru
            val latestRes = makeRequest("/drama-box/he001/theater", mapOf(
                "newChannelStyle" to 1,
                "isNeedRank" to 1,
                "pageNo" to page,
                "index" to 1,
                "channelId" to 48
            ))
            val latestList = parseDramaList(latestRes)
            if (latestList.isNotEmpty()) {
                homePages.add(HomePageList("Terbaru", latestList))
            }

            // Rekomendasi
            val recRes = makeRequest("/drama-box/he001/recommendBook", mapOf(
                "isNeedRank" to 1,
                "newChannelStyle" to 1,
                "specialColumnId" to 0,
                "pageNo" to 1,
                "channelId" to 43
            ))
            val recList = parseDramaList(recRes)
            if (recList.isNotEmpty()) {
                homePages.add(HomePageList("Rekomendasi", recList))
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

    private fun parseDramaList(jsonStr: String): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        try {
            val json = JSONObject(jsonStr)
            val dataObj = json.optJSONObject("data") ?: return results
            
            val columnVoList = dataObj.optJSONArray("columnVoList")
            if (columnVoList != null) {
                for (i in 0 until columnVoList.length()) {
                    val col = columnVoList.optJSONObject(i) ?: continue
                    val bookList = col.optJSONArray("bookList") ?: continue
                    for (j in 0 until bookList.length()) {
                        val book = bookList.optJSONObject(j) ?: continue
                        results.add(mapBookToSearchResponse(book))
                    }
                }
            } else {
                val recommendListObj = dataObj.optJSONObject("recommendList")
                val records = recommendListObj?.optJSONArray("records") ?: dataObj.optJSONArray("records")
                if (records != null) {
                    for (i in 0 until records.length()) {
                        val book = records.optJSONObject(i) ?: continue
                        results.add(mapBookToSearchResponse(book))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results.distinctBy { it.url }
    }

    private fun mapBookToSearchResponse(book: JSONObject): SearchResponse {
        val bookId = book.optString("bookId")
        val bookName = book.optString("bookName")
        val cover = book.optString("coverWap").ifEmpty { book.optString("cover") }
        val introduction = book.optString("introduction")
        
        return newTvSeriesSearchResponse(
            name = bookName,
            url = "$mainUrl/play/$bookId",
            type = TvType.TvSeries
        ) {
            this.posterUrl = cover
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        try {
            val resStr = makeRequest("/drama-box/search/search", mapOf(
                "searchSource" to "搜索按钮",
                "pageNo" to 1,
                "pageSize" to 20,
                "from" to "search_sug",
                "keyword" to query
            ))
            val json = JSONObject(resStr)
            val dataObj = json.optJSONObject("data") ?: return results
            val searchList = dataObj.optJSONArray("searchList") ?: return results
            for (i in 0 until searchList.length()) {
                val book = searchList.optJSONObject(i) ?: continue
                results.add(mapBookToSearchResponse(book))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val bookId = url.substringAfter("/play/").substringBefore("?").substringBefore("/")
        if (bookId.isEmpty()) return null

        try {
            val detailRes = makeRequest("/drama-box/chapterv2/detail", mapOf(
                "needRecommend" to true,
                "from" to "book_album",
                "bookId" to bookId
            ))
            val detailJson = JSONObject(detailRes)
            val dataObj = detailJson.optJSONObject("data") ?: return null
            val bookObj = dataObj.optJSONObject("book") ?: return null
            
            val bookName = bookObj.optString("bookName")
            val cover = bookObj.optString("coverWap").ifEmpty { bookObj.optString("cover") }
            val introduction = bookObj.optString("introduction")
            
            val chaptersRes = makeRequest("/drama-box/chapterv2/batch/load", mapOf(
                "boundaryIndex" to 0,
                "comingPlaySectionId" to -1,
                "index" to 1,
                "currencyPlaySource" to "discover_new_rec_new",
                "needEndRecommend" to 0,
                "currencyPlaySourceName" to "",
                "preLoad" to false,
                "rid" to "",
                "pullCid" to "",
                "loadDirection" to 0,
                "bookId" to bookId
            ))
            
            val chapJson = JSONObject(chaptersRes)
            val chapDataObj = chapJson.optJSONObject("data") ?: return null
            val chapterList = chapDataObj.optJSONArray("chapterList") ?: JSONArray()
            
            val episodes = ArrayList<Episode>()
            for (i in 0 until chapterList.length()) {
                val ch = chapterList.optJSONObject(i) ?: continue
                val chapterIndex = ch.optInt("chapterIndex")
                val chapterName = ch.optString("chapterName")
                val coverUrl = ch.optString("cover")
                
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
                
                episodes.add(
                    newEpisode(
                        "$bookId|$chapterIndex|${videoPath ?: ""}"
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
                        "Referer" to mainUrl,
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
