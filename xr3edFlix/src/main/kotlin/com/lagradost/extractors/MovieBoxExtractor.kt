package com.lagradost.extractors

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MovieBoxExtractor {
    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private const val SECRET_KEY_DEFAULT_B64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
    private val MOVIEBOX_BEARER_TOKEN: String = try {
        String(
            Base64.decode(
                "ZXlKaGJHY2lPaUpJVXpJMU5pSXNJblI1Y0NJNklrcFhWQ0o5LmV5SjFhV1FpT2pnMk16TXpNamd6TVRRMk5EazRNamN4TlRJc0ltVjRjQ0k2TVRjNU1qZzVORGc0TlN3aWFXRjBJam94TnpnMU1URTROVGcxZlEuT2FENTlld0ZqZ0lUUkVGczFWSmFoWmRoRTJWTHhxUVk1WjNPT29HUktqVQ==",
                Base64.DEFAULT
            ),
            Charsets.UTF_8
        )
    } catch (_: Exception) { "" }

    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }
    }

    private fun generateXClientToken(timestamp: Long): String {
        val tsStr = timestamp.toString()
        val reversed = tsStr.reversed()
        val hash = md5(reversed.toByteArray())
        return "$tsStr,$hash"
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val parsed = Uri.parse(url)
        val path = parsed.path ?: ""
        val query = if (parsed.queryParameterNames.isNotEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key ->
                parsed.getQueryParameters(key).joinToString("&") { "$key=$it" }
            }
        } else ""
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            md5(if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes)
        } else ""
        val bodyLength = bodyBytes?.size?.toString() ?: ""
        val canonical = "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"

        val decodedOnce = Base64.decode(SECRET_KEY_DEFAULT_B64, Base64.DEFAULT)
        val decodedOnceStr = String(decodedOnce, Charsets.UTF_8)
        val secretBytes = Base64.decode(decodedOnceStr, Base64.DEFAULT)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = Base64.encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$timestamp|2|$signature"
    }

    private fun getHeaders(url: String, body: String? = null, method: String = "GET"): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val xClientToken = generateXClientToken(timestamp)
        val contentType = if (method == "POST") "application/json; charset=utf-8" else "application/json"
        val xTrSignature = generateXTrSignature(method, "application/json", contentType, url, body, timestamp)
        return mapOf(
            "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Subsystem for Android(TM); Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
            "accept" to "application/json",
            "content-type" to contentType,
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","install_ch":"ps","device_id":"da2b99c821e6ea023e4be55b54d5f7d8","install_store":"ps","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Windows","model":"Subsystem for Android(TM)","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}""",
            "x-client-status" to "0",
            "Authorization" to "Bearer $MOVIEBOX_BEARER_TOKEN",
            "x-user" to MOVIEBOX_BEARER_TOKEN
        )
    }

    private fun isTitleMatch(name: String, title: String): Boolean {
        val cleanName = name.lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
        val cleanTitle = title.lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
        if (cleanName.isEmpty() || cleanTitle.isEmpty()) return false

        if (cleanName == cleanTitle) return true
        if (cleanName == "${cleanTitle}original") return true

        // Exclude unwanted language suffixes if query doesn't specify them
        val nonOriginalDubs = listOf("tamil", "hindi", "telugu", "malayalam", "kannada")
        if (nonOriginalDubs.any { name.contains("[$it]", ignoreCase = true) } && !nonOriginalDubs.any { title.contains(it, ignoreCase = true) }) {
            return false
        }

        val titleBefore = title.substringBefore(":").substringBefore("-").trim().lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
        val nameBefore = name.substringBefore(":").substringBefore("-").trim().lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
        val titleAfter = title.substringAfter(":", "").substringAfter("-", "").trim().lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
        val nameAfter = name.substringAfter(":", "").substringAfter("-", "").trim().lowercase().replace(Regex("[^a-zA-Z0-9]"), "")

        if (titleAfter.isNotEmpty() && nameAfter.isNotEmpty()) {
            return titleBefore == nameBefore && (nameAfter.contains(titleAfter) || titleAfter.contains(nameAfter))
        }

        return false
    }

    suspend fun invoke(
        title: String?,
        season: Int? = 0,
        episode: Int? = 0,
        subCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            if (title.isNullOrBlank()) return false

            val searchUrl = "https://api.inmoviebox.com/wefeed-mobile-bff/subject-api/search/v2"
            val jsonBody = """{"page":1,"perPage":10,"keyword":"$title"}"""
            val searchHeaders = getHeaders(searchUrl, jsonBody, "POST")

            val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            val response = app.post(searchUrl, headers = searchHeaders, requestBody = requestBody)
            if (response.code != 200) return false

            val root = mapper.readTree(response.text)
            val results = root["data"]?.get("results") ?: return false

            var bestSubjectId: String? = null
            for (result in results) {
                val subjects = result["subjects"] ?: continue
                for (subject in subjects) {
                    val name = subject["title"]?.asText() ?: continue
                    val subjectId = subject["subjectId"]?.asText() ?: continue
                    val type = subject["subjectType"]?.asInt() ?: 0
                    if (isTitleMatch(name, title) && (type == 1 || type == 2)) {
                        bestSubjectId = subjectId
                        break
                    }
                }
                if (bestSubjectId != null) break
            }

            if (bestSubjectId == null) return false

            var foundLinks = false
            val targetSeason = season ?: 0
            val targetEpisode = episode ?: 0

            val detailUrl = "https://api.inmoviebox.com/wefeed-mobile-bff/subject-api/get?subjectId=$bestSubjectId"
            val detailHeaders = getHeaders(detailUrl, null, "GET")
            val detailRes = try {
                app.get(detailUrl, headers = detailHeaders).text
            } catch (_: Exception) { "" }

            val subjectList = mutableListOf<Pair<String, String>>()
            subjectList.add(bestSubjectId to "Original Audio")
            val addedSubjectIds = mutableSetOf(bestSubjectId)

            if (detailRes.isNotEmpty()) {
                try {
                    val rootDetail = mapper.readTree(detailRes)
                    val dubs = rootDetail["data"]?.get("dubs")
                    if (dubs != null && dubs.isArray) {
                        for (dub in dubs) {
                            val dubId = dub["subjectId"]?.asText()
                            val dubName = dub["lanName"]?.asText() ?: "Dub"
                            if (!dubId.isNullOrEmpty() && addedSubjectIds.add(dubId)) {
                                subjectList.add(dubId to dubName)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MovieBoxExtractor", "Error parsing dubs", e)
                }
            }

            for ((currentSubjectId, languageName) in subjectList) {
                val allowedLangs = listOf("original", "indonesia")
                val isAllowed = allowedLangs.any { languageName.contains(it, ignoreCase = true) }
                if (!isAllowed) continue

                var playInfoStreamsFound = false
                val playHosts = listOf("https://api4.aoneroom.com", "https://api.inmoviebox.com")

                for (host in playHosts) {
                    try {
                        val playUrl = "$host/wefeed-mobile-bff/subject-api/play-info?subjectId=$currentSubjectId&se=$targetSeason&ep=$targetEpisode"
                        val headersPlay = getHeaders(playUrl, null, "GET")
                        val playRes = app.get(playUrl, headers = headersPlay).text
                        val playRoot = mapper.readTree(playRes)

                        if (playRoot["code"]?.asInt() == 0) {
                            val streams = playRoot["data"]?.get("streams")
                            if (streams != null && streams.isArray && streams.size() > 0) {
                                for (stream in streams) {
                                    val streamUrl = stream["url"]?.asText() ?: continue
                                    if (streamUrl.isBlank()) continue
                                    val resolutions = stream["resolutions"]?.asText() ?: stream["resolution"]?.asText() ?: ""
                                    val quality = when {
                                        resolutions.contains("2160") || resolutions.contains("4k", true) -> Qualities.P2160.value
                                        resolutions.contains("1440") || resolutions.contains("2k", true) -> Qualities.P1440.value
                                        resolutions.contains("1080") -> Qualities.P1080.value
                                        resolutions.contains("720") -> Qualities.P720.value
                                        resolutions.contains("480") -> Qualities.P480.value
                                        resolutions.contains("360") -> Qualities.P360.value
                                        else -> Qualities.Unknown.value
                                    }

                                    val signCookie = stream["signCookie"]?.asText()
                                    val streamHeaders = mutableMapOf(
                                        "User-Agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Subsystem for Android(TM); Build/TQ3A.230901.001; Cronet/145.0.7582.0)"
                                    )
                                    if (!signCookie.isNullOrEmpty()) {
                                        streamHeaders["Cookie"] = signCookie
                                    }

                                    val sourceName = "MovieBox"
                                    val displayName = "MovieBox ($languageName)".trim()
                                    val linkType = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE

                                    callback.invoke(
                                        newExtractorLink(
                                            source = sourceName,
                                            name = displayName,
                                            url = streamUrl,
                                            type = linkType
                                        ) {
                                            this.quality = quality
                                            this.headers = streamHeaders
                                        }
                                    )
                                    foundLinks = true
                                    playInfoStreamsFound = true

                                    val streamId = stream["id"]?.asText()
                                    if (!streamId.isNullOrEmpty()) {
                                        try {
                                            val subUrlInternal = "$host/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$currentSubjectId&streamId=$streamId"
                                            val headersSubInternal = getHeaders(subUrlInternal, null, "GET")
                                            val subInternalRes = app.get(subUrlInternal, headers = headersSubInternal).text
                                            val subInternalRoot = mapper.readTree(subInternalRes)
                                            subInternalRoot["data"]?.get("extCaptions")?.forEach { cap ->
                                                val lang = cap["language"]?.asText() ?: cap["lanName"]?.asText() ?: cap["lan"]?.asText() ?: "Unknown"
                                                val capUrl = cap["url"]?.asText() ?: return@forEach
                                                subCallback.invoke(newSubtitleFile(lang = lang, url = capUrl))
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MovieBoxExtractor", "Error on play-info from $host", e)
                    }
                    if (playInfoStreamsFound) break
                }

                // Fallback ke H5 download
                if (!playInfoStreamsFound) {
                    try {
                        val downloadUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/download" +
                                "?subjectId=${URLEncoder.encode(currentSubjectId, "UTF-8")}" +
                                "&se=$targetSeason&ep=$targetEpisode&detailPath="

                        val downloadHeaders = mapOf(
                            "accept" to "*/*",
                            "accept-language" to "en-US,en;q=0.5",
                            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
                            "origin" to "https://videodownloader.site",
                            "referer" to "https://videodownloader.site/"
                        )

                        val dlRes = app.get(downloadUrl, headers = downloadHeaders)
                        if (dlRes.code == 200) {
                            val dlRoot = mapper.readTree(dlRes.text)
                            if (dlRoot["code"]?.asInt() == 0) {
                                val dlData = dlRoot["data"]
                                val downloads = dlData?.get("downloads")
                                val captions = dlData?.get("captions")

                                if (downloads != null && downloads.isArray) {
                                    for (download in downloads) {
                                        val streamUrl = download["url"]?.asText() ?: continue
                                        if (streamUrl.isBlank()) continue
                                        val resolution = download["resolution"]?.asInt()
                                        val quality = when (resolution) {
                                            2160 -> Qualities.P2160.value
                                            1440 -> Qualities.P1440.value
                                            1080 -> Qualities.P1080.value
                                            720 -> Qualities.P720.value
                                            480 -> Qualities.P480.value
                                            360 -> Qualities.P360.value
                                            else -> Qualities.Unknown.value
                                        }

                                        val sourceName = "MovieBox"
                                        val displayName = "MovieBox ($languageName)".trim()
                                        val linkType = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE

                                        callback.invoke(
                                            newExtractorLink(
                                                source = sourceName,
                                                name = displayName,
                                                url = streamUrl,
                                                type = linkType
                                            ) {
                                                this.quality = quality
                                                this.headers = mapOf(
                                                    "User-Agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Subsystem for Android(TM); Build/TQ3A.230901.001; Cronet/145.0.7582.0)"
                                                )
                                            }
                                        )
                                        foundLinks = true
                                    }
                                }

                                if (captions != null && captions.isArray) {
                                    for (caption in captions) {
                                        val capUrl = caption["url"]?.asText() ?: continue
                                        val lang = caption["lan"]?.asText() ?: caption["lanName"]?.asText() ?: "Unknown"
                                        subCallback.invoke(newSubtitleFile(lang = lang, url = capUrl))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MovieBoxExtractor", "Error on H5 download", e)
                    }
                }
            }

            return foundLinks
        } catch (e: Exception) {
            Log.e("MovieBoxExtractor", "MovieBox invoke failed", e)
            return false
        }
    }
}
