package com.lagradost

import android.content.Context
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.json.JSONArray
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.OkHttpClient
import okhttp3.Headers

class MeloloProvider : MainAPI() {
    override var mainUrl = "https://api.tmtreader.com"
    override var name = "Melolo"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("Trending Drama", "trending"),
        MainPageData("Romansa", "2"),
        MainPageData("Pemeran Utama Pria", "19"),
        MainPageData("CEO", "754")
    )

    private val commonHeaders = mapOf(
        "User-Agent" to "com.worldance.drama/53418 (Linux; U; Android 12; en_US; MuMu; Build/SQ3A.220705.004; Cronet/TTNet.android.3.1.0.8)"
    )

    private val commonParams = "aid=645713&device_platform=android&device_id=7609231910774638088&iid=7659648719923726133&openudid=50a82024529633c7&sys_region=US&locale=en-US&language=en"

    private val staticBookDetails = mapOf(
        "7657435255623650357" to Pair("Beneath the Shadow of Power", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/841fedf33866ac3db3285b9deecf9946~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=hZu8xmDCffbDVzn5vn1hvn4oifY%3D"),
        "7655897426561076277" to Pair("My Husband, the CEO", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/bf6fc22d25ffde650ea33f26048c291d~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=Vcl5LxUulYv5Qt8i6Zi7UZCbOl8%3D"),
        "7657852492641733685" to Pair("The Lost Heir", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/2828db77db0af53e2f0243f45278e82b~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110789&x-signature=9y52Bbmp9G9ryPyiwOtZ3YjaoXk%3D"),
        "7655263162391874565" to Pair("The Hidden Billionaire", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/6bb82880f3ce3dfc8272cc54b19199f5~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=5Rh4%2BIOIxhriJ9I0aOKa6EoO22E%3D"),
        "7656644146102291461" to Pair("Secret Marriage", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/51a3cd2f533a809b2eb99e17c4667cc8~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=zgcgzM%2BLRb5QF7AhtvQd05dwcz0%3D"),
        "7655202378265660469" to Pair("Unveiled Queen", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/82fb63c6bc1609a8d8105e8ddbd65da2~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=EtuuqEtuqzU%2BYzXnVgUDXWnvSoM%3D"),
        "7656306115868625973" to Pair("Fated Love", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/52258626f249f4735a78864623173547~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=q2Re7PO9Th5LoVLpxPJqsWR7OpY%3D"),
        "7656352297785510917" to Pair("The Contract Bride", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/e473efdf82b55ddaf3ab57c296af01a8~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=RdhCKN6HzHLle13dtE8GGa%2Bh64k%3D"),
        "7645534390017084421" to Pair("Cold CEO's Warmer Heart", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/7dfabb33eec685c2e90328bd189cc7a5~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110789&x-signature=HCsNwW%2Fs%2FJ8HSCDPyMA5KaDDU4k%3D"),
        "7657361523391597573" to Pair("CEO's Runaway Wife", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/a07ce0a8910921efe5335dbe32012493~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110789&x-signature=ZoMrDXGozRP6Oa2q7QmbKJScQMo%3D"),
        "7595011717575232517" to Pair("Sweet Revenge", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/36fc89ca9b5fd2bf6e4944873f28e131~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=bhKb7U%2BD9WlcbFAGF08zo%2F4w0NU%3D"),
        "7639315034102828085" to Pair("Silent Vows", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/5f6660d18479f6f3ebfbe067b32f095b~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=H7sC5CPpXZtTHYnWhvUFRkZDLlQ%3D"),
        "7655739700639960117" to Pair("The Heiress", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/73fae03f860c7379e07575268790c071~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=UKNKBJiC%2BD9LHokD66JSRtJfAls%3D"),
        "7636721427118312453" to Pair("Beneath the Shadow of Power II", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/841fedf33866ac3db3285b9deecf9946~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=hZu8xmDCffbDVzn5vn1hvn4oifY%3D"),
        "7656363888757197829" to Pair("Zombie Slayer Ascends", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/18675e97947492e888868a929b3a41fb~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110789&x-signature=k0tVNkz84WsslEpNAm3s%2B%2BPGwe8%3D"),
        "7631383376364063749" to Pair("Love After Marriage", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/cdb5841e5fd3f31467c931e07a30536c~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=komYPfly%2F6TnzeZMfnHLMfjdsbg%3D"),
        "7623452418121927733" to Pair("Broken Marriage", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/bf6fc22d25ffde650ea33f26048c291d~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=Vcl5LxUulYv5Qt8i6Zi7UZCbOl8%3D"),
        "7633336990653484037" to Pair("Fated Partner", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/2828db77db0af53e2f0243f45278e82b~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110789&x-signature=9y52Bbmp9G9ryPyiwOtZ3YjaoXk%3D"),
        "7651926919163694085" to Pair("Justice by the Pond", "https://p19-novel-sign.fizzopic.org/novel-images-apsoutheast/cdb5841e5fd3f31467c931e07a30536c~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=komYPfly%2F6TnzeZMfnHLMfjdsbg%3D"),
        "7651790081413352453" to Pair("Sweet but Betrayed", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/2828db77db0af53e2f0243f45278e82b~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110789&x-signature=9y52Bbmp9G9ryPyiwOtZ3YjaoXk%3D"),
        "7630016555664804869" to Pair("Boss Wife Rules", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/6bb82880f3ce3dfc8272cc54b19199f5~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=5Rh4%2BIOIxhriJ9I0aOKa6EoO22E%3D"),
        "7652544402623056901" to Pair("Eye for Eye Marriage War", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/51a3cd2f533a809b2eb99e17c4667cc8~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=zgcgzM%2BLRb5QF7AhtvQd05dwcz0%3D"),
        "7638886447419771909" to Pair("The Little Lucky Star", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/82fb63c6bc1609a8d8105e8ddbd65da2~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=EtuuqEtuqzU%2BYzXnVgUDXWnvSoM%3D"),
        "7650377807121353781" to Pair("Farm Girl Ascends", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/52258626f249f4735a78864623173547~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=q2Re7PO9Th5LoVLpxPJqsWR7OpY%3D"),
        "7656726745898290229" to Pair("City of Infinite Return", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/e473efdf82b55ddaf3ab57c296af01a8~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=RdhCKN6HzHLle13dtE8GGa%2Bh64k%3D"),
        "7631829392875850805" to Pair("Grace Strikes Back", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/7dfabb33eec685c2e90328bd189cc7a5~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110789&x-signature=HCsNwW%2Fs%2FJ8HSCDPyMA5KaDDU4k%3D"),
        "7656457800142294069" to Pair("Rise of the Sea King", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/a07ce0a8910921efe5335dbe32012493~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110789&x-signature=ZoMrDXGozRP6Oa2q7QmbKJScQMo%3D"),
        "7635230819098823685" to Pair("Crab King", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/36fc89ca9b5fd2bf6e4944873f28e131~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=bhKb7U%2BD9WlcbFAGF08zo%2F4w0NU%3D"),
        "7623645268684049461" to Pair("Collateral Damage", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/5f6660d18479f6f3ebfbe067b32f095b~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=H7sC5CPpXZtTHYnWhvUFRkZDLlQ%3D"),
        "7657182335237884981" to Pair("Jungle Heiress Contract Marriage", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/73fae03f860c7379e07575268790c071~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=UKNKBJiC%2BD9LHokD66JSRtJfAls%3D"),
        "7651830603154738181" to Pair("Priced Daughter", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/12c2e3df0897bf32a1befcc7c3fcc86d~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=Eht203%2BiTHFVPrusmgoA%2BfflMqk%3D"),
        "7631829386907356165" to Pair("Village of Hidden Pacts", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/fb6039cf4ca950ef3ee69581e63c8168~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=sJBIPmeEE6aMSBjS3h3dQtXSrUU%3D"),
        "7592111729832643589" to Pair("The Door to Tomorrow", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/88c91eaf771ee7eec4f69d41867feeed~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110788&x-signature=j6zkmI3%2B01z0PsPbLgSklBoOdd4%3D"),
        "7656735244900371461" to Pair("Zombie Slayer Ascends II", "https://p16-novel-sign.fizzopic.org/novel-images-apsoutheast/18675e97947492e888868a929b3a41fb~tplv-836v1mcgsk-image-quality-ttk1-cp:336:478.heic?rk3s=95ec04ee&x-expires=1785110789&x-signature=k0tVNkz84WsslEpNAm3s%2B%2BPGwe8%3D")
    )

    private var customClient: OkHttpClient? = null
    private var lastProxyStr = ""

    private fun getClient(proxyStr: String): OkHttpClient {
        if (customClient != null && lastProxyStr == proxyStr) {
            return customClient!!
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)

        if (proxyStr.isNotEmpty()) {
            try {
                val proxyType = if (proxyStr.startsWith("socks://", ignoreCase = true)) Proxy.Type.SOCKS else Proxy.Type.HTTP
                val cleanProxy = proxyStr.replace("socks://", "", ignoreCase = true).replace("http://", "", ignoreCase = true)
                val parts = cleanProxy.split(":")
                val host = parts[0]
                val port = parts[1].toInt()
                val proxy = Proxy(proxyType, java.net.InetSocketAddress.createUnresolved(host, port))
                builder.proxy(proxy)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        lastProxyStr = proxyStr
        customClient = builder.build()
        return customClient!!
    }

    private suspend fun getApiData(url: String, forceProxy: Boolean = true): String {
        val context = MeloloProviderPlugin.currentContext
        val proxyStr = context?.let { MeloloProviderPlugin.getSavedProxy(it) } ?: ""
        android.util.Log.d("Melolo", "getApiData: url=$url, context=$context, proxyStr=$proxyStr")

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = getClient(proxyStr)
                val headersBuilder = okhttp3.Headers.Builder()
                commonHeaders.forEach { (k, v) -> headersBuilder.add(k, v) }
                
                if (proxyStr.isNotEmpty()) {
                    try {
                        val req = okhttp3.Request.Builder()
                            .url(url)
                            .headers(headersBuilder.build())
                            .build()
                        client.newCall(req).execute().use { response ->
                            if (response.code == 200) {
                                val text = response.body?.string() ?: ""
                                if (!text.contains("FILTERED_ERROR") && !text.contains("REMOVE_ERROR")) {
                                    return@withContext text
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                var directText = ""
                var directSuccess = false
                try {
                    val req = okhttp3.Request.Builder()
                        .url(url)
                        .headers(headersBuilder.build())
                        .build()
                    client.newCall(req).execute().use { response ->
                        if (response.code == 200) {
                            val text = response.body?.string() ?: ""
                            if (!text.contains("FILTERED_ERROR") && !text.contains("REMOVE_ERROR")) {
                                directText = text
                                directSuccess = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (directSuccess) {
                    return@withContext directText
                }

                val proxies = listOf(
                    "https://api.allorigins.win/raw?url=",
                    "https://api.codetabs.com/v1/proxy?quest="
                )
                
                for (proxyPrefix in proxies) {
                    try {
                        val finalUrl = proxyPrefix + URLEncoder.encode(url, "UTF-8")
                        val req = okhttp3.Request.Builder()
                            .url(finalUrl)
                            .headers(headersBuilder.build())
                            .build()
                        client.newCall(req).execute().use { response ->
                            if (response.code == 200) {
                                val text = response.body?.string() ?: ""
                                if (text.contains("\"code\":") && !text.contains("FILTERED_ERROR") && !text.contains("REMOVE_ERROR")) {
                                    return@withContext text
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                ""
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val listItems = ArrayList<SearchResponse>()
        try {
            if (request.data == "trending") {
                val bookIds = ArrayList<String>()
                val tabUrl = "$mainUrl/i18n_novel/bookmall/tab/v1/?$commonParams"
                val resText = getApiData(tabUrl, forceProxy = true)
                val root = JSONObject(resText)
                val dataObj = root.optJSONObject("data")
                val tabInfos = dataObj?.optJSONArray("book_tab_infos")
                if (tabInfos != null && tabInfos.length() > 0) {
                    val tab0 = tabInfos.getJSONObject(0)
                    val cells = tab0.optJSONArray("cells")
                    if (cells != null) {
                        for (i in 0 until cells.length()) {
                            val cell = cells.getJSONObject(i)
                            val cellUrl = cell.optString("url", "")
                            if (cellUrl.contains("stick_ids=")) {
                                val idsStr = cellUrl.substringAfter("stick_ids=").substringBefore("&")
                                val decodedIds = URLDecoder.decode(idsStr, "UTF-8")
                                decodedIds.split(",").forEach { id ->
                                    val cleanId = id.trim()
                                    if (cleanId.isNotEmpty() && !bookIds.contains(cleanId)) {
                                        bookIds.add(cleanId)
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (bookIds.isEmpty()) {
                    val staticIds = listOf(
                        "7657435255623650357", "7655897426561076277", "7657852492641733685",
                        "7655263162391874565", "7656644146102291461", "7655202378265660469",
                        "7656306115868625973", "7656352297785510917", "7645534390017084421",
                        "7657361523391597573", "7595011717575232517", "7639315034102828085",
                        "7655739700639960117", "7636721427118312453", "7656363888757197829",
                        "7631383376364063749", "7623452418121927733", "7633336990653484037"
                    )
                    bookIds.addAll(staticIds)
                }

                bookIds.take(15).forEach { bookId ->
                    val staticInfo = staticBookDetails[bookId]
                    if (staticInfo != null) {
                        val name = staticInfo.first
                        val cover = staticInfo.second
                        listItems.add(
                            newTvSeriesSearchResponse(name, "$mainUrl/book/$bookId", TvType.TvSeries) {
                                this.posterUrl = cover
                            }
                        )
                    }
                }
            } else {
                val catId = request.data
                val searchUrl = "$mainUrl/i18n_novel/search/page/v1/?$commonParams&category_id=$catId"
                val resText = getApiData(searchUrl, forceProxy = true)
                val root = JSONObject(resText)
                val dataObj = root.optJSONObject("data")
                val searchData = dataObj?.optJSONArray("search_data")
                if (searchData != null) {
                    for (i in 0 until searchData.length()) {
                        val item = searchData.getJSONObject(i)
                        val book = item.optJSONObject("search_book")
                        val bookId = book?.optString("book_id")
                        val name = book?.optString("book_name")
                        val cover = book?.optString("cover_url")
                        if (!bookId.isNullOrEmpty() && !name.isNullOrEmpty()) {
                            listItems.add(
                                newTvSeriesSearchResponse(name, "$mainUrl/book/$bookId", TvType.TvSeries) {
                                    this.posterUrl = cover
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (listItems.isEmpty()) return null
        return newHomePageResponse(
            list = listOf(HomePageList(request.name, listItems)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        try {
            val searchUrl = "$mainUrl/i18n_novel/search/page/v1/?$commonParams&query=${URLEncoder.encode(query, "UTF-8")}"
            val resText = getApiData(searchUrl, forceProxy = true)
            val root = JSONObject(resText)
            val dataObj = root.optJSONObject("data")
            val searchData = dataObj?.optJSONArray("search_data")
            if (searchData != null) {
                for (i in 0 until searchData.length()) {
                    val item = searchData.getJSONObject(i)
                    val book = item.optJSONObject("search_book") ?: continue
                    val bookId = book.optString("book_id", "")
                    val name = book.optString("book_name", "")
                    val cover = book.optString("cover_url", "")
                    if (bookId.isNotEmpty() && name.isNotEmpty()) {
                        results.add(
                            newTvSeriesSearchResponse(name, "$mainUrl/book/$bookId", TvType.TvSeries) {
                                this.posterUrl = cover
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val bookId = url.substringAfter("/book/").trim()
        if (bookId.isEmpty()) return null

        try {
            val staticInfo = staticBookDetails[bookId]
            val name: String
            val cover: String
            val plotText: String

            if (staticInfo != null) {
                name = staticInfo.first
                cover = staticInfo.second
                plotText = "No description available."
            } else {
                val detailUrl = "$mainUrl/i18n_novel/book/books/detail/?$commonParams&book_ids=$bookId"
                val detailRes = getApiData(detailUrl, forceProxy = true)
                val detailRoot = JSONObject(detailRes)
                val dataArr = detailRoot.optJSONArray("data") ?: return null
                if (dataArr.length() == 0) return null
                val book = dataArr.getJSONObject(0)
                name = book.optString("book_name")
                cover = book.optString("cover_url")
                plotText = book.optString("desc")
            }

            val episodesList = ArrayList<Episode>()
            try {
                val directoryUrl = "$mainUrl/i18n_novel/book/directory/info/v1/?$commonParams&book_id=$bookId"
                val directoryRes = getApiData(directoryUrl, forceProxy = true)
                val directoryRoot = JSONObject(directoryRes)
                val dirData = directoryRoot.optJSONObject("data")
                val items = dirData?.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val itemId = item.optString("item_id")
                        val title = item.optString("title")
                        val index = item.optInt("index", i + 1)
                        if (itemId.isNotEmpty()) {
                            episodesList.add(
                                newEpisode("$mainUrl/play/$bookId/$itemId") {
                                    this.name = title
                                    this.episode = index
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (episodesList.isEmpty()) {
                episodesList.add(
                    newEpisode("$mainUrl/play/$bookId/dummy_item_id") {
                        this.name = "Episode 1"
                        this.episode = 1
                    }
                )
            }

            return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodesList) {
                this.posterUrl = cover
                this.plot = plotText
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
        try {
            val bookId = data.substringAfter("/play/").substringBefore("/")
            val itemId = data.substringAfterLast("/")
            if (bookId.isEmpty() || itemId.isEmpty()) return false

            val playUrl = "$mainUrl/i18n_novel/reader/items/full/v1/?$commonParams&book_id=$bookId&item_ids=$itemId"
            val resText = getApiData(playUrl, forceProxy = true)
            val root = JSONObject(resText)
            val dataObj = root.optJSONObject("data")
            val contents = dataObj?.optJSONObject("item_contents")
            val item = contents?.optJSONObject(itemId)
            val info = item?.optJSONObject("item_info") ?: return false

            // Ambil dari mBashString jika ada, atau list video alternatif
            val bashString = info.optString("mBashString", "")
            val videoListStr = info.optString("mVideoList", "")

            var foundVideoUrl = ""
            if (bashString.isNotEmpty()) {
                val decodedBash = String(android.util.Base64.decode(bashString, android.util.Base64.DEFAULT))
                val bashObj = JSONObject(decodedBash)
                val mainUrlObj = bashObj.optJSONObject("main_url")
                val mainUrlVal = mainUrlObj?.optString("main_url", "")
                if (!mainUrlVal.isNullOrEmpty()) {
                    foundVideoUrl = mainUrlVal
                }
            }

            if (foundVideoUrl.isEmpty() && videoListStr.isNotEmpty()) {
                val videoList = JSONArray(videoListStr)
                if (videoList.length() > 0) {
                    val firstVid = videoList.getJSONObject(0)
                    val mainUrlVal = firstVid.optString("main_url", "")
                    if (mainUrlVal.isNotEmpty()) {
                        foundVideoUrl = mainUrlVal
                    }
                }
            }

            if (foundVideoUrl.isNotEmpty()) {
                val cleanUrl = if (foundVideoUrl.startsWith("http://")) {
                    foundVideoUrl.replaceFirst("http://", "https://")
                } else {
                    foundVideoUrl
                }

                callback.invoke(
                    newExtractorLink(
                        name = "Melolo Standard Stream",
                        source = "Melolo",
                        url = cleanUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P360.value
                        this.headers = commonHeaders
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
