package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import java.net.URLDecoder
import android.content.Context
import java.net.ServerSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlin.concurrent.thread

object LocalManifestServer {
    private var serverSocket: ServerSocket? = null
    private var serverPort: Int = 0
    
    class ManifestMetadata(
        val originalUrl: String,
        val drmLicenseParam: String,
        val headers: Map<String, String>
    )
    private val manifestMetadatas = java.util.concurrent.ConcurrentHashMap<String, ManifestMetadata>()

    fun start(): Int {
        if (serverSocket != null) return serverPort
        try {
            val socket = ServerSocket(0)
            serverSocket = socket
            serverPort = socket.localPort
            thread(start = true, isDaemon = true) {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        thread(start = true, isDaemon = true) {
                            try {
                                 val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                                 val line = reader.readLine() ?: ""
                                 android.util.Log.d("EventProvider", "LocalManifestServer HTTP request: $line")
                                 val path = line.split(" ").getOrNull(1) ?: ""
                                 
                                 if (path.startsWith("/init_")) {
                                     // Penanganan proxy segmen inisialisasi untuk stripping pssh box Widevine
                                     val id = path.substringAfter("/init_", "").substringBefore("?", "")
                                     
                                     // Ekstrak query parameters
                                     val queryStr = path.substringAfter("?", "")
                                     val paramsMap = HashMap<String, String>()
                                      if (queryStr.isNotEmpty()) {
                                          for (param in queryStr.split("&")) {
                                              val idx = param.indexOf('=')
                                              if (idx != -1) {
                                                  val key = param.substring(0, idx)
                                                  val value = param.substring(idx + 1)
                                                  paramsMap[key] = java.net.URLDecoder.decode(value, "UTF-8")
                                              }
                                          }
                                      }
                                     
                                     val rep = paramsMap["rep"] ?: ""
                                     val base = paramsMap["base"] ?: ""
                                     val origPath = paramsMap["path"] ?: ""
                                     val origParams = paramsMap["params"] ?: ""
                                     val ref = paramsMap["ref"] ?: ""
                                     val orig = paramsMap["orig"] ?: ""
                                     
                                     // Susun URL asli
                                     val cleanPath = origPath.replace("\$RepresentationID\$", rep).replace("\$RepresentationID", rep)
                                     val sep = if (cleanPath.contains("?")) "&" else "?"
                                     val originalUrl = base + cleanPath + (if (origParams.isNotEmpty()) sep + origParams else "")
                                     
                                     android.util.Log.d("EventProvider", "LocalManifestServer proxying init segment: $originalUrl")
                                     
                                     val headers = mapOf(
                                         "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                         "Referer" to if (ref.isNotEmpty()) ref else "https://xys1-2-player.pages.dev/",
                                         "Origin" to if (orig.isNotEmpty()) orig else "https://xys1-2-player.pages.dev"
                                     )
                                     
                                     var bytes: ByteArray? = null
                                     try {
                                         val response = kotlinx.coroutines.runBlocking {
                                             app.get(originalUrl, headers = headers, timeout = 20)
                                         }
                                         bytes = response.body.bytes()
                                     } catch (e: Exception) {
                                         android.util.Log.e("EventProvider", "Failed to download original init segment: ${e.message}", e)
                                     }
                                     
                                     val clientOs = client.getOutputStream()
                                     if (bytes != null && bytes.isNotEmpty()) {
                                         val modifiedBytes = bytes.clone()
                                         var psshCount = 0
                                         for (i in 0 until modifiedBytes.size - 3) {
                                             if (modifiedBytes[i] == 0x70.toByte() && // 'p'
                                                 modifiedBytes[i+1] == 0x73.toByte() && // 's'
                                                 modifiedBytes[i+2] == 0x73.toByte() && // 's'
                                                 modifiedBytes[i+3] == 0x68.toByte()) { // 'h'
                                                 
                                                 modifiedBytes[i] = 0x66.toByte()   // 'f'
                                                 modifiedBytes[i+1] = 0x72.toByte() // 'r'
                                                 modifiedBytes[i+2] = 0x65.toByte() // 'e'
                                                 modifiedBytes[i+3] = 0x65.toByte() // 'e'
                                                 psshCount++
                                             }
                                         }
                                         android.util.Log.d("EventProvider", "LocalManifestServer stripped $psshCount pssh box(es) from init segment.")
                                         
                                         val headerString = "HTTP/1.1 200 OK\r\n" +
                                                            "Content-Type: video/mp4\r\n" +
                                                            "Content-Length: ${modifiedBytes.size}\r\n" +
                                                            "Access-Control-Allow-Origin: *\r\n" +
                                                            "Connection: close\r\n" +
                                                            "\r\n"
                                         clientOs.write(headerString.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                         clientOs.write(modifiedBytes)
                                         clientOs.flush()
                                         android.util.Log.d("EventProvider", "LocalManifestServer successfully sent modified init segment.")
                                     } else {
                                         val headerString = "HTTP/1.1 404 Not Found\r\n" +
                                                            "Connection: close\r\n" +
                                                            "\r\n"
                                         clientOs.write(headerString.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                         clientOs.flush()
                                     }
                                 } else {
                                     // Request manifest secara dinamis
                                     val id = path.substringAfter("/manifest_", "").substringBefore(".mpd", "")
                                     val meta = manifestMetadatas[id]
                                     android.util.Log.d("EventProvider", "LocalManifestServer resolved ID: $id, found metadata: ${meta != null}")
                                     
                                     val out = java.io.PrintWriter(java.io.OutputStreamWriter(client.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8), true)
                                     if (meta != null) {
                                         var xml: String? = null
                                         try {
                                             val manifestHeaders = meta.headers.toMutableMap().apply {
                                                 put("Accept", "application/dash+xml,video/mpd,application/xml;q=0.9,*/*;q=0.8")
                                                 put("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
                                             }
                                              val manifestUrl = if (meta.originalUrl.startsWith("https://") && meta.originalUrl.contains("workers.dev")) {
                                                  meta.originalUrl.replace("https://", "http://")
                                              } else {
                                                  meta.originalUrl
                                              }
                                              val response = kotlinx.coroutines.runBlocking {
                                                  app.get(manifestUrl, headers = manifestHeaders, timeout = 25)
                                              }
                                              val manifestXml = response.text
                                              
                                              var modifiedXml = manifestXml
                                              
                                              // Paksa tipe MPD menjadi dynamic agar dideteksi sebagai Live (menghindari batasan durasi 30 menit / 1 jam)
                                              if (modifiedXml.contains("type=\"static\"", ignoreCase = true) || modifiedXml.contains("type='static'", ignoreCase = true)) {
                                                  modifiedXml = modifiedXml.replace(Regex("""type\s*=\s*["']static["']""", RegexOption.IGNORE_CASE), """type="dynamic"""")
                                              }
                                              if (!modifiedXml.contains("minimumUpdatePeriod", ignoreCase = true)) {
                                                  modifiedXml = modifiedXml.replace(Regex("""<MPD"""), """<MPD minimumUpdatePeriod="PT2S"""")
                                              }
                                              // Hapus mediaPresentationDuration agar durasi video tidak dibatasi (sehingga berjalan terus sebagai LIVE sejati)
                                              modifiedXml = modifiedXml.replace(Regex("""mediaPresentationDuration\s*=\s*["'][^"']+["']""", RegexOption.IGNORE_CASE), "")

                                              // 1. Hapus Widevine
                                              modifiedXml = modifiedXml.replace(Regex("""<ContentProtection[^>]*schemeIdUri=\s*["']urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed["'][^>]*>([\s\S]*?)</ContentProtection>""", RegexOption.IGNORE_CASE), "")
                                              modifiedXml = modifiedXml.replace(Regex("""<ContentProtection[^>]*schemeIdUri=\s*["']urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed["'][^>]*/\s*>""", RegexOption.IGNORE_CASE), "")
                                              
                                              // 2. Hapus PlayReady
                                              modifiedXml = modifiedXml.replace(Regex("""<ContentProtection[^>]*schemeIdUri=\s*["']urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95["'][^>]*>([\s\S]*?)</ContentProtection>""", RegexOption.IGNORE_CASE), "")
                                              modifiedXml = modifiedXml.replace(Regex("""<ContentProtection[^>]*schemeIdUri=\s*["']urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95["'][^>]*/\s*>""", RegexOption.IGNORE_CASE), "")

                                              // 3. Tambahkan namespace cenc jika belum ada
                                              if (!modifiedXml.contains("xmlns:cenc")) {
                                                  modifiedXml = modifiedXml.replace("<MPD", "<MPD xmlns:cenc=\"urn:mpeg:cenc:2013\"")
                                              }

                                              // 4. Injeksikan ClearKey ContentProtection dengan default_KID yang sesuai (selalu berformat UUID dengan tanda hubung)
                                              val rawKid = if (meta.drmLicenseParam.contains(":")) {
                                                  meta.drmLicenseParam.substringBefore(":").trim()
                                              } else {
                                                  meta.drmLicenseParam.trim()
                                              }
                                              val cleanKid = rawKid.replace("-", "")
                                              val kidUuid = if (cleanKid.length == 32) {
                                                  "${cleanKid.substring(0, 8)}-${cleanKid.substring(8, 12)}-${cleanKid.substring(12, 16)}-${cleanKid.substring(16, 20)}-${cleanKid.substring(20)}"
                                              } else {
                                                  rawKid
                                              }
                                              
                                               if (kidUuid.isNotEmpty()) {
                                                    val clearKeyBlock = """
                                                        <ContentProtection schemeIdUri="urn:uuid:e2513a00-7bfb-11e9-9130-0242ac110002" cenc:default_KID="$kidUuid" xmlns:cenc="urn:mpeg:cenc:2013"/>
                                                        <ContentProtection schemeIdUri="urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e" cenc:default_KID="$kidUuid" xmlns:cenc="urn:mpeg:cenc:2013"/>
                                                    """.trimIndent()
                                                   var injectCount = 0
                                                   modifiedXml = modifiedXml.replace(Regex("""<AdaptationSet([^>]*)>""", RegexOption.IGNORE_CASE)) { matchResult ->
                                                       injectCount++
                                                       matchResult.value + "\n" + clearKeyBlock + "\n"
                                                   }
                                                   android.util.Log.d("EventProvider", "LocalManifestServer injected ClearKey block $injectCount times. KID=$kidUuid")
                                               }
                                             
                                             val finalUrl = response.url
                                             val queryParams = if (finalUrl.contains("?")) finalUrl.substringAfter("?") else ""
                                             val baseUrlString = if (finalUrl.contains("?")) finalUrl.substringBefore("?") else finalUrl
                                             val absoluteBaseUrl = baseUrlString.substringBeforeLast("/") + "/"
                                             
                                             if (!modifiedXml.contains("<BaseURL>") && !modifiedXml.contains("<BaseURL ")) {
                                                 val periodIdx = modifiedXml.indexOf("<Period")
                                                 if (periodIdx != -1) {
                                                     val insertIdx = modifiedXml.indexOf(">", periodIdx) + 1
                                                     modifiedXml = modifiedXml.substring(0, insertIdx) + "\n<BaseURL>$absoluteBaseUrl</BaseURL>\n" + modifiedXml.substring(insertIdx)
                                                 } else {
                                                     val mpdIdx = modifiedXml.indexOf("<MPD")
                                                     if (mpdIdx != -1) {
                                                         val insertIdx = modifiedXml.indexOf(">", mpdIdx) + 1
                                                         modifiedXml = modifiedXml.substring(0, insertIdx) + "\n<BaseURL>$absoluteBaseUrl</BaseURL>\n" + modifiedXml.substring(insertIdx)
                                                     }
                                                 }
                                             }
                                             
                                              // Tulis ulang SegmentTemplate media agar selalu absolute (mencegah ExoPlayer meminta chunk video ke proxy lokal)
                                              val rootDomain = if (absoluteBaseUrl.startsWith("https://")) {
                                                  "https://" + absoluteBaseUrl.substringAfter("https://").substringBefore("/")
                                              } else if (absoluteBaseUrl.startsWith("http://")) {
                                                  "http://" + absoluteBaseUrl.substringAfter("http://").substringBefore("/")
                                              } else {
                                                  ""
                                              }
                                              modifiedXml = modifiedXml.replace(Regex("""media=["']([^"']+)["']""", RegexOption.IGNORE_CASE)) { matchResult ->
                                                  val p1 = matchResult.groupValues[1]
                                                  if (p1.startsWith("http://", ignoreCase = true) || p1.startsWith("https://", ignoreCase = true)) {
                                                      matchResult.value
                                                  } else {
                                                      val absoluteMediaUrl = if (p1.startsWith("/")) {
                                                          rootDomain + p1
                                                      } else {
                                                          absoluteBaseUrl + p1
                                                      }
                                                      val sep = if (absoluteMediaUrl.contains("?")) "&amp;" else "?"
                                                      val finalMediaUrl = if (queryParams.isNotEmpty()) {
                                                          absoluteMediaUrl + sep + queryParams.replace("&", "&amp;")
                                                      } else {
                                                          absoluteMediaUrl
                                                      }
                                                      """media="$finalMediaUrl""""
                                                  }
                                              }
                                             
                                             // Tulis ulang SegmentTemplate initialization
                                             val cleanId = meta.drmLicenseParam.replace("-", "").replace(":", "").replace(",", "").trim()
                                             val refHeader = meta.headers["Referer"] ?: ""
                                             val originHeader = meta.headers["Origin"] ?: ""
                                             
                                             modifiedXml = modifiedXml.replace(Regex("""initialization=["']([^"']+)["']""", RegexOption.IGNORE_CASE)) { matchResult ->
                                                 val path = matchResult.groupValues[1]
                                                 val encodedBase = java.net.URLEncoder.encode(absoluteBaseUrl, "UTF-8")
                                                 val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                                                 val encodedParams = java.net.URLEncoder.encode(queryParams, "UTF-8")
                                                 val encodedRef = java.net.URLEncoder.encode(refHeader, "UTF-8")
                                                 val encodedOrigin = java.net.URLEncoder.encode(originHeader, "UTF-8")
                                                 
                                                 """initialization="http://127.0.0.1:$serverPort/init_$cleanId?rep=${'$'}RepresentationID${'$'}&amp;base=$encodedBase&amp;path=$encodedPath&amp;params=$encodedParams&amp;ref=$encodedRef&amp;orig=$encodedOrigin""""
                                             }
                                             
                                             // ClearKey tags
                                             val hasClearKey = modifiedXml.contains("urn:uuid:e2513a00-7bfb-11e9-9130-0242ac110002", ignoreCase = true)
                                             if (!hasClearKey) {
                                                 val keyPairs = meta.drmLicenseParam.split(",")
                                                 val contentProtectionXmlBuilder = StringBuilder()
                                                 for (pair in keyPairs) {
                                                     val parts = pair.split(":")
                                                     if (parts.size == 2) {
                                                         val keyId = parts[0].trim()
                                                         val uuidKid = if (keyId.length == 32) {
                                                             "${keyId.substring(0, 8)}-${keyId.substring(8, 12)}-${keyId.substring(12, 16)}-${keyId.substring(16, 20)}-${keyId.substring(20)}"
                                                         } else {
                                                             keyId
                                                         }
                                                         contentProtectionXmlBuilder.append("""
                                                             <ContentProtection schemeIdUri="urn:uuid:e2513a00-7bfb-11e9-9130-0242ac110002" cenc:default_KID="$uuidKid" xmlns:cenc="urn:mpeg:cenc:2013"/>
                                                         """.trimIndent()).append("\n")
                                                     }
                                                 }
                                                 val contentProtectionXml = contentProtectionXmlBuilder.toString()
                                                 
                                                 if (contentProtectionXml.isNotEmpty()) {
                                                     var tempXml = modifiedXml
                                                     
                                                     val selfClosingRegex = Regex("""<ContentProtection[^>]*urn:mpeg:dash:mp4protection:2011[^>]*/\s*>""", RegexOption.IGNORE_CASE)
                                                     tempXml = selfClosingRegex.replace(tempXml) { matchResult ->
                                                         val matchedTag = matchResult.value
                                                         val kidMatch = Regex("""cenc:default_KID\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(matchedTag)
                                                         
                                                         val clearKeyTag = if (kidMatch != null) {
                                                             val kidToUse = kidMatch.groupValues[1]
                                                             """<ContentProtection schemeIdUri="urn:uuid:e2513a00-7bfb-11e9-9130-0242ac110002" cenc:default_KID="$kidToUse" xmlns:cenc="urn:mpeg:cenc:2013"/>"""
                                                         } else {
                                                             contentProtectionXml.trim()
                                                         }
                                                         matchedTag + "\n" + clearKeyTag
                                                     }
                                                     
                                                     val openCloseRegex = Regex("""<ContentProtection[^>]*urn:mpeg:dash:mp4protection:2011[^>]*>([\s\S]*?)</ContentProtection>""", RegexOption.IGNORE_CASE)
                                                     tempXml = openCloseRegex.replace(tempXml) { matchResult ->
                                                         val matchedTag = matchResult.value
                                                         val kidMatch = Regex("""cenc:default_KID\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(matchedTag)
                                                         
                                                         val clearKeyTag = if (kidMatch != null) {
                                                             val kidToUse = kidMatch.groupValues[1]
                                                             """<ContentProtection schemeIdUri="urn:uuid:e2513a00-7bfb-11e9-9130-0242ac110002" cenc:default_KID="$kidToUse" xmlns:cenc="urn:mpeg:cenc:2013"/>"""
                                                         } else {
                                                             contentProtectionXml.trim()
                                                         }
                                                         matchedTag + "\n" + clearKeyTag
                                                     }
                                                     
                                                     if (tempXml == modifiedXml) {
                                                         val parts = modifiedXml.split("<AdaptationSet")
                                                         if (parts.size > 1) {
                                                             val sb = StringBuilder(parts[0])
                                                             for (i in 1 until parts.size) {
                                                                 sb.append("<AdaptationSet")
                                                                 val rest = parts[i]
                                                                 val tagEndIdx = rest.indexOf(">")
                                                                 if (tagEndIdx != -1) {
                                                                     sb.append(rest.substring(0, tagEndIdx + 1))
                                                                     sb.append("\n$contentProtectionXml\n")
                                                                     sb.append(rest.substring(tagEndIdx + 1))
                                                                 } else {
                                                                     sb.append(rest)
                                                                 }
                                                             }
                                                             tempXml = sb.toString()
                                                         }
                                                     }
                                                     modifiedXml = tempXml
                                                 }
                                             }
                                             xml = modifiedXml
                                         } catch (e: Exception) {
                                             android.util.Log.e("EventProvider", "LocalManifestServer dynamically fetch manifest error", e)
                                         }
                                         
                                         if (xml != null) {
                                             val bytes = xml.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                                             out.print("HTTP/1.1 200 OK\r\n")
                                             out.print("Content-Type: application/dash+xml; charset=utf-8\r\n")
                                             out.print("Content-Length: ${bytes.size}\r\n")
                                             out.print("Access-Control-Allow-Origin: *\r\n")
                                             out.print("Connection: close\r\n")
                                             out.print("\r\n")
                                             out.print(xml)
                                             out.flush()
                                             android.util.Log.d("EventProvider", "LocalManifestServer dynamic HTTP 200 OK sent for ID: $id")
                                         } else {
                                             out.print("HTTP/1.1 502 Bad Gateway\r\n")
                                             out.print("Connection: close\r\n")
                                             out.print("\r\n")
                                             out.flush()
                                         }
                                     } else {
                                         out.print("HTTP/1.1 404 Not Found\r\n")
                                         out.print("Connection: close\r\n")
                                         out.print("\r\n")
                                         out.flush()
                                     }
                                 }
                                 client.close()
                            } catch (e: Exception) {
                                android.util.Log.e("EventProvider", "LocalManifestServer client thread exception: ${e.message}", e)
                                try { client.close() } catch (ex: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        // socket closed
                    }
                }
            }
            return serverPort
        } catch (e: Exception) {
            return 0
        }
    }
    
    fun registerManifest(id: String, originalUrl: String, drmLicenseParam: String, headers: Map<String, String>): String {
        val port = start()
        manifestMetadatas[id] = ManifestMetadata(originalUrl, drmLicenseParam, headers)
        return "http://127.0.0.1:$port/manifest_$id.mpd"
    }
}

class Xr3edEventProvider(val context: Context) : MainAPI() {
    override var mainUrl = "https://wc26.netxtv.id"
    override var name = "xr3ed event"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("World Cup 2026", "worldcup")
    )

    private val defaultLogo = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/refs/heads/builds/world_cup_cover.png"

    private fun hexToBase64Url(str: String): String {
        val clean = str.replace(" ", "").trim()
        val isHex = clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } && clean.length % 2 == 0
        if (!isHex || clean.isEmpty()) return clean
        return try {
            val bytes = ByteArray(clean.length / 2)
            for (i in bytes.indices) {
                bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        } catch (e: Exception) {
            clean
        }
    }

    private fun extractUserAgent(url: String, defaultUa: String): String {
        val decodedUrl = url.replace("%7C", "|").replace("%20", " ")
        val parts = decodedUrl.split("|")
        if (parts.size > 1) {
            val headerPart = parts[1]
            val uaParam = headerPart.split("&").firstOrNull { it.startsWith("User-Agent=", ignoreCase = true) }
            if (uaParam != null) {
                val uaValue = uaParam.substringAfter("=").trim()
                if (uaValue.isNotEmpty()) {
                    return if (uaValue.contains("%")) {
                        try {
                            java.net.URLDecoder.decode(uaValue, "UTF-8")
                        } catch (e: Exception) {
                            uaValue
                        }
                    } else {
                        uaValue
                    }
                }
            }
        }
        return defaultUa
    }

    private suspend fun getDrmDashManifestUrl(originalUrl: String, drmLicenseParam: String, headers: Map<String, String>): String {
        android.util.Log.d("EventProvider", "getDrmDashManifestUrl start: url=$originalUrl, drmLicenseParam=$drmLicenseParam")
        try {
            val cleanId = drmLicenseParam.replace("-", "").replace(":", "").replace(",", "").trim()
            val resultUrl = LocalManifestServer.registerManifest(cleanId, originalUrl, drmLicenseParam, headers)
            android.util.Log.d("EventProvider", "getDrmDashManifestUrl registered dynamic metadata! Local URL: $resultUrl")
            return resultUrl
        } catch (e: Exception) {
            android.util.Log.e("EventProvider", "getDrmDashManifestUrl register error", e)
            return originalUrl
        }
    }

    private fun parseTimeToMinutes(timeStr: String): Int? {
        try {
            val clean = timeStr.replace(".", ":").trim()
            if (clean.contains(":")) {
                val parts = clean.split(":")
                val hour = parts[0].toIntOrNull() ?: return null
                val min = parts[1].toIntOrNull() ?: return null
                return hour * 60 + min
            }
            val hour = clean.toIntOrNull() ?: return null
            return hour * 60
        } catch (e: Exception) {
            return null
        }
    }

    private data class MatchItem(
        val time: String,
        val home: String,
        val away: String,
        val name: String,
        val groupLink: String,
        val matchMinutes: Int
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = ArrayList<SearchResponse>()
        var categoryTitle = "WORLD CUP 2026"
        try {
            val jadwalUrl = "https://api-tvnetx01.pages.dev/netxtv/jadwal.js"
            val response = app.get(jadwalUrl, timeout = 15).text
            val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
            val root = JSONObject(jsonStr)
            val scheduleArray = root.optJSONArray("schedule")
            
            val allMatches = ArrayList<MatchItem>()
            if (scheduleArray != null) {
                for (i in 0 until scheduleArray.length()) {
                    val group = scheduleArray.optJSONObject(i) ?: continue
                    if (!group.optBoolean("active", true)) continue
                    
                    val groupTitle = group.optString("title", "EVENT")
                    if (!groupTitle.contains("WORLD CUP", ignoreCase = true) && !groupTitle.contains("FIFA", ignoreCase = true)) {
                        continue
                    }
                    
                    val linkVal = group.optString("link", "")
                    val matchesArray = group.optJSONArray("matches")
                    if (matchesArray != null) {
                        for (j in 0 until matchesArray.length()) {
                            val match = matchesArray.optJSONObject(j) ?: continue
                            if (!match.optBoolean("active", true)) continue
                            
                            val time = match.optString("time", "")
                            val home = match.optString("home", "")
                            val away = match.optString("away", "")
                            val nameVal = match.optString("name", "")
                            
                            val matchMin = parseTimeToMinutes(time) ?: continue
                            allMatches.add(
                                MatchItem(time, home, away, nameVal, linkVal, matchMin)
                            )
                        }
                    }
                }
            }

            if (allMatches.isNotEmpty()) {
                val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+7"))
                val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                val currentMin = calendar.get(java.util.Calendar.MINUTE)
                val currentMinutes = currentHour * 60 + currentMin

                val activeMatches = ArrayList<MatchItem>()
                val upcomingMatches = ArrayList<MatchItem>()

                for (match in allMatches) {
                    val startMin = match.matchMinutes
                    val endMin = startMin + 120
                    val isActive = if (endMin < 1440) {
                        currentMinutes in startMin..endMin
                    } else {
                        currentMinutes >= startMin || currentMinutes <= (endMin - 1440)
                    }
                    
                    if (isActive) {
                        activeMatches.add(match)
                    } else {
                        upcomingMatches.add(match)
                    }
                }

                val targetMatches = ArrayList<MatchItem>()
                if (activeMatches.isNotEmpty()) {
                    targetMatches.addAll(activeMatches)
                    categoryTitle = if (activeMatches.size == 1) {
                        val m = activeMatches[0]
                        val matchName = if (m.home.isNotEmpty() && m.away.isNotEmpty()) "${m.home} vs ${m.away}" else m.name
                        "LIVE: $matchName (${m.time})"
                    } else {
                        "LIVE MATCHES (${activeMatches[0].time})"
                    }
                } else if (upcomingMatches.isNotEmpty()) {
                    val minDiff = upcomingMatches.map { (it.matchMinutes - currentMinutes + 1440) % 1440 }.minOrNull()
                    if (minDiff != null) {
                        val closestUpcoming = upcomingMatches.filter { ((it.matchMinutes - currentMinutes + 1440) % 1440) == minDiff }
                        targetMatches.addAll(closestUpcoming)
                        categoryTitle = if (closestUpcoming.size == 1) {
                            val m = closestUpcoming[0]
                            val matchName = if (m.home.isNotEmpty() && m.away.isNotEmpty()) "${m.home} vs ${m.away}" else m.name
                            "WORLD CUP 2026 - $matchName (${m.time})"
                        } else {
                            "WORLD CUP 2026 MATCHES (${closestUpcoming[0].time})"
                        }
                    }
                }

                // Ambil channel.js untuk mendapatkan info channel dari group target
                val jsUrl = "https://api-tvnetx01.pages.dev/netxtv/channel.js"
                val responseChannel = app.get(jsUrl, timeout = 15).text
                val jsonChannelStr = if (responseChannel.contains("---")) responseChannel.substringAfter("---").trim() else responseChannel.trim()
                val rootChannel = JSONObject(jsonChannelStr)
                val channelsObj = rootChannel.optJSONObject("channels") ?: JSONObject()
                val groupsObj = rootChannel.optJSONObject("groups") ?: JSONObject()

                val addedChannels = mutableSetOf<String>()
                for (m in targetMatches) {
                    val cleanGroupId = if (m.groupLink.startsWith("go:")) m.groupLink.substringAfter("go:") else m.groupLink
                    
                    val targetGroups = mutableListOf<String>()
                    val keys = groupsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.equals(cleanGroupId, ignoreCase = true)) {
                            targetGroups.add(k)
                        }
                    }
                    if (targetGroups.isEmpty()) {
                        val keys2 = groupsObj.keys()
                        while (keys2.hasNext()) {
                            val k = keys2.next()
                            if (k.startsWith(cleanGroupId, ignoreCase = true) || k.contains(cleanGroupId, ignoreCase = true)) {
                                targetGroups.add(k)
                                break
                            }
                        }
                    }

                    val collectedChannels = mutableListOf<Triple<String, String, String>>()
                    for (gId in targetGroups) {
                        val arr = groupsObj.optJSONArray(gId) ?: continue
                        for (i in 0 until arr.length()) {
                            val chId = arr.optString(i)
                            if (chId.equals("vvip", ignoreCase = true) || chId.equals("replay", ignoreCase = true) || chId.equals("wc-jadwal", ignoreCase = true)) continue
                            if (addedChannels.contains(chId)) continue
                            addedChannels.add(chId)

                            val chData = channelsObj.optJSONObject(chId) ?: continue
                            val chName = chData.optString("name", chId)
                            val streamUrl = "https://wc26.netxtv.id/?id=jadwal#go:$chId"
                            collectedChannels.add(Triple(chId, chName, streamUrl))
                        }
                    }

                    val sortedChannels = collectedChannels.sortedWith(compareBy<Triple<String, String, String>> {
                        val chIdLower = it.first.lowercase()
                        val chNameLower = it.second.lowercase()
                        
                        val isEnglish = chIdLower.contains("eng") || chIdLower.contains("english") ||
                                        chIdLower.contains("us") || chIdLower.contains("uk") ||
                                        chIdLower.contains("dazn") || chIdLower.contains("bein") ||
                                        chIdLower.contains("sky") || chIdLower.contains("optus") ||
                                        chIdLower.contains("supersport") || chIdLower.contains("tsn") ||
                                        chIdLower.contains("espn") || chIdLower.contains("fox") ||
                                        chIdLower.contains("astro") || chIdLower.contains("hub") ||
                                        chIdLower.contains("premier") || 
                                        chIdLower.contains("ppv") || chIdLower.contains("vvip") ||
                                        chNameLower.contains("eng") || chNameLower.contains("english") ||
                                        chNameLower.contains("us") || chNameLower.contains("uk") ||
                                        chNameLower.contains("dazn") || chNameLower.contains("bein") ||
                                        chNameLower.contains("sky") || chNameLower.contains("optus") ||
                                        chNameLower.contains("supersport") || chNameLower.contains("tsn") ||
                                        chNameLower.contains("espn") || chNameLower.contains("fox") ||
                                        chNameLower.contains("astro") || chNameLower.contains("hub") ||
                                        chNameLower.contains("premier") || 
                                        chNameLower.contains("ppv") || chNameLower.contains("vvip")
                                        
                        val isLokal = chIdLower.contains("tvri") || chIdLower.contains("vidio") ||
                                      chIdLower.contains("sctv") || chIdLower.contains("indosiar") ||
                                      chIdLower.contains("trans") || chIdLower.contains("rcti") ||
                                      chIdLower.contains("lokal") || chIdLower.contains("moji") ||
                                      chIdLower.contains("one1") ||
                                      chNameLower.contains("tvri") || chNameLower.contains("vidio") ||
                                      chNameLower.contains("sctv") || chNameLower.contains("indosiar") ||
                                      chNameLower.contains("trans") || chNameLower.contains("rcti") ||
                                      chNameLower.contains("lokal") || chNameLower.contains("moji") ||
                                      chNameLower.contains("one1")
                        
                        when {
                            isEnglish -> 1
                            isLokal -> 2
                            else -> 3
                        }
                    }.thenBy { it.second })

                    for (ch in sortedChannels) {
                        android.util.Log.d("EventProvider", "Sorted channel: ID=${ch.first}, Name=${ch.second}")
                        list.add(
                            newLiveSearchResponse(
                                ch.second,
                                ch.third,
                                TvType.Live
                            ) {
                                this.posterUrl = defaultLogo
                            }
                        )
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (list.isEmpty()) {
            categoryTitle = "WORLD CUP 2026"
            list.add(
                newLiveSearchResponse(
                    "Sedang tidak ada pertandingan piala dunia aktif saat ini",
                    "https://wc26.netxtv.id/?id=jadwal#none",
                    TvType.Live
                ) {
                    this.posterUrl = defaultLogo
                }
            )
        }

        return newHomePageResponse(
            listOf(HomePageList(categoryTitle, list)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val list = ArrayList<SearchResponse>()
        try {
            val jsUrl = "https://api-tvnetx01.pages.dev/netxtv/channel.js"
            val response = app.get(jsUrl, timeout = 15).text
            val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
            val root = JSONObject(jsonStr)
            val channelsObj = root.optJSONObject("channels") ?: JSONObject()
            val groupsObj = root.optJSONObject("groups") ?: JSONObject()

            val targetChannelIds = mutableSetOf<String>()
            val keys = groupsObj.keys()
            while (keys.hasNext()) {
                val groupKey = keys.next()
                if (groupKey.contains("ucl") || groupKey.contains("wc") || groupKey.contains("timnas")) {
                    val arr = groupsObj.optJSONArray(groupKey)
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            targetChannelIds.add(arr.optString(i))
                        }
                    }
                }
            }

            for (chId in targetChannelIds) {
                val ch = channelsObj.optJSONObject(chId) ?: continue
                val chName = ch.optString("name", chId)
                val chImg = ch.optString("img", defaultLogo)
                val chHref = ch.optString("href")

                if (chHref.isNullOrBlank()) continue
                if (!chName.contains(query, ignoreCase = true)) continue

                val streamUrl = if (chHref.startsWith("go:")) {
                    "https://wc26.netxtv.id/?id=jadwal#$chHref"
                } else {
                    "https://wc26.netxtv.id/?id=jadwal#go:$chId"
                }

                list.add(
                    newLiveSearchResponse(
                        chName,
                        streamUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = chImg
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse {
        var title = "Live Stream World Cup"
        try {
            if (url.contains("#match:")) {
                val groupId = url.substringAfter("#match:")
                title = "Live Match - $groupId"
                return newLiveStreamLoadResponse(
                    title,
                    url,
                    url
                ) {
                    this.posterUrl = defaultLogo
                    this.plot = "Pilih server saluran streaming di bawah untuk menonton pertandingan ini secara langsung."
                }
            } else {
                var code = if (url.contains("#go:")) url.substringAfter("#go:") else url.substringAfter("#")
                if (code.contains("?")) {
                    code = code.substringBefore("?")
                }
                if (code.contains("&")) {
                    code = code.substringBefore("&")
                }
                title = "Live Stream - $code"
                val linkUrl = "https://wc26.netxtv.id/?id=jadwal#go:$code"
                return newLiveStreamLoadResponse(
                    title,
                    linkUrl,
                    linkUrl
                ) {
                    this.posterUrl = defaultLogo
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return newLiveStreamLoadResponse(
            title,
            url,
            url
        ) {
            this.posterUrl = defaultLogo
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            if (data.contains("#match:")) {
                val groupId = data.substringAfter("#match:")
                val jsUrl = "https://api-tvnetx01.pages.dev/netxtv/channel.js"
                val response = app.get(jsUrl, timeout = 15).text
                val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
                val root = JSONObject(jsonStr)
                val channelsObj = root.optJSONObject("channels") ?: JSONObject()
                val groupsObj = root.optJSONObject("groups") ?: JSONObject()
                
                val targetGroups = mutableListOf<String>()
                val keys = groupsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.equals(groupId, ignoreCase = true)) {
                        targetGroups.add(k)
                    }
                }
                if (targetGroups.isEmpty()) {
                    val keys2 = groupsObj.keys()
                    while (keys2.hasNext()) {
                        val k = keys2.next()
                        if (k.startsWith(groupId, ignoreCase = true) || k.contains(groupId, ignoreCase = true)) {
                            targetGroups.add(k)
                            break
                        }
                    }
                }
                
                val addedChannels = mutableSetOf<String>()
                var resolvedAny = false
                
                for (gId in targetGroups) {
                    val arr = groupsObj.optJSONArray(gId) ?: continue
                    for (i in 0 until arr.length()) {
                        val chId = arr.optString(i)
                        if (chId.equals("vvip", ignoreCase = true) || chId.equals("replay", ignoreCase = true) || chId.equals("wc-jadwal", ignoreCase = true)) continue
                        if (addedChannels.contains(chId)) continue
                        addedChannels.add(chId)
                        
                        val chData = channelsObj.optJSONObject(chId) ?: continue
                        val chName = chData.optString("name", chId)
                        val chHref = chData.optString("href") ?: ""
                        
                        val linkUrl = if (chHref.startsWith("go:")) {
                            "https://xys1-2-player.pages.dev/bitmovin/?id=${chHref.substringAfter("go:")}"
                        } else if (chHref.isNotEmpty()) {
                            chHref
                        } else {
                            "https://xys1-2-player.pages.dev/bitmovin/?id=$chId"
                        }
                        
                        val success = resolveSingleChannel(linkUrl, chName, callback)
                        if (success) {
                            resolvedAny = true
                        }
                    }
                }
                return resolvedAny
            } else {
                var targetUrl = data
                var chName = "Server"
                if (data.contains("#go:")) {
                    var code = data.substringAfter("#go:").substringBefore("#").substringBefore("&")
                    if (code.contains("?")) {
                        code = code.substringBefore("?")
                    }
                    val jsUrl = "https://api-tvnetx01.pages.dev/netxtv/channel.js"
                    val response = app.get(jsUrl, timeout = 15).text
                    val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
                    val root = JSONObject(jsonStr)
                    val channelsObj = root.optJSONObject("channels") ?: JSONObject()
                    
                    var resolved = false
                    var depth = 0
                    while (!resolved && depth < 5) {
                        val channelData = channelsObj.optJSONObject(code)
                        if (channelData != null) {
                            chName = channelData.optString("name", "Server")
                            val href = channelData.optString("href")
                            if (!href.isNullOrBlank()) {
                                 if (href.startsWith("go:")) {
                                      var nextCode = href.substringAfter("go:").substringBefore("#").substringBefore("&")
                                      if (nextCode.contains("?")) {
                                          nextCode = nextCode.substringBefore("?")
                                      }
                                      if (nextCode == code) {
                                          targetUrl = "https://xys1-2-player.pages.dev/bitmovin/?id=$code"
                                          resolved = true
                                      } else {
                                          code = nextCode
                                          depth++
                                      }
                                 } else {
                                      targetUrl = href
                                      resolved = true
                                 }
                            } else {
                                resolved = true
                            }
                        } else {
                            targetUrl = "https://xys1-2-player.pages.dev/bitmovin/?id=$code"
                            resolved = true
                        }
                    }
                }
                return resolveSingleChannel(targetUrl, chName, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private suspend fun resolveSingleChannel(
        targetUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var currentTargetUrl = targetUrl
        try {
            if (currentTargetUrl.contains(".pages.dev/") && (currentTargetUrl.contains("bitmovin") || currentTargetUrl.contains("shaka") || currentTargetUrl.contains("jwplayer") || currentTargetUrl.contains("clappr") || currentTargetUrl.contains("nsplayer"))) {
                var idVal = currentTargetUrl.substringAfter("id=").substringBefore("&").substringBefore("#")
                idVal = when (idVal) {
                    "vpl6" -> "tvrivp"
                    "vpl8" -> "tvri"
                    "tvri2" -> "tvrivpxx"
                    "one1" -> "one_1"
                    "one2" -> "one_2"
                    "dazn3es" -> "dazn3_spain"
                    "dazn1es" -> "dsports"
                    "xssc2" -> "ssc2"
                    else -> idVal
                }
                
                android.util.Log.d("EventProvider", "Resolving bitmovin id: $idVal")
                
                var successDrm = false
                try {
                    val workerUrl = "http://bitmovin.03anutv.workers.dev/?id=$idVal&t=${System.currentTimeMillis()}"
                    val responseText = app.get(workerUrl, timeout = 10).text
                    if (!responseText.trim().equals("CHANNEL_NOT_FOUND", ignoreCase = true)) {
                        val responseJson = JSONObject(responseText.trim())
                        val ivB64 = responseJson.optString("iv")
                        val dataB64 = responseJson.optString("data")
                        
                        if (!ivB64.isNullOrEmpty() && !dataB64.isNullOrEmpty()) {
                            val password = "xys1-gh"
                            val salt = "salt123"
                            val iterations = 1000
                            val keySize = 256
                            
                            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                            val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), iterations, keySize)
                            val tmp = factory.generateSecret(spec)
                            val secretKey = javax.crypto.spec.SecretKeySpec(tmp.encoded, "AES")
                            
                            val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
                            val combinedCipher = android.util.Base64.decode(dataB64, android.util.Base64.NO_WRAP)
                            
                            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
                            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec)
                            
                            val decryptedBytes = cipher.doFinal(combinedCipher)
                            val decryptedText = String(decryptedBytes, Charsets.UTF_8)
                            val decryptedJson = JSONObject(decryptedText)
                            
                            val dashUrl = decryptedJson.optString("dash")
                            val drmStr = decryptedJson.optString("drm")
                            
                            if (!dashUrl.isNullOrBlank() && !drmStr.isNullOrBlank()) {
                                val parts = drmStr.split(":")
                                if (parts.size == 2) {
                                    val keyId = parts[0].trim()
                                    val keyValue = parts[1].trim()
                                    
                                    android.util.Log.d("EventProvider", "Successfully decrypted DRM ClearKey: kid=$keyId key=$keyValue")
                                    
                                    val clearkeyKid = hexToBase64Url(keyId)
                                    val clearkeyKey = hexToBase64Url(keyValue)
                                    
                                    val headersMap = HashMap<String, String>()
                                    headersMap["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                    
                                    val decodedDashUrl = dashUrl.replace("%7C", "|").replace("%20", " ")
                                    val partsUrl = decodedDashUrl.split("|")
                                    if (partsUrl.size > 1) {
                                        val headerPart = partsUrl[1]
                                        for (param in headerPart.split("&")) {
                                            val pair = param.split("=")
                                            if (pair.size == 2) {
                                                val key = pair[0].trim()
                                                val value = try {
                                                    java.net.URLDecoder.decode(pair[1], "UTF-8").trim()
                                                } catch (e: Exception) {
                                                    pair[1].trim()
                                                }
                                                if (key.equals("Referer", ignoreCase = true) && value.isNotEmpty()) {
                                                    headersMap["Referer"] = value
                                                }
                                                if (key.equals("Origin", ignoreCase = true) && value.isNotEmpty()) {
                                                    headersMap["Origin"] = value
                                                }
                                            }
                                        }
                                    } else {
                                        val cleanReferer = currentTargetUrl.substringBefore("?").substringBefore("#")
                                        val cleanOrigin = if (cleanReferer.startsWith("https://")) {
                                            "https://" + cleanReferer.substringAfter("https://").substringBefore("/")
                                        } else {
                                            "https://xys1-2-player.pages.dev"
                                        }
                                        headersMap["Referer"] = cleanReferer
                                        headersMap["Origin"] = cleanOrigin
                                    }
                                    val headers = headersMap.toMap()
                                    
                                    val streamUrl = if (dashUrl.contains(".mpd", ignoreCase = true) || dashUrl.contains("mpd", ignoreCase = true)) {
                                        getDrmDashManifestUrl(dashUrl, drmStr, headers)
                                    } else {
                                        dashUrl
                                    }
                                    android.util.Log.d("EventProvider", "Invoking CallSite 1: URL=$streamUrl, KID=$clearkeyKid, KEY=$clearkeyKey, HEADERS=$headers")
                                    
                                    callback.invoke(
                                        newDrmExtractorLink(
                                            serverName,
                                            serverName,
                                            streamUrl,
                                            ExtractorLinkType.DASH,
                                            CLEARKEY_UUID
                                        ) {
                                            quality = Qualities.Unknown.value
                                            this.headers = headers
                                            kty = "oct"
                                            kid = clearkeyKid
                                            this.key = clearkeyKey
                                        }
                                    )
                                    successDrm = true
                                    return true
                                }
                            }
                        } else {
                            android.util.Log.d("EventProvider", "Response worker has empty iv or data: $responseText")
                        }
                    } else {
                        android.util.Log.d("EventProvider", "Worker returned CHANNEL_NOT_FOUND for id: $idVal")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EventProvider", "Failed to decrypt bitmovin source", e)
                }
                
                if (!successDrm) {
                    android.util.Log.d("EventProvider", "Bitmovin failed/not found. Fallback to NS Player resolver for: $idVal")
                    try {
                        val nsWorkerUrl = "http://nsplayer.pisionpluss5a.workers.dev/?id=$idVal"
                        val nsResponseText = app.get(nsWorkerUrl, timeout = 15).text
                        android.util.Log.d("EventProvider", "NS Player worker raw response: $nsResponseText")
                        if (nsResponseText.trim().isNotEmpty()) {
                            val nsJson = JSONObject(nsResponseText.trim())
                            var encryptedPayload = nsJson.optString(idVal)
                            if (encryptedPayload.isNullOrEmpty()) {
                                val keys = nsJson.keys()
                                while (keys.hasNext() && encryptedPayload.isNullOrEmpty()) {
                                    val k = keys.next()
                                    if (k.all { it.isDigit() }) {
                                        val valStr = nsJson.optString(k)
                                        if (valStr.length > 200 && (valStr.startsWith("EA0") || valStr.startsWith("DBA"))) {
                                            encryptedPayload = valStr
                                            android.util.Log.d("EventProvider", "Found dynamic numeric channel payload at key: $k")
                                        }
                                    }
                                }
                            }
                            if (!encryptedPayload.isNullOrEmpty()) {
                                val key = "xys1-gh"
                                val decodedBytes = android.util.Base64.decode(encryptedPayload, android.util.Base64.DEFAULT)
                                val decryptedBytes = ByteArray(decodedBytes.size)
                                for (i in decodedBytes.indices) {
                                    val cByte = decodedBytes[i].toInt() and 0xFF
                                    val kByte = key[i % key.length].code
                                    decryptedBytes[i] = (cByte xor kByte).toByte()
                                }
                                
                                val decryptedUrl = String(decryptedBytes, Charsets.UTF_8)
                                    .replace("|", "%7C")
                                    .replace(" ", "%20")
                                
                                android.util.Log.d("EventProvider", "NS Player XOR decrypt success: $decryptedUrl")
                                
                                val decodedUrlParam = decryptedUrl.replace("%7C", "|").replace("%20", " ")
                                if (decodedUrlParam.contains("drmScheme=clearkey", ignoreCase = true) && decodedUrlParam.contains("drmLicense=", ignoreCase = true)) {
                                    val cleanUrl = decodedUrlParam.substringBefore("|").substringBefore("&drmScheme=")
                                    val licenseParam = decodedUrlParam.substringAfter("drmLicense=").substringBefore("&")
                                    val firstPair = licenseParam.split(",").firstOrNull()?.split(":")
                                    if (firstPair != null && firstPair.size == 2) {
                                        val keyId = firstPair[0].trim()
                                        val keyValue = firstPair[1].trim()
                                        val clearkeyKid = hexToBase64Url(keyId)
                                        val clearkeyKey = hexToBase64Url(keyValue)
                                        
                                        android.util.Log.d("EventProvider", "Successfully decrypted NS Player DRM ClearKey params: $licenseParam for stream: $cleanUrl")
                                        
                                        val userAgent = extractUserAgent(decryptedUrl, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                        val headersMap = HashMap<String, String>()
                                        headersMap["User-Agent"] = userAgent
                                        
                                        val parts = decodedUrlParam.split("|")
                                        if (parts.size > 1) {
                                            val headerPart = parts[1]
                                            for (param in headerPart.split("&")) {
                                                val pair = param.split("=")
                                                if (pair.size == 2) {
                                                    val key = pair[0].trim()
                                                    val value = try {
                                                        java.net.URLDecoder.decode(pair[1], "UTF-8").trim()
                                                      } catch (e: Exception) {
                                                          pair[1].trim()
                                                      }
                                                      if (key.equals("Referer", ignoreCase = true) && value.isNotEmpty()) {
                                                          headersMap["Referer"] = value
                                                      }
                                                      if (key.equals("Origin", ignoreCase = true) && value.isNotEmpty()) {
                                                          headersMap["Origin"] = value
                                                      }
                                                  }
                                              }
                                          }
                                          val headers = headersMap.toMap()
                                         
                                         val streamUrl = if (cleanUrl.contains(".mpd", ignoreCase = true) || cleanUrl.contains("mpd", ignoreCase = true)) {
                                             getDrmDashManifestUrl(cleanUrl, licenseParam, headers)
                                         } else {
                                             cleanUrl
                                         }
                                         
                                         val isDash = cleanUrl.contains(".mpd", ignoreCase = true) || cleanUrl.contains("mpd", ignoreCase = true)
                                         val streamType = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                                         
                                         android.util.Log.d("EventProvider", "Invoking CallSite 2: URL=$streamUrl, KID=$clearkeyKid, KEY=$clearkeyKey, HEADERS=$headers")
                                         
                                         callback.invoke(
                                             newDrmExtractorLink(
                                                 serverName,
                                                 serverName,
                                                 streamUrl,
                                                 streamType,
                                                 CLEARKEY_UUID
                                             ) {
                                                 quality = Qualities.Unknown.value
                                                 this.headers = headers
                                                 kty = "oct"
                                                 kid = clearkeyKid
                                                 this.key = clearkeyKey
                                             }
                                         )
                                         successDrm = true
                                         return true
                                     }
                                 }
                                 currentTargetUrl = decryptedUrl
                             } else {
                                 android.util.Log.d("EventProvider", "NS Player payload key $idVal not found in json")
                             }
                         }
                    } catch (e: Exception) {
                        android.util.Log.e("EventProvider", "Failed to decrypt NS Player source", e)
                    }
                }
                
                if (!successDrm && currentTargetUrl.contains(".pages.dev/") && !currentTargetUrl.contains("bitmovin") && !currentTargetUrl.contains("nsplayer")) {
                    currentTargetUrl = "https://stream.netxtv.id/live/$idVal/index.m3u8"
                    android.util.Log.d("EventProvider", "Decryption failed. Defaulting to stream CDN fallback: $currentTargetUrl")
                }
            }

            android.util.Log.d("EventProvider", "Final targetUrl to stream: $currentTargetUrl")

            val isFlv = currentTargetUrl.contains(".flv", ignoreCase = true) || currentTargetUrl.contains("flv", ignoreCase = true)
            val url = if (isFlv) {
                if (!currentTargetUrl.contains("#")) "$currentTargetUrl#.flv" else currentTargetUrl
            } else if (!currentTargetUrl.contains(".m3u8", ignoreCase = true) && 
                          !currentTargetUrl.contains("m3u8", ignoreCase = true) && 
                          !currentTargetUrl.contains(".mpd", ignoreCase = true) && 
                          !currentTargetUrl.contains("mpd", ignoreCase = true) && 
                          !currentTargetUrl.contains("#") && 
                          (currentTargetUrl.contains("live.php") || currentTargetUrl.contains("play.php") || currentTargetUrl.contains("/live/"))) {
                "$currentTargetUrl#.m3u8"
            } else {
                currentTargetUrl
            }

            val isM3u8 = url.contains(".m3u8", ignoreCase = true) || url.contains("m3u8", ignoreCase = true)
            val isDash = url.contains(".mpd", ignoreCase = true) || url.contains("mpd", ignoreCase = true) || url.contains("dash", ignoreCase = true)
            val type = when {
                isDash -> ExtractorLinkType.DASH
                isM3u8 -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }

            val cleanReferer = currentTargetUrl.substringBefore("?").substringBefore("#")
            val cleanOrigin = if (cleanReferer.startsWith("https://")) {
                "https://" + cleanReferer.substringAfter("https://").substringBefore("/")
            } else {
                "https://xys1-2-player.pages.dev"
            }
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to cleanReferer,
                "Origin" to cleanOrigin
            )

            callback.invoke(
                newExtractorLink(
                    serverName,
                    serverName,
                    url,
                    type
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
