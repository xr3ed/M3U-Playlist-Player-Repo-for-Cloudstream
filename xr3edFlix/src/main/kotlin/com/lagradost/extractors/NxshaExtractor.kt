package com.lagradost.extractors

import android.os.Build
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object NxshaExtractor {
    private const val TAG = "NxshaExtractor"
    private const val BASE_URL = "https://nxsha.space"
    private const val PASSPHRASE = "S8x!Jk4ZP1uG8\$my"
    private val SALTED_HEADER = "Salted__".toByteArray(Charsets.UTF_8)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Referer" to "$BASE_URL/embed/",
        "Accept" to "application/json"
    )

    private fun evpBytesToKey(
        passphrase: ByteArray,
        salt: ByteArray,
        keyLen: Int = 32,
        ivLen: Int = 16
    ): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        val keyAndIv = ByteArray(keyLen + ivLen)
        var currentHash = ByteArray(0)
        var offset = 0
        while (offset < keyLen + ivLen) {
            md5.reset()
            md5.update(currentHash)
            md5.update(passphrase)
            md5.update(salt)
            currentHash = md5.digest()
            val toCopy = minOf(currentHash.size, keyLen + ivLen - offset)
            System.arraycopy(currentHash, 0, keyAndIv, offset, toCopy)
            offset += toCopy
        }
        val key = ByteArray(keyLen)
        val iv = ByteArray(ivLen)
        System.arraycopy(keyAndIv, 0, key, 0, keyLen)
        System.arraycopy(keyAndIv, keyLen, iv, 0, ivLen)
        return Pair(key, iv)
    }

    private fun safeBase64Encode(bytes: ByteArray): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } else {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    private fun safeBase64Decode(str: String): ByteArray {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.util.Base64.getDecoder().decode(str)
        } else {
            Base64.decode(str, Base64.DEFAULT)
        }
    }

    private fun encrypt(plainText: String): String {
        val salt = ByteArray(8)
        SecureRandom().nextBytes(salt)
        val (key, iv) = evpBytesToKey(PASSPHRASE.toByteArray(Charsets.UTF_8), salt, 32, 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(SALTED_HEADER.size + salt.size + cipherText.size)
        System.arraycopy(SALTED_HEADER, 0, combined, 0, SALTED_HEADER.size)
        System.arraycopy(salt, 0, combined, SALTED_HEADER.size, salt.size)
        System.arraycopy(cipherText, 0, combined, SALTED_HEADER.size + salt.size, cipherText.size)
        val b64 = safeBase64Encode(combined)
        return b64.replace('+', '-').replace('/', '_').replace("=", "")
    }

    private fun decrypt(encryptedBase64: String): String? {
        return try {
            var b64 = encryptedBase64.replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) b64 += "="
            val bytes = safeBase64Decode(b64)
            if (bytes.size < 16) return null
            for (i in 0 until 8) {
                if (bytes[i] != SALTED_HEADER[i]) return null
            }
            val salt = bytes.copyOfRange(8, 16)
            val cipherText = bytes.copyOfRange(16, bytes.size)
            val (key, iv) = evpBytesToKey(PASSPHRASE.toByteArray(Charsets.UTF_8), salt, 32, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error: ${e.message}")
            null
        }
    }

    private fun randomSalt(length: Int = 10): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return (1..length).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    private fun encodePayload(obj: JSONObject): String {
        obj.put("_req_ts", System.currentTimeMillis())
        obj.put("_req_salt", randomSalt(10))
        return encrypt(obj.toString())
    }

    suspend fun invoke(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null && imdbId.isNullOrEmpty()) return

        val isMovie = season == null || episode == null
        val type = if (isMovie) "movie" else "tv"

        coroutineScope {
            // 1. Fetch Subtitles in parallel
            launchSubtitles(tmdbId, imdbId, type, season, episode, subtitleCallback)

            // 2. Fetch Server List
            val serverListPayload = JSONObject().apply {
                put("tmdbId", tmdbId?.toString() ?: "")
                put("imdb_id", imdbId ?: "")
                put("type", type)
                if (!isMovie) {
                    put("season", season)
                    put("episode", episode)
                }
            }

            val encServerQ = encodePayload(serverListPayload)
            val serverListUrl = "$BASE_URL/api/servers?q=${URLEncoder.encode(encServerQ, "UTF-8")}"
            val serverListRes = runCatching {
                app.get(serverListUrl, headers = headers, timeout = 10)
            }.getOrNull()

            val scrapersToRun = mutableListOf<Pair<String, String>>() // scraper to displayName
            if (serverListRes != null && serverListRes.code == 200) {
                val resObj = JSONObject(serverListRes.text)
                val hash = resObj.optString("_hash")
                if (hash.isNotBlank()) {
                    val decStr = decrypt(hash)
                    if (!decStr.isNullOrBlank()) {
                        val decObj = JSONObject(decStr)
                        val serversArr = decObj.optJSONArray("servers")
                        if (serversArr != null) {
                            for (i in 0 until serversArr.length()) {
                                val sObj = serversArr.optJSONObject(i) ?: continue
                                val scraper = sObj.optString("scraper")
                                val name = sObj.optString("name", scraper)
                                if (scraper.isNotBlank()) {
                                    scrapersToRun.add(Pair(scraper, name))
                                }
                            }
                        }
                    }
                }
            }

            // Fallback list of top responsive scrapers if server list failed or empty
            if (scrapersToRun.isEmpty()) {
                val fallbackScrapers = listOf(
                    "vidapi" to "VidPi",
                    "castle" to "CastVid",
                    "yomovies" to "StreamX",
                    "nitro" to "Nitro",
                    "mbox" to "MovieBox",
                    "em-8" to "VidHindi",
                    "bdxs" to "Multi-blue",
                    "watchout" to "Multi-bill",
                    "stvv" to "Stvvid",
                    "streamflix" to "StremFx"
                )
                scrapersToRun.addAll(fallbackScrapers)
            }

            // 3. Query all scrapers concurrently
            scrapersToRun.map { (scraper, providerName) ->
                async {
                    try {
                        val sourcePayload = JSONObject().apply {
                            put("ex_lang", false)
                            put("provider", scraper)
                            put("tmdbId", tmdbId?.toString() ?: "")
                            put("imdb_id", imdbId ?: "")
                            put("type", type)
                            if (!isMovie) {
                                put("season", season)
                                put("episode", episode)
                            }
                        }

                        val encSourceQ = encodePayload(sourcePayload)
                        val sourceUrl = "$BASE_URL/api/sources?q=${URLEncoder.encode(encSourceQ, "UTF-8")}"
                        val sourceRes = app.get(sourceUrl, headers = headers, timeout = 12)
                        if (sourceRes.code != 200) return@async

                        val sJson = JSONObject(sourceRes.text)
                        val hash = sJson.optString("_hash")
                        if (hash.isBlank()) return@async

                        val decSourceStr = decrypt(hash) ?: return@async
                        val decSourceObj = JSONObject(decSourceStr)
                        val sourcesArr = decSourceObj.optJSONArray("sources") ?: return@async

                        for (j in 0 until sourcesArr.length()) {
                            val srcObj = sourcesArr.optJSONObject(j) ?: continue
                            val streamUrl = srcObj.optString("url")
                            if (streamUrl.isBlank()) continue

                            val qualityStr = srcObj.optString("quality", "")
                            val label = srcObj.optString("label", "")
                            val isEmbed = srcObj.optBoolean("isEmbed", false)
                            val typeStr = srcObj.optString("type", "")

                            if (isEmbed || typeStr == "embed") {
                                loadExtractor(streamUrl, "$BASE_URL/", subtitleCallback, callback)
                            } else {
                                val quality = when {
                                    qualityStr.contains("4k", ignoreCase = true) || qualityStr.contains("2160") -> Qualities.P2160.value
                                    qualityStr.contains("1080") -> Qualities.P1080.value
                                    qualityStr.contains("720") -> Qualities.P720.value
                                    qualityStr.contains("480") -> Qualities.P480.value
                                    qualityStr.contains("360") -> Qualities.P360.value
                                    streamUrl.contains("1080") -> Qualities.P1080.value
                                    streamUrl.contains("720") -> Qualities.P720.value
                                    streamUrl.contains("480") -> Qualities.P480.value
                                    else -> Qualities.P1080.value
                                }

                                val linkType = if (typeStr == "hls" || typeStr == "m3u8" || streamUrl.contains(".m3u8")) {
                                    ExtractorLinkType.M3U8
                                } else {
                                    ExtractorLinkType.VIDEO
                                }

                                val cleanLabel = if (label.isNotBlank() && label != "Auto" && label != "Original audio") {
                                    " - $label"
                                } else if (qualityStr.isNotBlank() && qualityStr != "Auto") {
                                    " - $qualityStr"
                                } else ""

                                val displayName = "$providerName$cleanLabel"

                                callback.invoke(
                                    newExtractorLink(
                                        name = displayName,
                                        source = "Nxsha - $providerName",
                                        url = streamUrl,
                                        type = linkType
                                    ) {
                                        this.quality = quality
                                        this.referer = "$BASE_URL/"
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Scraper $scraper failed: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }

    private fun launchSubtitles(
        tmdbId: Int?,
        imdbId: String?,
        type: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        kotlinx.coroutines.GlobalScope.async {
            try {
                val subPayload = JSONObject().apply {
                    put("tmdbId", tmdbId?.toString() ?: "")
                    put("imdb_id", imdbId ?: "")
                    put("type", type)
                    if (type == "tv") {
                        put("season", season)
                        put("episode", episode)
                    }
                }
                val encSubQ = encodePayload(subPayload)
                val subUrl = "$BASE_URL/api/subtitles?q=${URLEncoder.encode(encSubQ, "UTF-8")}"
                val subRes = app.get(subUrl, headers = headers, timeout = 8)
                if (subRes.code == 200) {
                    val sJson = JSONObject(subRes.text)
                    val hash = sJson.optString("_hash")
                    if (hash.isNotBlank()) {
                        val decStr = decrypt(hash)
                        if (!decStr.isNullOrBlank()) {
                            val decObj = JSONObject(decStr)
                            val subsArr = decObj.optJSONArray("subtitles")
                            if (subsArr != null) {
                                for (i in 0 until subsArr.length()) {
                                    val sub = subsArr.optJSONObject(i) ?: continue
                                    val uri = sub.optString("uri")
                                    val title = sub.optString("title", sub.optString("language", "Unknown"))
                                    if (uri.isNotBlank()) {
                                        subtitleCallback.invoke(newSubtitleFile(title, uri))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Subtitles failed: ${e.message}")
            }
        }
    }
}
