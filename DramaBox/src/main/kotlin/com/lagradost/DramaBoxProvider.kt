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
import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import kotlin.math.min

class DramaBoxProvider : MainAPI() {
    companion object {
        private val detailCache = java.util.concurrent.ConcurrentHashMap<String, LoadResponse>()
        private val searchCache = java.util.concurrent.ConcurrentHashMap<String, List<SearchResponse>>()
        private val seenHomepageUrls = java.util.concurrent.ConcurrentSkipListSet<String>()
        private var mainPageCache: HomePageResponse? = null
        private var mainPageCacheTime: Long = 0
        private var mainPageDeferred: CompletableDeferred<HomePageResponse?>? = null

        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private val PASSWORD = com.lagradost.DramaBox.BuildConfig.SHORTMAX_KEY
        private var cfDeferred: CompletableDeferred<Boolean>? = null

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

    override var mainUrl = com.lagradost.DramaBox.BuildConfig.SHORTMAX_URL

    private fun decryptIfEncrypted(response: String): String {
        val trimmed = response.trim()
        if (trimmed.startsWith("{") && trimmed.contains("\"data\"")) {
            try {
                val json = JSONObject(trimmed)
                if (json.has("data")) {
                    val dataStr = json.getString("data")
                    if (dataStr == "{}" || dataStr.isEmpty()) return trimmed
                    return decryptCryptoJS(dataStr)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return trimmed
    }

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
                dialog.show(activity.supportFragmentManager, "cf_bypass_dramabox")
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

    private suspend fun validateOrSolveCf(activity: AppCompatActivity) {
        if (mainUrl.contains("nax1.cc")) return
        val ctx = activity
        val cookies = getCfCookies(ctx)
        val ua = getCfUserAgent(ctx)

        var needSolve = false
        if (cookies == null || !cookies.contains("cf_clearance") || ua == null) {
            needSolve = true
        } else {
            try {
                val headersMap = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to ua,
                    "Cookie" to cookies
                )
                val testRes = app.get(mainUrl, headers = headersMap, timeout = 10).text
                val trimmed = testRes.trim()
                if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
                    needSolve = true
                }
            } catch (e: Exception) {
                needSolve = true
            }
        }

        if (needSolve) {
            setCfCookies(ctx, null)
            setCfUserAgent(ctx, null)
            solveCloudflare(activity, mainUrl)
        }
    }

    private fun getLocalCache(key: String, durationMs: Long): String? {
        val ctx = CommonActivity.activity ?: return null
        val cacheTime = ctx.getKey<Long>("dramabox_cache_time_$key") ?: return null
        if (System.currentTimeMillis() - cacheTime > durationMs) {
            return null
        }
        return ctx.getKey<String>("dramabox_cache_$key")
    }

    private fun setLocalCache(key: String, data: String) {
        val ctx = CommonActivity.activity ?: return
        ctx.setKey("dramabox_cache_$key", data)
        ctx.setKey("dramabox_cache_time_$key", System.currentTimeMillis())
    }

    private suspend fun fetchWithFallback(
        relativePath: String,
        fallbackUrl: String,
        fallbackParams: Map<String, String> = emptyMap()
    ): String {
        val jsdelivrUrl = "https://cdn.jsdelivr.net/gh/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream@main/database/dramabox/$relativePath"
        try {
            println("DramaBox: Fetching from jsDelivr -> $jsdelivrUrl")
            val res = getWithRetry(jsdelivrUrl)
            val decrypted = decryptIfEncrypted(res).trim()
            if (decrypted.startsWith("{") || decrypted.startsWith("[")) {
                return res
            } else {
                throw Exception("jsDelivr payload is not a valid JSON")
            }
        } catch (e: Exception) {
            println("DramaBox: jsDelivr failed (${e.message}), fallback to direct API -> $fallbackUrl")
            return requestWithCf(fallbackUrl, if (fallbackParams.isEmpty()) null else fallbackParams)
        }
    }

    private suspend fun fetchPageData(
        cacheKey: String,
        relativePath: String,
        fallbackUrl: String,
        fallbackParams: Map<String, String> = emptyMap(),
        durationMs: Long = 30 * 60 * 1000L // 30 menit
    ): String {
        val cached = getLocalCache(cacheKey, durationMs)
        if (cached != null) {
            println("DramaBox: Using local cache for $cacheKey")
            return cached
        }
        val data = fetchWithFallback(relativePath, fallbackUrl, fallbackParams)
        if (data.isNotEmpty()) {
            setLocalCache(cacheKey, data)
        }
        return data
    }


    private suspend fun requestWithCf(url: String, params: Map<String, String>? = null): String {
        if (mainUrl.contains("nax1.cc")) {
            return if (params != null) {
                app.get(url, params = params).text
            } else {
                app.get(url).text
            }
        }
        val ctx = CommonActivity.activity
        val currentCookies = getCfCookies(ctx)
        val currentUA = getCfUserAgent(ctx)
        System.out.println("DramaBox request: $url | cfCookies: $currentCookies | cfUA: $currentUA")
        val headersMap = mutableMapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to (currentUA ?: USER_AGENT)
        )
        currentCookies?.let { headersMap["Cookie"] = it }

        val response = if (params != null) {
            app.get(url, params = params, headers = headersMap, timeout = 30).text
        } else {
            app.get(url, headers = headersMap, timeout = 30).text
        }
        System.out.println("DramaBox response: ${response.take(300)}")

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
                    app.get(url, params = params, headers = newHeadersMap, timeout = 30).text
                } else {
                    app.get(url, headers = newHeadersMap, timeout = 30).text
                }
                checkResponse(retryResponse)
                return retryResponse
            } else {
                throw e
            }
        }
    }

    override var name = "#Dracin DramaBox"
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
        if (page == 1) {
            val activity = (CommonActivity.activity as? AppCompatActivity)
            if (activity != null) {
                try {
                    validateOrSolveCf(activity)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        if (page > 1) {
            val homePages = ArrayList<HomePageList>()
            try {
                val forYouRes = fetchPageData(
                    "foryou_$page",
                    "foryou_$page.json",
                    "$mainUrl/api/dramabox/foryou",
                    mapOf("page" to page.toString())
                )
                val forYouList = parseSekaiDramaList(forYouRes, filterDuplicates = true)
                if (forYouList.isNotEmpty()) {
                    homePages.add(HomePageList("Lainnya", forYouList))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return if (homePages.isNotEmpty()) {
                newHomePageResponse(homePages, hasNext = page < 11)
            } else {
                null
            }
        }

        // Halaman Pertama (page == 1): Dedup & Cache
        val deferred = synchronized(Companion) {
            // Jika cache valid (kurang dari 15 detik), langsung kembalikan cache
            if (mainPageCache != null && System.currentTimeMillis() - mainPageCacheTime < 15000) {
                return mainPageCache
            }

            // Jika ada request halaman utama yang sedang berjalan, tunggu request tersebut
            mainPageDeferred?.let { return@synchronized it }

            val newDeferred = CompletableDeferred<HomePageResponse?>()
            mainPageDeferred = newDeferred

            // Jalankan request di IO Thread Pool secara asynchronous
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                try {
                    val response = fetchMainPageFromNetwork()
                    if (response != null) {
                        mainPageCache = response
                        mainPageCacheTime = System.currentTimeMillis()
                    }
                    newDeferred.complete(response)
                } catch (e: Exception) {
                    newDeferred.complete(null)
                } finally {
                    synchronized(Companion) {
                        mainPageDeferred = null
                    }
                }
            }
            newDeferred
        }
        return deferred.await()
    }

    private suspend fun fetchMainPageFromNetwork(): HomePageResponse? {
        val homePages = ArrayList<HomePageList>()
        seenHomepageUrls.clear()
        println("DramaBox: Fetching page 1 from network (deduplicated)")
        try {
            // 1. Pilihan VIP
            val vipRes = fetchPageData("vip", "vip.json", "$mainUrl/api/dramabox/vip")
            val vipList = parseVipDramaList(vipRes, filterDuplicates = false)
            if (vipList.isNotEmpty()) {
                homePages.add(HomePageList("Pilihan VIP", vipList))
            }

            // 2. Trending
            val trendingRes = fetchPageData("trending", "trending.json", "$mainUrl/api/dramabox/trending")
            val trendingList = parseSekaiDramaList(trendingRes, filterDuplicates = false)
            if (trendingList.isNotEmpty()) {
                homePages.add(HomePageList("Trending", trendingList))
            }

            // 3. Terbaru
            val latestRes = fetchPageData("latest", "latest.json", "$mainUrl/api/dramabox/latest")
            val latestList = parseSekaiDramaList(latestRes, filterDuplicates = false)
            if (latestList.isNotEmpty()) {
                homePages.add(HomePageList("Terbaru", latestList))
            }

            // 4. Pencarian Populer
            val popSearchRes = fetchPageData("populersearch", "populersearch.json", "$mainUrl/api/dramabox/populersearch")
            val popSearchList = parseSekaiDramaList(popSearchRes, filterDuplicates = false)
            if (popSearchList.isNotEmpty()) {
                homePages.add(HomePageList("Pencarian Populer", popSearchList))
            }

            // 5. Sulih Suara Populer
            val voicePopRes = fetchPageData(
                "dubindo_terpopuler",
                "dubindo_terpopuler.json",
                "$mainUrl/api/dramabox/dubindo",
                mapOf("classify" to "terpopuler")
            )
            val voicePopList = parseSekaiDramaList(voicePopRes, filterDuplicates = false)
            if (voicePopList.isNotEmpty()) {
                homePages.add(HomePageList("Sulih Suara Populer", voicePopList))
            }

            // 6. Sulih Suara Terbaru
            val voiceNewRes = fetchPageData(
                "dubindo_terbaru",
                "dubindo_terbaru.json",
                "$mainUrl/api/dramabox/dubindo",
                mapOf("classify" to "terbaru")
            )
            val voiceNewList = parseSekaiDramaList(voiceNewRes, filterDuplicates = false)
            if (voiceNewList.isNotEmpty()) {
                homePages.add(HomePageList("Sulih Suara Terbaru", voiceNewList))
            }

            // 7. Lainnya (Halaman 1)
            val forYouRes = fetchPageData(
                "foryou_1",
                "foryou_1.json",
                "$mainUrl/api/dramabox/foryou",
                mapOf("page" to "1")
            )
            val forYouList = parseSekaiDramaList(forYouRes, filterDuplicates = true)
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

    private fun parseSekaiDramaList(jsonStr: String, filterDuplicates: Boolean = false): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val book = jsonArray.optJSONObject(i) ?: continue
                val bookId = book.optString("bookId")
                val bookName = book.optString("bookName")
                val cover = book.optString("coverWap").ifEmpty { book.optString("cover") }
                val url = "$mainUrl/play/$bookId"
                
                if (filterDuplicates) {
                    if (seenHomepageUrls.contains(url)) {
                        continue
                    }
                    seenHomepageUrls.add(url)
                }

                results.add(
                    newTvSeriesSearchResponse(
                        name = bookName,
                        url = url,
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

    private fun parseVipDramaList(jsonStr: String, filterDuplicates: Boolean = false): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        try {
            val json = JSONObject(jsonStr)
            val columnVoList = json.optJSONArray("columnVoList")
            if (columnVoList != null) {
                for (i in 0 until columnVoList.length()) {
                    val col = columnVoList.optJSONObject(i) ?: continue
                    val bookList = col.optJSONArray("bookList") ?: continue
                    for (j in 0 until bookList.length()) {
                        val book = bookList.optJSONObject(j) ?: continue
                        val bookId = book.optString("bookId")
                        val bookName = book.optString("bookName")
                        val cover = book.optString("coverWap").ifEmpty { book.optString("cover") }
                        val url = "$mainUrl/play/$bookId"
                        
                        if (filterDuplicates) {
                            if (seenHomepageUrls.contains(url)) {
                                continue
                            }
                            seenHomepageUrls.add(url)
                        }

                        results.add(
                            newTvSeriesSearchResponse(
                                name = bookName,
                                url = url,
                                type = TvType.TvSeries
                            ) {
                                this.posterUrl = cover
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cached = searchCache[query]
        if (cached != null) return cached

        try {
            val rawRes = requestWithCf("$mainUrl/api/dramabox/search", mapOf("query" to query))
            val json = JSONObject(rawRes)
            val decrypted = decryptCryptoJS(json.getString("data"))
            if (decrypted.isNotEmpty()) {
                val results = parseSekaiDramaList(decrypted)
                if (results.isNotEmpty()) {
                    searchCache[query] = results
                    return results
                }
            }
        } catch (e: Exception) {
            println("DramaBox: Direct search failed: ${e.message}")
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

        val cached = detailCache[bookId]
        if (cached != null) return cached

        val cacheKeyDetail = "detail_$bookId"
        val cacheKeyEpisodes = "episodes_$bookId"
        val cacheDuration = 2 * 60 * 60 * 1000L // 2 jam

        var detailRes: String? = getLocalCache(cacheKeyDetail, cacheDuration)
        var episodesRes: String? = getLocalCache(cacheKeyEpisodes, cacheDuration)

        // Validasi apakah cache disk valid
        if (detailRes != null && episodesRes != null) {
            try {
                JSONObject(decryptIfEncrypted(detailRes))
                JSONArray(decryptIfEncrypted(episodesRes))
                println("DramaBox: Using local disk cache for detail/episodes of $bookId")
            } catch (e: Exception) {
                println("DramaBox: Cache is invalid, discarding cache for $bookId: ${e.message}")
                detailRes = null
                episodesRes = null
            }
        }

        try {
            if (detailRes == null || episodesRes == null) {
                val dRes = fetchWithFallback(
                    "detail/$bookId.json",
                    "$mainUrl/api/dramabox/detail/$bookId"
                )
                val eRes = fetchWithFallback(
                    "allepisode/$bookId.json",
                    "$mainUrl/api/dramabox/allepisode/$bookId"
                )
                detailRes = dRes
                episodesRes = eRes
                
                // Pastikan respon valid sebelum di-cache
                try {
                    JSONObject(decryptIfEncrypted(detailRes))
                    JSONArray(decryptIfEncrypted(episodesRes))
                    setLocalCache(cacheKeyDetail, detailRes)
                    setLocalCache(cacheKeyEpisodes, episodesRes)
                } catch (e: Exception) {
                    println("DramaBox: Fetch success but payload is invalid, not caching: ${e.message}")
                }
            }

            val detailJson = JSONObject(decryptIfEncrypted(detailRes))
            val bookName = detailJson.optString("bookName").ifEmpty { "DramaBox" }
            val cover = detailJson.optString("coverWap").ifEmpty { detailJson.optString("cover") }
            val introduction = detailJson.optString("introduction").ifEmpty { "Saksikan drama pendek menarik di DramaBox." }
            val chapterList = JSONArray(decryptIfEncrypted(episodesRes))

            val episodes = ArrayList<Episode>()
            var firstEpisodeCover: String? = null
            for (i in 0 until chapterList.length()) {
                val ch = chapterList.optJSONObject(i) ?: continue
                val chapterIndex = ch.optInt("chapterIndex")
                val chapterName = ch.optString("chapterName").ifEmpty { "EP ${chapterIndex + 1}" }
                val coverUrl = ch.optString("chapterImg").ifEmpty { ch.optString("chapterImgMap") }
                
                if (firstEpisodeCover == null && coverUrl.isNotEmpty()) {
                    firstEpisodeCover = coverUrl
                }

                episodes.add(
                    newEpisode(
                        "$bookId|$chapterIndex"
                    ) {
                        this.name = chapterName
                        this.episode = chapterIndex
                        this.season = 1
                        this.posterUrl = coverUrl
                    }
                )
            }

            val response = newTvSeriesLoadResponse(
                name = bookName,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = cover
                this.plot = introduction
                if (firstEpisodeCover != null) {
                    this.backgroundPosterUrl = firstEpisodeCover
                }
            }
            detailCache[bookId] = response
            return response
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
        println("DramaBox: loadLinks called with data = $data")
        val parts = data.split("|")
        if (parts.size < 2) {
            println("DramaBox: parts size too small: ${parts.size}")
            return false
        }
        var bookId = parts[0]
        if (bookId.startsWith("http://") || bookId.startsWith("https://")) {
            val parsedId = when {
                bookId.contains("/play/") -> bookId.substringAfter("/play/").substringBefore("?").substringBefore("/")
                bookId.contains("/detail/") -> bookId.substringAfter("/detail/").substringBefore("?").substringBefore("/")
                else -> bookId.substringAfterLast("/").substringBefore("?")
            }
            if (parsedId.isNotEmpty()) {
                bookId = parsedId
            }
        }
        val episodeIndex = parts[1].toIntOrNull() ?: 0
        val cachedVideoPath = parts.getOrNull(2)
        println("DramaBox: parsed bookId=$bookId, episodeIndex=$episodeIndex, cachedVideoPath=$cachedVideoPath")

        // 1. Cek masa kedaluwarsa tautan cache
        var useCached = false
        if (!cachedVideoPath.isNullOrEmpty() && (cachedVideoPath.startsWith("http://") || cachedVideoPath.startsWith("https://"))) {
            val expiresStr = cachedVideoPath.substringAfter("Expires=", "").substringBefore("&")
            val expires = expiresStr.toLongOrNull()
            val currentTime = System.currentTimeMillis() / 1000
            println("DramaBox: cached link expires=$expires, currentTime=$currentTime")
            if (expires == null || currentTime < expires) {
                useCached = true
            }
        }
        println("DramaBox: useCached=$useCached")

        // 2. Ambil URL video segar dan daftar subtitle dari API
        try {
            println("DramaBox: calling mainUrl for fresh data & subtitles, bookId=$bookId, episodeIndex=$episodeIndex")
            val episodesRes = requestWithCf("$mainUrl/api/dramabox/allepisode/$bookId")
            val chapterList = JSONArray(decryptIfEncrypted(episodesRes))
            
            // Temukan episode yang cocok berdasarkan index
            var targetCh: JSONObject? = null
            for (i in 0 until chapterList.length()) {
                val ch = chapterList.optJSONObject(i) ?: continue
                if (ch.optInt("chapterIndex") == episodeIndex) {
                    targetCh = ch
                    break
                }
            }
            if (targetCh == null && episodeIndex < chapterList.length()) {
                targetCh = chapterList.optJSONObject(episodeIndex)
            }

            if (targetCh != null) {
                // Ekstrak subtitle jika tersedia
                val subList = targetCh.optJSONArray("subLanguageVoList")
                if (subList != null) {
                    for (j in 0 until subList.length()) {
                        val subObj = subList.optJSONObject(j) ?: continue
                        val langCode = subObj.optString("captionLanguage")
                        val subUrl = subObj.optString("url")
                        if (subUrl.isNotEmpty() && langCode != "none") {
                            val cleanSubUrl = if (subUrl.startsWith("https://")) {
                                "https://" + subUrl.substring(8).replace("//", "/")
                            } else if (subUrl.startsWith("http://")) {
                                "http://" + subUrl.substring(7).replace("//", "/")
                            } else {
                                subUrl
                            }
                            val langName = when (langCode) {
                                "in" -> "Indonesian"
                                "en" -> "English"
                                "de" -> "German"
                                "pt" -> "Portuguese"
                                "ko" -> "Korean"
                                "it" -> "Italian"
                                "fr" -> "French"
                                "es" -> "Spanish"
                                "zh" -> "Chinese"
                                "ar" -> "Arabic"
                                "vi" -> "Vietnamese"
                                "th" -> "Thai"
                                "ja" -> "Japanese"
                                "pl" -> "Polish"
                                else -> langCode.uppercase()
                            }
                            subtitleCallback.invoke(
                                newSubtitleFile(langName, cleanSubUrl)
                            )
                        }
                    }
                }

                // Siapkan header pemutar video (Cookie + User-Agent)
                val currentUA = getCfUserAgent(CommonActivity.activity) ?: USER_AGENT
                val currentCookies = getCfCookies(CommonActivity.activity)
                val headersMap = mutableMapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to currentUA
                )
                currentCookies?.let { headersMap["Cookie"] = it }

                // Putar video
                if (useCached && !cachedVideoPath.isNullOrEmpty()) {
                    val isM3u8 = cachedVideoPath.contains(".m3u8", ignoreCase = true)
                    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    println("DramaBox: playing from cached URL: $cachedVideoPath")
                    callback.invoke(
                        newExtractorLink(
                            name = "$name Resmi",
                            source = name,
                            url = cachedVideoPath,
                            type = linkType
                        ) {
                            this.quality = Qualities.P720.value
                            this.headers = headersMap
                        }
                    )
                    return true
                }

                var videoPath: String? = null
                val cdnList = targetCh.optJSONArray("cdnList")
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
                println("DramaBox: parsed videoPath = $finalVideoPath")
                if (finalVideoPath.isNotEmpty()) {
                    if (finalVideoPath.contains(".encrypt.mp4") || finalVideoPath.contains("etavirp_nuyila")) {
                        finalVideoPath = "$mainUrl/api/dramabox/decrypt-stream?url=" + java.net.URLEncoder.encode(finalVideoPath, "UTF-8")
                        println("DramaBox: encrypted video, set decrypt URL = $finalVideoPath")
                    }
                    val isM3u8 = finalVideoPath.contains(".m3u8", ignoreCase = true)
                    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            name = "$name Resmi",
                            source = name,
                            url = finalVideoPath,
                            type = linkType
                        ) {
                            this.quality = Qualities.P720.value
                            this.headers = headersMap
                        }
                    )
                    println("DramaBox: successfully extracted link")
                    return true
                } else {
                    println("DramaBox: finalVideoPath is empty!")
                }
            } else {
                println("DramaBox: targetCh is null!")
            }
        } catch (e: Exception) {
            println("DramaBox: Exception in loadLinks step 2: ${e.message}")
            e.printStackTrace()
        }
        return false
    }
}
