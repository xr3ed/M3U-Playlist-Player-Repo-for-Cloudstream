package com.xr3ed.gudangfilmxr

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Morencius : VidhideExtractor() {
    override var name = "VIDHIDE"
    override var mainUrl = "https://morencius.com"
}

class Turbovidhls : VidhideExtractor() {
    override var name = "VIDHIDE"
    override var mainUrl = "https://turbovidhls.com"
}

class Vidhidefast : VidhideExtractor() {
    override var name = "VIDHIDE"
    override var mainUrl = "https://vidhidefast.com"
}

class Vidhidepro : VidhideExtractor() {
    override var name = "VIDHIDE"
    override var mainUrl = "https://vidhidepro.com"
}

class Mxdrop : MixDrop() {
    override var name = "MIXDROP"
    override var mainUrl = "https://mxdrop.top"
}

class Mdfx9dc8n : MixDrop() {
    override var name = "MIXDROP"
    override var mainUrl = "https://mdfx9dc8n.net"
}

class Hglink : StreamWishExtractor() {
    override var name = "STREAMWISH"
    override var mainUrl = "https://hglink.to"
}

open class AbyssExtractor : ExtractorApi() {
    override var name = "HYDRAX"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val ref = referer ?: "https://playsobat.xyz/"
            val html = app.get(url, headers = mapOf("Referer" to ref, "User-Agent" to USER_AGENT), timeout = 8).text
            if (html.isBlank()) return

            val datasBase64 = Regex("""const datas = "([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
                ?: return

            val decodedPayloadBytes = Base64.getDecoder().decode(datasBase64)
            val decodedPayloadJson = String(decodedPayloadBytes, Charsets.ISO_8859_1)

            val payload = JSONObject(decodedPayloadJson)
            val slug = payload.optString("slug")
            val md5Id = payload.optLong("md5_id")
            val userId = payload.optLong("user_id")
            val media = payload.optString("media")
            if (media.isBlank()) return

            val keyString = "$userId:$slug:$md5Id"
            val md5Bytes = getMd5HexBytes(keyString)
            val key = md5Bytes
            val iv = md5Bytes.sliceArray(0 until 16)

            val ciphertext = ByteArray(media.length) { i -> (media[i].code and 0xFF).toByte() }
            val decryptedBytes = decryptAesCtr(ciphertext, key, iv)
            val decryptedJson = String(decryptedBytes, Charsets.UTF_8)

            val mediaContainer = JSONObject(decryptedJson)
            val mp4Data = mediaContainer.optJSONObject("mp4") ?: return
            val sources = mp4Data.optJSONArray("sources") ?: return
            val domains = mp4Data.optJSONArray("domains") ?: return

            val domainList = mutableListOf<String>()
            for (i in 0 until domains.length()) {
                domainList.add(domains.getString(i))
            }

            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                val codec = source.optString("codec")
                if (codec.equals("av1", ignoreCase = true)) continue

                val label = source.optString("label")
                val resId = source.optInt("res_id")
                val size = source.optLong("size")
                val sub = source.optString("sub")

                val domain = domainList.firstOrNull { it.contains(sub) } ?: domainList.firstOrNull() ?: continue
                val path = "/mp4/$md5Id/$resId/$size?v=$slug"

                val sizeKeyBytes = getAbyssSizeMd5HexBytes(size.toString())
                val sizeIvBytes = sizeKeyBytes.sliceArray(0 until 16)

                val encryptedPathBytes = encryptAesCtr(path.toByteArray(Charsets.UTF_8), sizeKeyBytes, sizeIvBytes)
                val token = doubleBase64Encode(encryptedPathBytes)

                val finalUrl = "https://$domain/sora/$size/$token"
                val quality = getQualityFromName(label)

                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name $label",
                        url = finalUrl,
                        referer = url,
                        quality = quality,
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to url
                        )
                    )
                )
            }
        } catch (e: Throwable) {
            android.util.Log.e("AbyssExtractor", "Failed to extract AbyssPlayer: ${e.message}")
        }
    }

    private fun getMd5HexBytes(input: String): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        val hexString = digest.joinToString("") { "%02x".format(it) }
        return hexString.toByteArray(Charsets.UTF_8)
    }

    private fun getAbyssSizeMd5HexBytes(sizeStr: String): ByteArray {
        val bytes = ByteArray(sizeStr.length) { i ->
            (sizeStr[i] - '0').toByte()
        }
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        val hexString = digest.joinToString("") { "%02x".format(it) }
        return hexString.toByteArray(Charsets.UTF_8)
    }

    private fun decryptAesCtr(ciphertext: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(ciphertext)
    }

    private fun encryptAesCtr(plaintext: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(plaintext)
    }

    private fun doubleBase64Encode(data: ByteArray): String {
        val b1 = Base64.getEncoder().encodeToString(data).replace("=", "")
        val b2 = Base64.getEncoder().encodeToString(b1.toByteArray(Charsets.US_ASCII)).replace("=", "")
        return b2
    }
}

class AbyssToExtractor : AbyssExtractor() {
    override var mainUrl = "https://abyss.to"
}

class HydraxNetExtractor : AbyssExtractor() {
    override var mainUrl = "https://hydrax.net"
}

class HydraxTopExtractor : AbyssExtractor() {
    override var mainUrl = "https://hydrax.top"
}
