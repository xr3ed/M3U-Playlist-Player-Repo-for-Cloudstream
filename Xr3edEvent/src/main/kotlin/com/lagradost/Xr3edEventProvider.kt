package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.xr3ed.BuildConfig
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import java.net.URLDecoder
import android.content.Context
import java.net.ServerSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

object LocalManifestServer {
    private var serverSocket: ServerSocket? = null
    private var serverPort: Int = 0
    
    class ManifestMetadata(
        val originalUrl: String,
        val drmLicenseParam: String,
        val headers: Map<String, String>
    )
    private val manifestMetadatas = java.util.concurrent.ConcurrentHashMap<String, ManifestMetadata>()

    private val widevineUuid = byteArrayOf(
        0xed.toByte(), 0xef.toByte(), 0x8b.toByte(), 0xa9.toByte(),
        0x79.toByte(), 0xd6.toByte(), 0x4a.toByte(), 0xce.toByte(),
        0xa3.toByte(), 0xc8.toByte(), 0x27.toByte(), 0xdc.toByte(),
        0xd5.toByte(), 0x1d.toByte(), 0x21.toByte(), 0xed.toByte()
    )
    
    private val playreadyUuid = byteArrayOf(
        0x9a.toByte(), 0x04.toByte(), 0xf0.toByte(), 0x79.toByte(),
        0x98.toByte(), 0x40.toByte(), 0x42.toByte(), 0x86.toByte(),
        0xab.toByte(), 0x92.toByte(), 0xe6.toByte(), 0x5b.toByte(),
        0xe0.toByte(), 0x88.toByte(), 0x5f.toByte(), 0x95.toByte()
    )

    private val officialClearkeyUuid = byteArrayOf(
        0x10.toByte(), 0x77.toByte(), 0xef.toByte(), 0xec.toByte(),
        0xc0.toByte(), 0xb2.toByte(), 0x4d.toByte(), 0x02.toByte(),
        0xac.toByte(), 0xe3.toByte(), 0x3c.toByte(), 0x1e.toByte(),
        0x52.toByte(), 0xe2.toByte(), 0xfb.toByte(), 0x4b.toByte()
    )

    private fun stripAllPssh(bytes: ByteArray): ByteArray {
        val modified = bytes.clone()
        var mdatIdx = -1
        for (i in 0 until modified.size - 3) {
            if (modified[i] == 0x6d.toByte() &&     // 'm'
                modified[i+1] == 0x64.toByte() &&   // 'd'
                modified[i+2] == 0x61.toByte() &&   // 'a'
                modified[i+3] == 0x74.toByte()) {   // 't'
                mdatIdx = i
                break
            }
        }
        val limit = if (mdatIdx != -1) mdatIdx else modified.size - 3
        var psshCount = 0
        for (i in 0 until limit) {
            if (modified[i] == 0x70.toByte() &&     // 'p'
                modified[i+1] == 0x73.toByte() &&   // 's'
                modified[i+2] == 0x73.toByte() &&   // 's'
                modified[i+3] == 0x68.toByte()) {   // 'h'
                
                if (i + 23 < limit) {
                    var isWidevine = true
                    for (j in 0 until 16) {
                        if (modified[i + 8 + j] != widevineUuid[j]) { isWidevine = false; break }
                    }
                    var isPlayready = true
                    for (j in 0 until 16) {
                        if (modified[i + 8 + j] != playreadyUuid[j]) { isPlayready = false; break }
                    }
                    
                    if (isWidevine || isPlayready) {
                        modified[i] = 0x66.toByte()     // 'f'
                        modified[i+1] = 0x72.toByte()   // 'r'
                        modified[i+2] = 0x65.toByte()   // 'e'
                        modified[i+3] = 0x65.toByte()   // 'e'
                        psshCount++
                    }
                }
            }
        }
        if (psshCount > 0) {
            android.util.Log.d("EventProvider", "LocalManifestServer modified $psshCount DRM pssh box(es) to ClearKey.")
        }
        return modified
    }

    private fun injectMvex(bytes: ByteArray): ByteArray {
        var moovIdx = -1
        for (i in 0 until bytes.size - 7) {
            if (bytes[i] == 0x6d.toByte() &&     // 'm'
                bytes[i+1] == 0x6f.toByte() &&   // 'o'
                bytes[i+2] == 0x6f.toByte() &&   // 'o'
                bytes[i+3] == 0x76.toByte()) {   // 'v'
                moovIdx = i - 4
                break
            }
        }
        if (moovIdx == -1) return bytes
        
        val moovSize = ((bytes[moovIdx].toInt() and 0xFF) shl 24) or
                       ((bytes[moovIdx+1].toInt() and 0xFF) shl 16) or
                       ((bytes[moovIdx+2].toInt() and 0xFF) shl 8) or
                       (bytes[moovIdx+3].toInt() and 0xFF)
        
        var hasMvex = false
        val limit = minOf(bytes.size, moovIdx + moovSize)
        for (i in moovIdx + 8 until limit - 3) {
            if (bytes[i] == 0x6d.toByte() &&     // 'm'
                bytes[i+1] == 0x76.toByte() &&   // 'v'
                bytes[i+2] == 0x65.toByte() &&   // 'e'
                bytes[i+3] == 0x78.toByte()) {   // 'x'
                hasMvex = true
                break
            }
        }
        if (hasMvex) return bytes
        
        val mvexBytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x28, // mvex size = 40
            0x6d, 0x76, 0x65, 0x78, // 'm' 'v' 'e' 'x'
            0x00, 0x00, 0x00, 0x20, // trex size = 32
            0x74, 0x72, 0x65, 0x78, // 't' 'r' 'e' 'x'
            0x00, 0x00, 0x00, 0x00, // version & flags = 0
            0x00, 0x00, 0x00, 0x01, // track ID = 1
            0x00, 0x00, 0x00, 0x01, // default sample description index = 1
            0x00, 0x00, 0x00, 0x00, // default sample duration = 0
            0x00, 0x00, 0x00, 0x00, // default sample size = 0
            0x00, 0x00, 0x00, 0x00  // default sample flags = 0
        )
        
        val newBytes = ByteArray(bytes.size + 40)
        val insertPos = moovIdx + moovSize
        System.arraycopy(bytes, 0, newBytes, 0, insertPos)
        System.arraycopy(mvexBytes, 0, newBytes, insertPos, 40)
        if (bytes.size > insertPos) {
            System.arraycopy(bytes, insertPos, newBytes, insertPos + 40, bytes.size - insertPos)
        }
        
        val newMoovSize = moovSize + 40
        newBytes[moovIdx] = ((newMoovSize shr 24) and 0xFF).toByte()
        newBytes[moovIdx+1] = ((newMoovSize shr 16) and 0xFF).toByte()
        newBytes[moovIdx+2] = ((newMoovSize shr 8) and 0xFF).toByte()
        newBytes[moovIdx+3] = (newMoovSize and 0xFF).toByte()
        
        android.util.Log.d("EventProvider", "LocalManifestServer: Injected mvex box into moov. Size increased from ${bytes.size} to ${newBytes.size}")
        return newBytes
    }

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
                                 
                                 val isSegment = path.startsWith("/init_") || path.startsWith("/media_")
                                 val isLicense = path.startsWith("/license_")
                                 if (isSegment) {
                                      // Penanganan proxy segmen inisialisasi / media untuk stripping pssh box Widevine
                                      val prefix = if (path.startsWith("/init_")) "/init_" else "/media_"
                                      
                                      // Ekstrak query parameters
                                      val queryStr = path.substringAfter("?", "")
                                      val paramsMap = HashMap<String, String>()
                                       if (queryStr.isNotEmpty()) {
                                           val keysToExtract = listOf("rep", "bw", "base", "path", "ref", "orig", "params")
                                           for (key in keysToExtract) {
                                               val prefix1 = "$key="
                                               val prefix2 = "&$key="
                                               var valStart = -1
                                               if (queryStr.startsWith(prefix1)) {
                                                   valStart = prefix1.length
                                               } else {
                                                   val idx = queryStr.indexOf(prefix2)
                                                   if (idx != -1) {
                                                       valStart = idx + prefix2.length
                                                   }
                                               }
                                               if (valStart != -1) {
                                                   var valEnd = queryStr.length
                                                   for (otherKey in keysToExtract) {
                                                       if (otherKey == key) continue
                                                       val otherIdx = queryStr.indexOf("&$otherKey=", valStart)
                                                       if (otherIdx != -1 && otherIdx < valEnd) {
                                                           valEnd = otherIdx
                                                       }
                                                   }
                                                   val rawVal = queryStr.substring(valStart, valEnd)
                                                   paramsMap[key] = try {
                                                       java.net.URLDecoder.decode(rawVal, "UTF-8")
                                                   } catch (e: Exception) {
                                                       rawVal
                                                   }
                                               }
                                           }
                                       }
                                      
                                      val rep = paramsMap["rep"] ?: ""
                                      val time = paramsMap["time"] ?: ""
                                      val num = paramsMap["num"] ?: ""
                                      val bw = paramsMap["bw"] ?: ""
                                      val base = paramsMap["base"] ?: ""
                                      val origPath = paramsMap["path"] ?: ""
                                      val origParams = paramsMap["params"] ?: ""
                                      val ref = paramsMap["ref"] ?: ""
                                      val orig = paramsMap["orig"] ?: ""
                                      
                                      val sep = if (origPath.contains("?")) "&" else "?"
                                      var originalUrl = base + origPath + (if (origParams.isNotEmpty()) sep + origParams else "")
                                      
                                      // Susun URL asli dengan resolusi placeholder DASH pada seluruh URL
                                      originalUrl = originalUrl
                                          .replace("\$RepresentationID\$", rep)
                                          .replace("\$RepresentationID", rep)
                                          .replace("\$Time\$", time)
                                          .replace("\$Time", time)
                                          .replace("\$Number\$", num)
                                          .replace("\$Number", num)
                                          .replace("\$Bandwidth\$", bw)
                                          .replace("\$Bandwidth", bw)
                                          .replace("&amp;", "&")
                                      
                                      android.util.Log.d("EventProvider", "LocalManifestServer proxying segment: $originalUrl")
                                     
                                         val isRedbee = originalUrl.contains("redbee.live", ignoreCase = true)
                                         val headers = mutableMapOf<String, String>().apply {
                                             if (isRedbee) {
                                                 put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                                                 put("Referer", "${Xr3edEventProvider.PLAYER_BASE}/")
                                                 put("Origin", Xr3edEventProvider.PLAYER_BASE)
                                             } else {
                                                 put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                                 if (ref.isNotBlank()) put("Referer", ref)
                                                 if (orig.isNotBlank()) put("Origin", orig)
                                                 if (originalUrl.contains("secureswiftcontent.com", ignoreCase = true) || originalUrl.contains("bein", ignoreCase = true)) {
                                                     put("X-Forwarded-For", "175.139.142.25")
                                                 }
                                             }
                                         }
                                      
                                       var bytes: ByteArray? = null
                                       var isSuccess = false
                                       var responseCode = 404
                                       try {
                                           val response = kotlinx.coroutines.runBlocking {
                                               app.get(originalUrl, headers = headers.toMap(), timeout = 20)
                                           }
                                           isSuccess = response.isSuccessful
                                           responseCode = response.code
                                           if (isSuccess) {
                                               bytes = response.body.bytes()
                                           } else {
                                               android.util.Log.e("EventProvider", "Segment download failed with HTTP $responseCode: $originalUrl")
                                           }
                                       } catch (e: Exception) {
                                           android.util.Log.e("EventProvider", "Failed to download original segment: ${e.message}", e)
                                       }
                                       
                                       val clientOs = client.getOutputStream()
                                       if (isSuccess && bytes != null && bytes.isNotEmpty()) {
                                           var modifiedBytes = stripAllPssh(bytes)
                                           if (path.startsWith("/init_") || path.startsWith("/media_")) {
                                               modifiedBytes = injectMvex(modifiedBytes)
                                           }
                                           val headerString = "HTTP/1.1 200 OK\r\n" +
                                                              "Content-Type: video/mp4\r\n" +
                                                              "Content-Length: ${modifiedBytes.size}\r\n" +
                                                              "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                                                              "Pragma: no-cache\r\n" +
                                                              "Expires: 0\r\n" +
                                                              "Access-Control-Allow-Origin: *\r\n" +
                                                              "Connection: close\r\n" +
                                                              "\r\n"
                                           clientOs.write(headerString.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                           clientOs.write(modifiedBytes)
                                           clientOs.flush()
                                           android.util.Log.d("EventProvider", "LocalManifestServer successfully sent modified segment.")
                                       } else {
                                           val statusText = if (responseCode == 403) "Forbidden" else if (responseCode == 404) "Not Found" else "Error"
                                           val headerString = "HTTP/1.1 $responseCode $statusText\r\n" +
                                                              "Connection: close\r\n" +
                                                              "\r\n"
                                           clientOs.write(headerString.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                           clientOs.flush()
                                       }
                                  } else if (isLicense) {
                                      val id = path.substringAfter("/license_", "").substringBefore("?", "")
                                      val meta = manifestMetadatas[id]
                                      android.util.Log.d("EventProvider", "LocalManifestServer license requested for ID: $id, found metadata: ${meta != null}")
                                      
                                      val clientOs = client.getOutputStream()
                                      if (meta != null) {
                                          val keyPairs = if (meta.drmLicenseParam.contains(";")) meta.drmLicenseParam.split(";") else meta.drmLicenseParam.split(",")
                                          val keysJsonList = ArrayList<String>()
                                          for (pair in keyPairs) {
                                              val colonIdx = pair.indexOf(':')
                                              if (colonIdx > 0) {
                                                  val rawKid = pair.substring(0, colonIdx).trim()
                                                  val rawKey = pair.substring(colonIdx + 1).trim()
                                                  val b64Kid = hexToBase64Url(rawKid)
                                                  val b64Key = hexToBase64Url(rawKey)
                                                  keysJsonList.add("""{"kty":"oct","kid":"$b64Kid","k":"$b64Key"}""")
                                              }
                                          }
                                          val keysJson = keysJsonList.joinToString(",")
                                          val jwkSetJson = """{"keys":[$keysJson],"type":"temporary"}"""
                                          val bodyBytes = jwkSetJson.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                                          
                                          val headerString = "HTTP/1.1 200 OK\r\n" +
                                                             "Content-Type: application/json\r\n" +
                                                             "Content-Length: ${bodyBytes.size}\r\n" +
                                                              "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                                                              "Pragma: no-cache\r\n" +
                                                              "Expires: 0\r\n" +
                                                             "Access-Control-Allow-Origin: *\r\n" +
                                                             "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                                                             "Access-Control-Allow-Headers: *\r\n" +
                                                             "Connection: close\r\n" +
                                                             "\r\n"
                                          clientOs.write(headerString.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                          clientOs.write(bodyBytes)
                                          clientOs.flush()
                                          android.util.Log.d("EventProvider", "LocalManifestServer successfully sent JWK set response: $jwkSetJson")
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
                                      
                                       val clientOs = client.getOutputStream()
                                       if (meta != null) {
                                           var xml: String? = null
                                           try {
                                               val manifestHeaders = meta.headers.toMutableMap().apply {
                                                   put("Accept", "application/dash+xml,video/mpd,application/xml;q=0.9,*/*;q=0.8")
                                                   put("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
                                                   val isRedbee = meta.originalUrl.contains("redbee.live", ignoreCase = true)
                                                   if (isRedbee) {
                                                       put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                                                       put("Referer", "${Xr3edEventProvider.PLAYER_BASE}/")
                                                       put("Origin", Xr3edEventProvider.PLAYER_BASE)
                                                   } else {
                                                       if (get("Referer").isNullOrBlank()) remove("Referer")
                                                       if (get("Origin").isNullOrBlank()) remove("Origin")
                                                   }
                                               }
                                                val manifestUrl = meta.originalUrl
                                                 val response = kotlinx.coroutines.runBlocking {
                                                     app.get(manifestUrl, headers = manifestHeaders.toMap(), timeout = 25)
                                                 }
                                                 if (response.code != 200) {
                                                     android.util.Log.e("EventProvider", "LocalManifestServer: manifest fetch returned HTTP ${response.code} for URL: $manifestUrl")
                                                     throw Exception("HTTP ${response.code} from CDN")
                                                 }
                                                 val manifestXml = response.text
                                               
                                               var modifiedXml = manifestXml
                                               
                                               // Paksa tipe MPD menjadi dynamic agar dideteksi sebagai Live (menghindari batasan durasi 30 menit / 1 jam)
                                               if (modifiedXml.contains("type=\"static\"", ignoreCase = true) || modifiedXml.contains("type='static'", ignoreCase = true)) {
                                                   val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                                                       timeZone = java.util.TimeZone.getTimeZone("UTC")
                                                   }
                                                   val currentTimeUtc = sdf.format(java.util.Date())
                                                   modifiedXml = modifiedXml.replace(Regex("""type\s*=\s*["']static["']""", RegexOption.IGNORE_CASE), """type="dynamic" availabilityStartTime="$currentTimeUtc"""")
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
                                              
                                              val finalUrl = response.url
                                              android.util.Log.d("EventProvider", "LocalManifestServer original manifestUrl: $manifestUrl, response.url (finalUrl): $finalUrl")
                                              val queryParams = if (finalUrl.contains("?")) finalUrl.substringAfter("?") else ""
                                              val baseUrlString = if (finalUrl.contains("?")) finalUrl.substringBefore("?") else finalUrl
                                              val absoluteBaseUrl = baseUrlString.substringBeforeLast("/") + "/"
                                              android.util.Log.d("EventProvider", "LocalManifestServer calculated absoluteBaseUrl: $absoluteBaseUrl")
                                              
                                               val manifestBaseUrl = Regex("""<BaseURL[^>]*>([^<]+)</BaseURL>""", RegexOption.IGNORE_CASE).find(modifiedXml)?.groupValues?.get(1)?.trim()
                                               
                                               val cleanId = meta.drmLicenseParam.replace("-", "").replace(":", "").replace(",", "").trim()
                                               val refHeader = meta.headers["Referer"] ?: ""
                                               val originHeader = meta.headers["Origin"] ?: ""
                                               val rootDomain = if (absoluteBaseUrl.startsWith("https://")) {
                                                   "https://" + absoluteBaseUrl.substringAfter("https://").substringBefore("/")
                                               } else if (absoluteBaseUrl.startsWith("http://")) {
                                                   "http://" + absoluteBaseUrl.substringAfter("http://").substringBefore("/")
                                               } else {
                                                   ""
                                               }
                                               
                                               val baseToResolve = if (!manifestBaseUrl.isNullOrEmpty()) {
                                                   if (manifestBaseUrl.startsWith("http://", ignoreCase = true) || manifestBaseUrl.startsWith("https://", ignoreCase = true)) {
                                                       manifestBaseUrl
                                                   } else if (manifestBaseUrl.startsWith("/")) {
                                                       rootDomain + manifestBaseUrl
                                                   } else {
                                                       absoluteBaseUrl + manifestBaseUrl
                                                   }
                                               } else {
                                                   absoluteBaseUrl
                                               }
                                               
                                               if (!modifiedXml.contains("<BaseURL>") && !modifiedXml.contains("<BaseURL ")) {
                                                   val periodIdx = modifiedXml.indexOf("<Period")
                                                   if (periodIdx != -1) {
                                                       val insertIdx = modifiedXml.indexOf(">", periodIdx) + 1
                                                       modifiedXml = modifiedXml.substring(0, insertIdx) + "\n<BaseURL>$baseToResolve</BaseURL>\n" + modifiedXml.substring(insertIdx)
                                                   } else {
                                                       val mpdIdx = modifiedXml.indexOf("<MPD")
                                                       if (mpdIdx != -1) {
                                                           val insertIdx = modifiedXml.indexOf(">", mpdIdx) + 1
                                                           modifiedXml = modifiedXml.substring(0, insertIdx) + "\n<BaseURL>$baseToResolve</BaseURL>\n" + modifiedXml.substring(insertIdx)
                                                       }
                                                   }
                                               }
                                               
                                               
                                               
                                                     // Tulis ulang SegmentTemplate media agar diarahkan ke proxy lokal (agar kita dapat menyuntikkan mvex ke tiap media segment)
                                                     modifiedXml = modifiedXml.replace(Regex("""media=["']([^"']+)["']""", RegexOption.IGNORE_CASE)) { matchResult ->
                                                         val p1 = matchResult.groupValues[1]
                                                         val absoluteMediaUrl = if (p1.startsWith("http://", ignoreCase = true) || p1.startsWith("https://", ignoreCase = true)) {
                                                             p1
                                                         } else if (p1.startsWith("/")) {
                                                             rootDomain + p1
                                                         } else {
                                                             baseToResolve + p1
                                                         }
                                                         val encodedBase = java.net.URLEncoder.encode(baseToResolve, "UTF-8")
                                                         val encodedParams = java.net.URLEncoder.encode(queryParams, "UTF-8")
                                                         val encodedRef = java.net.URLEncoder.encode(refHeader, "UTF-8")
                                                         val encodedOrigin = java.net.URLEncoder.encode(originHeader, "UTF-8")
                                                         
                                                         """media="http://127.0.0.1:$serverPort/media_$cleanId?rep=${'$'}RepresentationID${'$'}&amp;bw=${'$'}Bandwidth${'$'}&amp;base=$encodedBase&amp;path=$p1&amp;params=$encodedParams&amp;ref=$encodedRef&amp;orig=$encodedOrigin""""
                                                     }
                                                 
                                                 // Tulis ulang SegmentTemplate initialization
                                                
                                                modifiedXml = modifiedXml.replace(Regex("""initialization=["']([^"']+)["']""", RegexOption.IGNORE_CASE)) { matchResult ->
                                                    val path = matchResult.groupValues[1]
                                                    val encodedBase = java.net.URLEncoder.encode(baseToResolve, "UTF-8")
                                                    val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                                                    val encodedParams = java.net.URLEncoder.encode(queryParams, "UTF-8")
                                                    val encodedRef = java.net.URLEncoder.encode(refHeader, "UTF-8")
                                                    val encodedOrigin = java.net.URLEncoder.encode(originHeader, "UTF-8")
                                                    
                                                    """initialization="http://127.0.0.1:$serverPort/init_$cleanId?rep=${'$'}RepresentationID${'$'}&amp;bw=${'$'}Bandwidth${'$'}&amp;base=$encodedBase&amp;path=$encodedPath&amp;params=$encodedParams&amp;ref=$encodedRef&amp;orig=$encodedOrigin""""
                                                }
                                               
                                                 // Tambahkan ClearKey DRM block dan inject cenc:default_KID yang sesuai untuk tiap AdaptationSet
                                                 val keyPairs = if (meta.drmLicenseParam.contains(";")) meta.drmLicenseParam.split(";") else meta.drmLicenseParam.split(",")
                                                 
                                                  // Hapus/strip ContentProtection Widevine & PlayReady asli agar tidak konflik dengan ClearKey
                                                  modifiedXml = modifiedXml.replace(
                                                      Regex("""<(\w+:)?ContentProtection[^>]*urn:uuid:(?:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed|9a04f079-9840-4286-ab92-e65be0885f95|79f0049a-4098-8642-ab92-e65be0885f95)[^>]*>[\s\S]*?</(?:\w+:)?ContentProtection>""", RegexOption.IGNORE_CASE),
                                                      ""
                                                  ).replace(
                                                      Regex("""<(\w+:)?ContentProtection[^>]*urn:uuid:(?:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed|9a04f079-9840-4286-ab92-e65be0885f95|79f0049a-4098-8642-ab92-e65be0885f95)[^>]*/>""", RegexOption.IGNORE_CASE),
                                                      ""
                                                  )

                                                  var adaptationIndex = 0
                                                  modifiedXml = modifiedXml.replace(Regex("""<(\w+:)?AdaptationSet([^>]*)>([\s\S]*?)</(?:\w+:)?AdaptationSet>""", RegexOption.IGNORE_CASE)) { matchResult ->
                                                      val prefix = matchResult.groups[1]?.value ?: ""
                                                      val attrs = matchResult.groups[2]?.value ?: ""
                                                      var body = matchResult.groups[3]?.value ?: ""
                                                      
                                                       val targetPair = keyPairs.firstOrNull() ?: ""
                                                       val targetKidHex = targetPair.substringBefore(":").trim()
                                                       val targetKidUuid = if (targetKidHex.length == 32) {
                                                           "${targetKidHex.substring(0, 8)}-${targetKidHex.substring(8, 12)}-${targetKidHex.substring(12, 16)}-${targetKidHex.substring(16, 20)}-${targetKidHex.substring(20)}"
                                                       } else {
                                                           targetKidHex
                                                       }
                                                       adaptationIndex++

                                                       val contentProtectionXmlBuilder = StringBuilder()
                                                       contentProtectionXmlBuilder.append("""
                                                           <ContentProtection schemeIdUri="urn:uuid:1077efec-c0b2-4d02-ace3-3c1e52e2fb4b" cenc:default_KID="$targetKidUuid" xmlns:cenc="urn:mpeg:cenc:2013"/>
                                                           <ContentProtection schemeIdUri="urn:uuid:e2513a00-7bfb-11e9-9130-0242ac110002" cenc:default_KID="$targetKidUuid" xmlns:cenc="urn:mpeg:cenc:2013"/>
                                                       """)
                                                      val contentProtectionXml = contentProtectionXmlBuilder.toString()
                                                      
                                                      if (targetKidUuid.isNotEmpty()) {
                                                          val mpdReplacement = """<ContentProtection value="cenc" schemeIdUri="urn:mpeg:dash:mp4protection:2011" cenc:default_KID="$targetKidUuid" xmlns:cenc="urn:mpeg:cenc:2013"/>"""
                                                          body = body.replace(
                                                              Regex("""<(\w+:)?ContentProtection\s+value="cenc"\s+schemeIdUri="urn:mpeg:dash:mp4protection:2011"\s*/?>""", RegexOption.IGNORE_CASE),
                                                              mpdReplacement
                                                          ).replace(
                                                              Regex("""<(\w+:)?ContentProtection\s+schemeIdUri="urn:mpeg:dash:mp4protection:2011"\s+value="cenc"\s*/?>""", RegexOption.IGNORE_CASE),
                                                              mpdReplacement
                                                          ).replace(
                                                              Regex("""<(\w+:)?ContentProtection[^>]*urn:mpeg:dash:mp4protection:2011[^>]*>[\s\S]*?</(?:\w+:)?ContentProtection>""", RegexOption.IGNORE_CASE),
                                                              mpdReplacement
                                                          )
                                                      }
                                                      
                                                      "<${prefix}AdaptationSet${attrs}>\n${contentProtectionXml}\n${body}</${prefix}AdaptationSet>"
                                                  }
                                                  xml = modifiedXml
                                           } catch (e: Exception) {
                                               android.util.Log.e("EventProvider", "LocalManifestServer dynamically fetch manifest error", e)
                                           }
                                           
                                           if (xml != null) {
                                               val bytes = xml.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                                               val headerString = "HTTP/1.1 200 OK\r\n" +
                                                                  "Content-Type: application/dash+xml; charset=utf-8\r\n" +
                                                                  "Content-Length: ${bytes.size}\r\n" +
                                                                   "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                                                                   "Pragma: no-cache\r\n" +
                                                                   "Expires: 0\r\n" +
                                                                  "Access-Control-Allow-Origin: *\r\n" +
                                                                  "Connection: close\r\n" +
                                                                  "\r\n"
                                               clientOs.write(headerString.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                               clientOs.write(bytes)
                                               clientOs.flush()
                                               android.util.Log.d("EventProvider", "LocalManifestServer dynamic HTTP 200 OK sent for ID: $id")
                                           } else {
                                               val headerString = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
                                               clientOs.write(headerString.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                               clientOs.flush()
                                           }
                                       } else {
                                           val headerString = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                                           clientOs.write(headerString.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                           clientOs.flush()
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

fun hexToBase64Url(str: String): String {
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

fun buildClearKeyInjection(licenseParam: String): Pair<String, String> {
    val pairs = if (licenseParam.contains(";")) licenseParam.split(";") else licenseParam.split(",")
    if (pairs.isEmpty()) return Pair("", "")
    
    val firstPair = pairs[0].split(":")
    if (firstPair.size != 2) return Pair("", "")
    val firstKid = hexToBase64Url(firstPair[0].trim())
    val firstKey = hexToBase64Url(firstPair[1].trim())
    
    return Pair(firstKid, firstKey)
}

class Xr3edEventProvider(val context: Context) : MainAPI() {
    // Domain dari BuildConfig — diisi via GitHub Secrets (CI) atau local.properties (lokal)
    override var mainUrl = BuildConfig.XR3EV_MAIN_URL
    override var name = "xr3ed event"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage: List<MainPageData>
        get() = listOf(
            MainPageData(dynamicMainPageTitle, "worldcup")
        )

    private val defaultLogo = "https://cdn.jsdelivr.net/gh/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream@main/world_cup_cover.png"

    companion object {
        const val OFFLINE_POSTER_URL = "https://cdn.jsdelivr.net/gh/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream@main/assets/channel_offline.png"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 menit
        val channelStatusCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        @Volatile var cacheTimestamp = 0L
        @Volatile var dynamicMainPageTitle = "World Cup 2026"

        // URL dari BuildConfig — diisi via GitHub Secrets (CI) atau local.properties (lokal)
        val API_BASE get() = BuildConfig.XR3EV_API_URL
        val PLAYER_BASE get() = BuildConfig.XR3EV_PLAYER_URL
        val STREAM_BASE get() = BuildConfig.XR3EV_STREAM_URL
        val cleanClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }

        private fun dec(b64: String): String {
            return String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }
        val WORKER_BASE get() = dec("aHR0cHM6Ly9iaXRtb3Zpbi4wM2FudXR2LndvcmtlcnMuZGV2")
        val NS_WORKER_BASE get() = dec("aHR0cDovL25zcGxheWVyLnBpc2lvbnBsdXNzNWEud29ya2Vycy5kZXY=")
    }

    // Remap worker ID — harus sinkron dengan remap di resolveSingleChannel
    private fun remapWorkerIdForCheck(id: String): String {
        var cleanId = id
        val prefixes = listOf("xbein", "xtsn", "xvidx", "xdazn", "xfs", "xssc", "xone", "xvpl")
        for (pref in prefixes) {
            if (cleanId.startsWith(pref, ignoreCase = true)) {
                cleanId = cleanId.substring(1)
                break
            }
        }
        if (cleanId.endsWith("myx", ignoreCase = true)) {
            cleanId = cleanId.substring(0, cleanId.length - 1)
        }
        return mapOf(
            "vpl6" to "tvrivp", "vpl8" to "tvri", "vpl7" to "vpl7", "tvri2" to "tvrivpxx",
            "one1" to "one_1", "one2" to "one_2",
            "dazn3es" to "dazn3_spain", "dazn1es" to "dazn1_spain", "dazn2es" to "dazn2_spain", "xssc2" to "ssc2"
        )[cleanId] ?: cleanId
    }

    // Dekripsi response dari bitmovin worker (AES-GCM)
    private fun decryptWorkerPayload(responseText: String): JSONObject? {
        return try {
            val responseJson = JSONObject(responseText.trim())
            val ivB64 = responseJson.optString("iv")
            val dataB64 = responseJson.optString("data")
            if (ivB64.isNullOrEmpty() || dataB64.isNullOrEmpty()) return null
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = javax.crypto.spec.PBEKeySpec("xys1-gh".toCharArray(), "salt123".toByteArray(), 1000, 256)
            val secretKey = javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
            val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
            val combined = android.util.Base64.decode(dataB64, android.util.Base64.NO_WRAP)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(combined), Charsets.UTF_8))
        } catch (e: Exception) { null }
    }

    // Cek apakah channel masih aktif: worker check + HTTP fetch manifest
    private suspend fun isChannelAlive(channelId: String, chName: String, href: String): Boolean {
        val nameLower = chName.lowercase()
        if (nameLower.contains("(vpn") || nameLower.contains("vpn jerman") || nameLower.contains("vpn usa")) {
            return false
        }
        return try {
            val resolvedId = if (href.startsWith("go:")) href.substring(3) else channelId
            val mappedId = remapWorkerIdForCheck(resolvedId)
            val workerUrl = "${Xr3edEventProvider.WORKER_BASE}/?id=$mappedId&t=${System.currentTimeMillis()}"
            val responseText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val req = okhttp3.Request.Builder()
                        .url(workerUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()
                    cleanClient.newCall(req).execute().body?.string() ?: ""
                } catch (e: Exception) { "" }
            }
            if (responseText.trim().equals("CHANNEL_NOT_FOUND", ignoreCase = true)) return false
            val decrypted = decryptWorkerPayload(responseText) ?: return false
            var dashUrl = if (decrypted.has("dash")) decrypted.optString("dash") else decrypted.optString("hls")
            if (dashUrl.isNullOrBlank()) return false
            
            // Bersihkan URL AIV jika di-proxy oleh workers.dev / pages.dev
            val aivRegex = Regex("""https://[^/]+\.(?:workers\.dev|pages\.dev)/[^/]+/((?:pdx-nitro|lhr-nitro)/.*)""", RegexOption.IGNORE_CASE)
            val aivMatch = aivRegex.find(dashUrl)
            if (aivMatch != null) {
                dashUrl = "https://otte.cache.aiv-cdn.net/" + aivMatch.groupValues[1]
            }
            
            // Layer 3 check: HTTP GET to verify stream accessibility
            val cleanUrl = dashUrl.split("|")[0].trim()
            val isM3u8 = cleanUrl.contains(".m3u8", ignoreCase = true) || cleanUrl.contains("m3u8", ignoreCase = true)
            val isRedbee = cleanUrl.contains("redbee.live", ignoreCase = true)
            val isMewatch = cleanUrl.contains("lion.hbx4.workers.dev", ignoreCase = true) || cleanUrl.contains("mewatch", ignoreCase = true)
            val isHbx4 = cleanUrl.contains("hbx4.workers.dev", ignoreCase = true) && !cleanUrl.contains("lion.hbx4.workers.dev", ignoreCase = true)
            
            val headers = mutableMapOf<String, String>().apply {
                if (isRedbee) {
                    put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    put("Referer", "${Xr3edEventProvider.PLAYER_BASE}/")
                    put("Origin", Xr3edEventProvider.PLAYER_BASE)
                } else {
                    put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    if (isMewatch) {
                        put("Referer", "https://www.mewatch.sg/")
                    } else if (isHbx4) {
                        put("Referer", "${Xr3edEventProvider.PLAYER_BASE}/")
                        put("Origin", Xr3edEventProvider.PLAYER_BASE)
                    }
                }
            }.toMap()
            
            try {
                val response = app.get(cleanUrl, headers = headers, timeout = 5)
                if (response.code != 200) return false
                val body = response.text
                if (body.isNullOrBlank() || body.length < 200) return false
                if (isM3u8) {
                    body.contains("#EXTM3U", ignoreCase = true)
                } else {
                    body.contains("<MPD", ignoreCase = true) || body.contains("<?xml", ignoreCase = true)
                }
            } catch(e: Exception) {
                true
            }
        } catch (e: Exception) {
            android.util.Log.w("EventProvider", "isChannelAlive check failed for $channelId: ${e.message}")
            true // Kalau error timeout/network di worker, anggap alive (jangan salah matikan)
        }
    }

    // Cek semua channel secara paralel, hasil di-cache 5 menit
    private suspend fun checkAllChannelsParallel(
        channelList: List<Triple<String, String, String>>,
        chIdToHref: Map<String, String>   // chId -> href asli dari channel.js (e.g. "go:bein1my")
    ) {
        val now = System.currentTimeMillis()
        if (now - cacheTimestamp < CACHE_TTL_MS && channelStatusCache.isNotEmpty()) {
            android.util.Log.d("EventProvider", "Channel status cache masih valid (${(now - cacheTimestamp) / 1000}s), skip check")
            return
        }
        android.util.Log.d("EventProvider", "Mulai cek ${channelList.size} channel secara paralel...")
        try {
            supervisorScope {
                val jobs = channelList.map { (chId, chName, _) ->
                    async {
                        // Gunakan href ASLI dari channel.js, bukan dari streamUrl!
                        // Contoh: fusball -> "go:bein1my", bukan "go:fusball"
                        val href = chIdToHref[chId] ?: ""
                        val alive = isChannelAlive(chId, chName, href)
                        channelStatusCache[chId] = alive
                        android.util.Log.d("EventProvider", "Channel $chId href=$href (${if (alive) "ALIVE" else "DEAD"})")
                    }
                }
                jobs.awaitAll()
            }
        } catch (e: Exception) {
            android.util.Log.e("EventProvider", "checkAllChannelsParallel error", e)
        }
        cacheTimestamp = System.currentTimeMillis()
        val deadCount = channelStatusCache.values.count { !it }
        android.util.Log.d("EventProvider", "Channel check selesai: ${channelList.size - deadCount} alive, $deadCount dead")
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
        val homePageLists = ArrayList<HomePageList>()
        var categoryTitle = "WORLD CUP 2026"
        try {
            // 1. Ambil channel.js dan menu.js dari Pages server
            val jsUrl = "${Xr3edEventProvider.API_BASE}/netxtv/channel.js?t=${System.currentTimeMillis()}"
            val responseChannel = app.get(jsUrl, timeout = 15).text
            val jsonChannelStr = if (responseChannel.contains("---")) responseChannel.substringAfter("---").trim() else responseChannel.trim()
            val rootChannel = JSONObject(jsonChannelStr)
            val channelsObj = rootChannel.optJSONObject("channels") ?: JSONObject()
            val groupsObj = rootChannel.optJSONObject("groups") ?: JSONObject()

            val menuUrl = "${Xr3edEventProvider.API_BASE}/netxtv/menu.js?t=${System.currentTimeMillis()}"
            val responseMenu = app.get(menuUrl, timeout = 15).text
            val jsonMenuStr = if (responseMenu.contains("---")) responseMenu.substringAfter("---").trim() else responseMenu.trim()
            val rootMenu = JSONObject(jsonMenuStr)
            val menuChannelsObj = rootMenu.optJSONObject("channels") ?: JSONObject()
            val menuGroupsObj = rootMenu.optJSONObject("groups") ?: JSONObject()

            // 2. Ambil jadwal.js untuk live/upcoming matches
            val jadwalUrl = "${Xr3edEventProvider.API_BASE}/netxtv/jadwal.js?t=${System.currentTimeMillis()}"
            val response = app.get(jadwalUrl, timeout = 15).text
            val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
            val root = JSONObject(jsonStr)
            val scheduleArray = root.optJSONArray("schedule")
            val worldCupGroup = scheduleArray?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
                    .firstOrNull { g ->
                        val title = g.optString("title", "")
                        title.contains("WORLD CUP", ignoreCase = true) || title.contains("FIFA", ignoreCase = true)
                    }
            }

            val groupLink = worldCupGroup?.optString("link", "go:ucl") ?: "go:ucl"

            val uniqueChannelsToCheck = mutableSetOf<String>()
            val chIdToHref = mutableMapOf<String, String>()

            val cleanGroupId = if (groupLink.startsWith("go:")) groupLink.substringAfter("go:") else groupLink
            val targetGroupId = when {
                groupsObj.has(cleanGroupId + "1") -> cleanGroupId + "1"
                groupsObj.has(cleanGroupId) -> cleanGroupId
                else -> cleanGroupId
            }

            val targetGroups = mutableListOf<String>()
            val arr = groupsObj.optJSONArray(targetGroupId)
            if (arr != null) {
                targetGroups.add(targetGroupId)
            } else {
                val normGroupId = cleanGroupId.lowercase().replace("l", "1")
                val keys = groupsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val normKey = k.lowercase().replace("l", "1")
                    if (normKey.startsWith(normGroupId) || normKey.contains(normGroupId)) {
                        targetGroups.add(k)
                    }
                }
            }

            val thisGroupChannels = ArrayList<Triple<String, String, String>>()
            val addedChannels = mutableSetOf<String>()
            for (gId in targetGroups) {
                val groupArr = groupsObj.optJSONArray(gId) ?: continue
                for (k in 0 until groupArr.length()) {
                    val chId = groupArr.optString(k)
                    if (chId.equals("wc-jadwal", ignoreCase = true) || chId.equals("vvip", ignoreCase = true) || chId.equals("chat", ignoreCase = true) || chId.equals("replay", ignoreCase = true)) continue
                    if (addedChannels.contains(chId)) continue
                    addedChannels.add(chId)

                    val chData = channelsObj.optJSONObject(chId) ?: continue
                    val chName = chData.optString("name", chId)
                    val chHref = chData.optString("href", "")
                    val streamUrl = "${mainUrl}/?id=jadwal#go:$chId"

                    thisGroupChannels.add(Triple(chId, chName, streamUrl))
                    uniqueChannelsToCheck.add(chId)
                    chIdToHref[chId] = chHref
                }
            }

            if (thisGroupChannels.isNotEmpty()) {
                val channelsListToCheck = uniqueChannelsToCheck.map { chId ->
                    val chData = channelsObj.optJSONObject(chId)
                    val chName = chData?.optString("name", chId) ?: chId
                    val streamUrl = "${mainUrl}/?id=jadwal#go:$chId"
                    Triple(chId, chName, streamUrl)
                }
                checkAllChannelsParallel(channelsListToCheck, chIdToHref)

                val sortedGroupChannels = thisGroupChannels.sortedWith(compareBy<Triple<String, String, String>> { ch ->
                    // 1. Online first (0), offline last (1)
                    val isAlive = channelStatusCache[ch.first] != false
                    if (isAlive) 0 else 1
                }.thenBy { ch ->
                    // 2. TVRI / TVRI+ absolute priority (0 for TVRI, 1 for others)
                    val chIdLower = ch.first.lowercase()
                    val chNameLower = ch.second.lowercase()
                    val isTVRI = chIdLower.contains("tvri") || chNameLower.contains("tvri")
                    if (isTVRI) 0 else 1
                }.thenBy { ch ->
                    // 3. Language / priority groups
                    val chIdLower = ch.first.lowercase()
                    val chNameLower = ch.second.lowercase()
                    
                    val hasUS = chIdLower.split(" ", "-", "_").contains("us") || chNameLower.split(" ", "-", "_").contains("us")
                    val hasUK = chIdLower.split(" ", "-", "_").contains("uk") || chNameLower.split(" ", "-", "_").contains("uk")

                    val isEnglish = chIdLower.contains("eng") || chIdLower.contains("english") ||
                                    hasUS || hasUK ||
                                    chIdLower.contains("dazn") || chIdLower.contains("bein") ||
                                    chIdLower.contains("sky") || chIdLower.contains("optus") ||
                                    chIdLower.contains("supersport") || chIdLower.contains("tsn") ||
                                    chIdLower.contains("espn") || chIdLower.contains("fox") ||
                                    chIdLower.contains("astro") || chIdLower.contains("hub") ||
                                    chIdLower.contains("premier") || chIdLower.contains("wctv") ||
                                    chIdLower.contains("ppv") || chIdLower.contains("sbs") ||
                                    chNameLower.contains("eng") || chNameLower.contains("english") ||
                                    chNameLower.contains("dazn") || chNameLower.contains("bein") ||
                                    chNameLower.contains("sky") || chNameLower.contains("optus") ||
                                    chNameLower.contains("supersport") || chNameLower.contains("tsn") ||
                                    chNameLower.contains("espn") || chNameLower.contains("fox") ||
                                    chNameLower.contains("astro") || chNameLower.contains("hub") ||
                                    chNameLower.contains("premier") || chNameLower.contains("wctv") ||
                                    chNameLower.contains("ppv") || chNameLower.contains("sbs")
                                    
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

                    val isGerman = chIdLower.contains("fusball") || chIdLower.contains("fussball") ||
                                   chNameLower.contains("fusball") || chNameLower.contains("fussball") ||
                                   chIdLower.contains("jerman") || chIdLower.contains("jerman") ||
                                   chIdLower.contains("german") || chIdLower.contains("german")

                    val isKAN = chIdLower.contains("kan11") || chIdLower.contains("kan 11") ||
                                chNameLower.contains("kan 11") || chNameLower.contains("kan11")

                    when {
                        isKAN     -> 99
                        isEnglish -> 2
                        isLokal   -> 3
                        isGerman  -> 4
                        else      -> 5
                    }
                }.thenBy { it.second })

                val channelsList = ArrayList<SearchResponse>()
                for (ch in sortedGroupChannels) {
                    val isAlive = channelStatusCache[ch.first] != false
                    val poster = if (isAlive) defaultLogo else OFFLINE_POSTER_URL
                    val displayName = if (isAlive) ch.second else "⚠️ ${ch.second} (OFFLINE)"
                    
                    channelsList.add(
                        newLiveSearchResponse(
                            displayName,
                            ch.third,
                            TvType.Live
                        ) {
                            this.posterUrl = poster
                        }
                    )
                }
                if (channelsList.isNotEmpty()) {
                    homePageLists.add(HomePageList("WORLD CUP 2026", channelsList))
                    categoryTitle = "WORLD CUP 2026"
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (homePageLists.isEmpty()) {
            categoryTitle = "WORLD CUP 2026"
            val emptyList = ArrayList<SearchResponse>()
            emptyList.add(
                newLiveSearchResponse(
                    "Sedang tidak ada pertandingan piala dunia aktif saat ini",
                    "${mainUrl}/?id=jadwal#none",
                    TvType.Live
                ) {
                    this.posterUrl = defaultLogo
                }
            )
            homePageLists.add(HomePageList(categoryTitle, emptyList))
        }

        dynamicMainPageTitle = categoryTitle

        return newHomePageResponse(
            homePageLists,
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val list = ArrayList<SearchResponse>()
        try {
            val jsUrl = "${Xr3edEventProvider.API_BASE}/netxtv/channel.js?t=${System.currentTimeMillis()}"
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
                if (chId.equals("wc-jadwal", ignoreCase = true) || chId.equals("vvip", ignoreCase = true) || chId.equals("chat", ignoreCase = true) || chId.equals("replay", ignoreCase = true)) continue
                val ch = channelsObj.optJSONObject(chId) ?: continue
                val chName = ch.optString("name", chId)
                val chImg = ch.optString("img", defaultLogo)
                val chHref = ch.optString("href")

                if (chHref.isNullOrBlank()) continue
                if (!chName.contains(query, ignoreCase = true)) continue

                val streamUrl = if (chHref.startsWith("go:")) {
                    "${Xr3edEventProvider.PLAYER_BASE}/?id=jadwal#$chHref"
                } else {
                    "${Xr3edEventProvider.PLAYER_BASE}/?id=jadwal#go:$chId"
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
                
                var chName = code
                try {
                    val jsUrl = "${Xr3edEventProvider.API_BASE}/netxtv/channel.js?t=${System.currentTimeMillis()}"
                    val response = app.get(jsUrl, timeout = 10).text
                    val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
                    val root = JSONObject(jsonStr)
                    val channelsObj = root.optJSONObject("channels") ?: JSONObject()
                    val chData = channelsObj.optJSONObject(code)
                    if (chData != null) {
                        chName = chData.optString("name", code)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                title = chName
                val linkUrl = "${mainUrl}/?id=jadwal#go:$code"
                return newLiveStreamLoadResponse(
                    title,
                    linkUrl,
                    linkUrl
                ) {
                    this.posterUrl = defaultLogo
                    this.plot = "Saluran ini 100% GRATIS. Jika Anda membayar atau membeli untuk mendapatkan akses ini, maka Anda telah DITIPU! Ketuk tombol putar di bawah untuk memulai siaran secara instan."
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
                val jsUrl = "${Xr3edEventProvider.API_BASE}/netxtv/channel.js?t=${System.currentTimeMillis()}"
                val response = app.get(jsUrl, timeout = 15).text
                val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
                val root = JSONObject(jsonStr)
                val channelsObj = root.optJSONObject("channels") ?: JSONObject()
                val groupsObj = root.optJSONObject("groups") ?: JSONObject()
                
                val normGroupId = groupId.lowercase().replace("l", "1")
                val targetGroups = mutableListOf<String>()
                val keys = groupsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val normKey = k.lowercase().replace("l", "1")
                    if (normKey.startsWith(normGroupId)) {
                        targetGroups.add(k)
                    }
                }
                if (targetGroups.isEmpty()) {
                    val keys2 = groupsObj.keys()
                    while (keys2.hasNext()) {
                        val k = keys2.next()
                        val normKey = k.lowercase().replace("l", "1")
                        if (normKey.contains(normGroupId)) {
                            targetGroups.add(k)
                        }
                    }
                }
                
                val addedChannels = mutableSetOf<String>()
                var resolvedAny = false
                
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
                        val chHref = chData.optString("href") ?: ""
                        
                        val linkUrl = if (chHref.startsWith("go:")) {
                            "${Xr3edEventProvider.PLAYER_BASE}/bitmovin/?id=${chHref.substringAfter("go:")}"
                        } else if (chHref.isNotEmpty()) {
                            chHref
                        } else {
                            "${Xr3edEventProvider.PLAYER_BASE}/bitmovin/?id=$chId"
                        }
                        collectedChannels.add(Triple(chId, chName, linkUrl))
                    }
                }

                val sortedChannels = collectedChannels.sortedWith(compareBy<Triple<String, String, String>> {
                    val chIdLower = it.first.lowercase()
                    val chNameLower = it.second.lowercase()
                    
                    val hasUS = chIdLower.split(" ", "-", "_").contains("us") || chNameLower.split(" ", "-", "_").contains("us")
                    val hasUK = chIdLower.split(" ", "-", "_").contains("uk") || chNameLower.split(" ", "-", "_").contains("uk")

                    val isEnglish = chIdLower.contains("eng") || chIdLower.contains("english") ||
                                    hasUS || hasUK ||
                                    chIdLower.contains("dazn") || chIdLower.contains("bein") ||
                                    chIdLower.contains("sky") || chIdLower.contains("optus") ||
                                    chIdLower.contains("supersport") || chIdLower.contains("tsn") ||
                                    chIdLower.contains("espn") || chIdLower.contains("fox") ||
                                    chIdLower.contains("astro") || chIdLower.contains("hub") ||
                                    chIdLower.contains("premier") || chIdLower.contains("wctv") ||
                                    chIdLower.contains("ppv") || chIdLower.contains("vvip") ||
                                    chNameLower.contains("eng") || chNameLower.contains("english") ||
                                    chNameLower.contains("dazn") || chNameLower.contains("bein") ||
                                    chNameLower.contains("sky") || chNameLower.contains("optus") ||
                                    chNameLower.contains("supersport") || chNameLower.contains("tsn") ||
                                    chNameLower.contains("espn") || chNameLower.contains("fox") ||
                                    chNameLower.contains("astro") || chNameLower.contains("hub") ||
                                    chNameLower.contains("premier") || chNameLower.contains("wctv") ||
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

                    val isGerman = chIdLower.contains("fusball") || chIdLower.contains("fussball") ||
                                   chNameLower.contains("fusball") || chNameLower.contains("fussball") ||
                                   chIdLower.contains("jerman") || chIdLower.contains("jerman") ||
                                   chIdLower.contains("german") || chIdLower.contains("german")
                    
                    val isTVRI = chIdLower.contains("tvri") ||
                                 chNameLower.contains("tvri")

                    val isKAN = chIdLower.contains("kan11") || chIdLower.contains("kan 11") ||
                                chNameLower.contains("kan 11") || chNameLower.contains("kan11")

                    when {
                        isKAN     -> 99 // KAN 11 (Israel) paling belakang (evaluasi duluan agar bypass isLokal)
                        isTVRI    -> 0  // TVRI & TVRI+ paling depan
                        isEnglish -> 2
                        isLokal   -> 3
                        isGerman  -> 4
                        else      -> 5
                    }
                }.thenBy { it.second })

                resolvedAny = false
                for (ch in sortedChannels) {
                    val success = resolveSingleChannel(ch.third, ch.second, callback)
                    if (success) {
                        resolvedAny = true
                    }
                }
                return resolvedAny
            } else {
                var targetUrl = ""
                var chName = "Server"
                if (data.contains("#go:")) {
                    var code = data.substringAfter("#go:").substringBefore("#").substringBefore("&")
                    if (code.contains("?")) {
                        code = code.substringBefore("?")
                    }
                    val jsUrl = "${Xr3edEventProvider.API_BASE}/netxtv/channel.js?t=${System.currentTimeMillis()}"
                    val response = app.get(jsUrl, timeout = 15).text
                    val jsonStr = if (response.contains("---")) response.substringAfter("---").trim() else response.trim()
                    val root = JSONObject(jsonStr)
                    val channelsObj = root.optJSONObject("channels") ?: JSONObject()
                    
                    var resolved = false
                    var depth = 0
                    var tempCode = code
                    while (!resolved && depth < 5) {
                        val channelData = channelsObj.optJSONObject(tempCode)
                        if (channelData != null) {
                            chName = channelData.optString("name", "Server")
                            val href = channelData.optString("href")
                            if (!href.isNullOrBlank()) {
                                 if (href.startsWith("go:")) {
                                      var nextCode = href.substringAfter("go:").substringBefore("#").substringBefore("&")
                                      if (nextCode.contains("?")) {
                                          nextCode = nextCode.substringBefore("?")
                                      }
                                      if (nextCode == tempCode) {
                                          resolved = true
                                      } else {
                                          tempCode = nextCode
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
                            resolved = true
                        }
                    }
                    
                    val groupsObj = root.optJSONObject("groups") ?: JSONObject()
                    val groupArr = groupsObj.optJSONArray(tempCode)
                    if (groupArr != null) {
                        val addedChannels = mutableSetOf<String>()
                        val collectedChannels = mutableListOf<Triple<String, String, String>>()
                        for (i in 0 until groupArr.length()) {
                            val chId = groupArr.optString(i)
                            if (chId.equals("vvip", ignoreCase = true) || chId.equals("replay", ignoreCase = true) || chId.equals("wc-jadwal", ignoreCase = true)) continue
                            if (addedChannels.contains(chId)) continue
                            addedChannels.add(chId)
                            
                            val chData = channelsObj.optJSONObject(chId) ?: continue
                            val chNameLoc = chData.optString("name", chId)
                            val chHref = chData.optString("href") ?: ""
                            
                            val linkUrl = if (chHref.startsWith("go:")) {
                                "${Xr3edEventProvider.PLAYER_BASE}/bitmovin/?id=${chHref.substringAfter("go:")}"
                            } else if (chHref.isNotEmpty()) {
                                chHref
                            } else {
                                "${Xr3edEventProvider.PLAYER_BASE}/bitmovin/?id=$chId"
                            }
                            collectedChannels.add(Triple(chId, chNameLoc, linkUrl))
                        }
                        
                        var resolvedAny = false
                        for (ch in collectedChannels) {
                            val success = resolveSingleChannel(ch.third, ch.second, callback)
                            if (success) {
                                resolvedAny = true
                            }
                        }
                        if (resolvedAny) return true
                    }
                    
                    if (targetUrl.isEmpty()) {
                        try {
                            val ifmUrl = "${Xr3edEventProvider.API_BASE}/web/ifm.js?t=${System.currentTimeMillis()}"
                            val ifmResponse = app.get(ifmUrl, timeout = 10, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")).text
                            val ifmJson = JSONObject(ifmResponse.trim())
                            if (ifmJson.has(tempCode)) {
                                val mapping = ifmJson.getString(tempCode)
                                targetUrl = if (mapping.startsWith("/")) {
                                    "${Xr3edEventProvider.PLAYER_BASE}$mapping"
                                } else {
                                    mapping
                                }
                                android.util.Log.d("EventProvider", "Resolved player mapping from ifm.js: $tempCode -> $targetUrl")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("EventProvider", "Failed to fetch/parse ifm.js mapping", e)
                        }
                    }
                    
                    if (targetUrl.isEmpty()) {
                        targetUrl = "${Xr3edEventProvider.PLAYER_BASE}/bitmovin/?id=$tempCode"
                    }
                } else {
                    targetUrl = data
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
                    "dazn3es" -> "dazn3_spainx"
                    "dazn1es" -> "dazn1_spainx"
                    "dazn2es" -> "dazn2_spainx"
                    "xdazn2es" -> "dazn2_spainx"
                    "xbein1my" -> "ssc_extra2"
                    "bein1my" -> "bein1myx"
                    "bein2my" -> "bein2myx"
                    "bein3my" -> "bein3myx"
                    "xssc2" -> "ssc2"
                    "fox1mx" -> "fox1mx"
                    "fox2mx" -> "fox2mx"
                    "fox3mx" -> "fox3mx"
                    "foxpremmx" -> "foxpremmx"
                    else -> idVal
                }
                
                var cleanId = idVal
                val prefixes = listOf("xbein", "xtsn", "xvidx", "xdazn", "xfs", "xssc", "xone", "xvpl")
                for (pref in prefixes) {
                    if (cleanId.startsWith(pref, ignoreCase = true)) {
                        cleanId = cleanId.substring(1)
                        break
                    }
                }
                if (cleanId.endsWith("myx", ignoreCase = true)) {
                    cleanId = cleanId.substring(0, cleanId.length - 1)
                }

                android.util.Log.d("EventProvider", "Resolving bitmovin id: $cleanId")
                
                var successDrm = false
                if (true) {
                    try {
                        val workerUrl = "${Xr3edEventProvider.WORKER_BASE}/?id=$cleanId&t=${System.currentTimeMillis()}"
                        val responseText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val req = okhttp3.Request.Builder()
                                    .url(workerUrl)
                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                    .build()
                                cleanClient.newCall(req).execute().body?.string() ?: ""
                            } catch (e: Exception) { "" }
                        }
                        if (!responseText.trim().equals("CHANNEL_NOT_FOUND", ignoreCase = true) && !responseText.contains("live/media0", ignoreCase = true)) {
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
                            var streamUrl = if (decryptedJson.has("dash")) decryptedJson.optString("dash") else decryptedJson.optString("hls")
                            
                            // Bersihkan URL AIV jika di-proxy oleh workers.dev / pages.dev
                            val aivRegex = Regex("""https://[^/]+\.(?:workers\.dev|pages\.dev)/[^/]+/((?:pdx-nitro|lhr-nitro)/.*)""", RegexOption.IGNORE_CASE)
                            val aivMatch = aivRegex.find(streamUrl)
                            if (aivMatch != null) {
                                val cleanedUrl = "https://otte.cache.aiv-cdn.net/" + aivMatch.groupValues[1]
                                android.util.Log.d("EventProvider", "Cleaned AIV URL from worker: original=$streamUrl -> cleaned=$cleanedUrl")
                                streamUrl = cleanedUrl
                            }
                            
                            val drmStr = decryptedJson.optString("drm")
                             
                            if (!streamUrl.isNullOrBlank()) {
                                 val headersMap = HashMap<String, String>()
                                 headersMap["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                 if (streamUrl.contains("secureswiftcontent.com", ignoreCase = true) || idVal.contains("bein", ignoreCase = true)) {
                                     headersMap["X-Forwarded-For"] = "175.139.142.25"
                                 }
                                 
                                 val decodedStreamUrl = streamUrl.replace("%7C", "|").replace("%20", " ")
                                val partsUrl = decodedStreamUrl.split("|")
                                if (partsUrl.size > 1) {
                                    val headerPart = partsUrl[1]
                                    for (param in headerPart.split("&")) {
                                        val eqIdx = param.indexOf('=')
                                        if (eqIdx > 0) {
                                            val hKey = param.substring(0, eqIdx).trim()
                                            val hVal = try {
                                                java.net.URLDecoder.decode(param.substring(eqIdx + 1), "UTF-8").trim()
                                            } catch (e: Exception) {
                                                param.substring(eqIdx + 1).trim()
                                            }
                                            if (hKey.equals("Referer", ignoreCase = true) && hVal.isNotEmpty()) {
                                                headersMap["Referer"] = hVal
                                            }
                                            if (hKey.equals("Origin", ignoreCase = true) && hVal.isNotEmpty()) {
                                                headersMap["Origin"] = hVal
                                            }
                                        }
                                    }
                                }
                                
                                val cleanDashUrl = streamUrl.split("|")[0].trim()
                                val cleanUrlParts = cleanDashUrl.split("?")
                                var extraRef = ""
                                var extraOrig = ""
                                if (cleanUrlParts.size > 1) {
                                    for (param in cleanUrlParts[1].split("&")) {
                                        val eqIdx = param.indexOf('=')
                                        if (eqIdx > 0) {
                                            val key = param.substring(0, eqIdx).trim()
                                            val value = try {
                                                java.net.URLDecoder.decode(param.substring(eqIdx + 1), "UTF-8").trim()
                                            } catch (e: Exception) {
                                                param.substring(eqIdx + 1).trim()
                                            }
                                            if (key.equals("ref", ignoreCase = true) || key.equals("referer", ignoreCase = true)) {
                                                extraRef = if (value.startsWith("http")) value else "https://$value"
                                                if (!extraRef.endsWith("/")) extraRef += "/"
                                            }
                                            if (key.equals("origin", ignoreCase = true)) {
                                                extraOrig = if (value.startsWith("http")) value else "https://$value"
                                            }
                                        }
                                    }
                                }
                                
                                if (!headersMap.containsKey("Referer") && extraRef.isNotEmpty()) {
                                    headersMap["Referer"] = extraRef
                                }
                                if (!headersMap.containsKey("Origin") && extraOrig.isNotEmpty()) {
                                    headersMap["Origin"] = extraOrig
                                }
                                
                                if (!headersMap.containsKey("Referer")) {
                                    if (cleanDashUrl.contains("lion.hbx4.workers.dev", ignoreCase = true) || cleanDashUrl.contains("mewatch", ignoreCase = true)) {
                                        headersMap["Referer"] = "https://www.mewatch.sg/"
                                    }
                                }
                                
                                if (!headersMap.containsKey("Origin") && headersMap.containsKey("Referer")) {
                                    val refVal = headersMap["Referer"] ?: ""
                                    if (refVal.startsWith("http")) {
                                        try {
                                            val refUri = java.net.URI(refVal)
                                            headersMap["Origin"] = "${refUri.scheme}://${refUri.host}"
                                        } catch(e: Exception) {}
                                    }
                                }
                                
                                val isHbx4 = cleanDashUrl.contains("hbx4.workers.dev", ignoreCase = true) && !cleanDashUrl.contains("lion.hbx4.workers.dev", ignoreCase = true)
                                if (cleanDashUrl.contains("redbee.live", ignoreCase = true) || isHbx4) {
                                    if (cleanDashUrl.contains("redbee.live", ignoreCase = true)) {
                                        headersMap["User-Agent"] = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                    }
                                    headersMap["Referer"] = "${Xr3edEventProvider.PLAYER_BASE}/"
                                    headersMap["Origin"] = Xr3edEventProvider.PLAYER_BASE
                                }
                                
                                val headers = headersMap
                                val isM3u8Stream = cleanDashUrl.contains(".m3u8", ignoreCase = true) || cleanDashUrl.contains("m3u8", ignoreCase = true)

                                if (!drmStr.isNullOrBlank() && !isM3u8Stream) {
                                    val drmPairs = drmStr.split(";")
                                    val firstPairRaw = drmPairs.firstOrNull { it.contains(":") } ?: ""
                                    val colonIdx = firstPairRaw.indexOf(':')
                                    
                                    if (colonIdx > 0) {
                                        val keyId = firstPairRaw.substring(0, colonIdx).trim()
                                        val keyValue = firstPairRaw.substring(colonIdx + 1).trim()
                                        
                                        val allPairsForProxy = drmPairs
                                            .filter { it.contains(":") }
                                            .joinToString(",") { pair ->
                                                val idx = pair.indexOf(':')
                                                if (idx > 0) "${pair.substring(0, idx).trim()}:${pair.substring(idx + 1).trim()}"
                                                else pair.trim()
                                            }
                                        
                                        var actualKid = keyId
                                        try {
                                            val mResp = app.get(cleanDashUrl, headers = headers, timeout = 10).text
                                            val kidMatch = Regex("""cenc:default_KID\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(mResp)
                                            if (kidMatch != null) {
                                                actualKid = kidMatch.groupValues[1].replace("-", "")
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("EventProvider", "Failed to fetch manifest to get actual KID", e)
                                        }
                                        
                                        val finalDrmParam = if (allPairsForProxy.contains(",")) {
                                            allPairsForProxy
                                        } else {
                                            "$actualKid:$keyValue"
                                        }
                                        
                                        val finalStreamUrl = if (cleanDashUrl.contains(".mpd", ignoreCase = true) || cleanDashUrl.contains("mpd", ignoreCase = true)) {
                                            getDrmDashManifestUrl(cleanDashUrl, finalDrmParam, headers)
                                        } else {
                                            cleanDashUrl
                                        }
                                         
                                        android.util.Log.d("EventProvider", "Invoking CallSite 1: URL=$finalStreamUrl, DRM_PAIRS=$finalDrmParam, HEADERS=$headers")
                                        callback.invoke(
                                            newDrmExtractorLink(
                                                "$serverName (Bitmovin)",
                                                "$serverName (Bitmovin)",
                                                finalStreamUrl,
                                                ExtractorLinkType.DASH,
                                                CLEARKEY_UUID
                                            ) {
                                                quality = Qualities.Unknown.value
                                                this.headers = headers
                                                val clearkeyPair = buildClearKeyInjection(finalDrmParam)
                                                kty = "oct"
                                                kid = clearkeyPair.first
                                                this.key = clearkeyPair.second
                                            }
                                        )
                                        successDrm = true
                                    } else {
                                        android.util.Log.e("EventProvider", "DRM string format tidak valid (tidak ada ':' separator): $drmStr")
                                    }
                                } else {
                                    val cleanStreamUrl = streamUrl.split("|")[0].trim()
                                    android.util.Log.d("EventProvider", "Invoking CallSite (No DRM): URL=$cleanStreamUrl")
                                    val isM3u8 = cleanStreamUrl.contains(".m3u8", ignoreCase = true) || cleanStreamUrl.contains("m3u8", ignoreCase = true)
                                    val isDash = cleanStreamUrl.contains(".mpd", ignoreCase = true) || cleanStreamUrl.contains("mpd", ignoreCase = true)
                                    val streamType = when {
                                        isDash -> ExtractorLinkType.DASH
                                        isM3u8 -> ExtractorLinkType.M3U8
                                        else -> ExtractorLinkType.VIDEO
                                    }
                                    callback.invoke(
                                        ExtractorLink(
                                            source = "Bitmovin",
                                            name = "$serverName (HLS)",
                                            url = cleanStreamUrl,
                                            referer = headers["Referer"] ?: "",
                                            quality = Qualities.Unknown.value,
                                            type = streamType,
                                            headers = headers
                                        )
                                    )
                                    successDrm = true
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
                }
                
                if (!successDrm) {
                    android.util.Log.d("EventProvider", "Bitmovin failed/not found. Fallback to NS Player resolver for: $idVal")
                    try {
                        val nsWorkerUrl = "${Xr3edEventProvider.NS_WORKER_BASE}/?id=$idVal"
                        val nsResponseText = app.get(nsWorkerUrl, timeout = 15).text
                        android.util.Log.d("EventProvider", "NS Player worker raw response: $nsResponseText")
                        if (nsResponseText.trim().isNotEmpty()) {
                            val nsJson = JSONObject(nsResponseText.trim())
                            var targetKey = idVal
                            if (nsJson.has(targetKey)) {
                                // targetKey is fine
                            } else {
                                val baseId = if (idVal.startsWith("x") && idVal.length > 1) idVal.substring(1) else idVal
                                val remappedBaseId = remapWorkerIdForCheck(baseId)
                                if (nsJson.has(remappedBaseId + "x")) {
                                    targetKey = remappedBaseId + "x"
                                } else if (nsJson.has(remappedBaseId)) {
                                    targetKey = remappedBaseId
                                }
                            }
                            android.util.Log.d("EventProvider", "Resolved NS Player key from $idVal to $targetKey")
                            var encryptedPayload = nsJson.optString(targetKey)
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
                                    
                                    val userAgent = extractUserAgent(decryptedUrl, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                    val headersMap = HashMap<String, String>()
                                    headersMap["User-Agent"] = userAgent
                                    if (cleanUrl.contains("secureswiftcontent.com", ignoreCase = true) || idVal.contains("bein", ignoreCase = true)) {
                                        headersMap["X-Forwarded-For"] = "175.139.142.25"
                                    }
                                    
                                    val parts = decodedUrlParam.split("|")
                                    if (parts.size > 1) {
                                        val headerPart = parts[1]
                                        for (param in headerPart.split("&")) {
                                            val eqIdx = param.indexOf('=')
                                            if (eqIdx > 0) {
                                                val hKey = param.substring(0, eqIdx).trim()
                                                val hVal = try {
                                                    java.net.URLDecoder.decode(param.substring(eqIdx + 1), "UTF-8").trim()
                                                } catch (e: Exception) {
                                                    param.substring(eqIdx + 1).trim()
                                                }
                                                if (hKey.equals("Referer", ignoreCase = true) && hVal.isNotEmpty()) {
                                                    headersMap["Referer"] = hVal
                                                }
                                                if (hKey.equals("Origin", ignoreCase = true) && hVal.isNotEmpty()) {
                                                    headersMap["Origin"] = hVal
                                                }
                                            }
                                        }
                                    }
                                    val isHbx4 = cleanUrl.contains("hbx4.workers.dev", ignoreCase = true) && !cleanUrl.contains("lion.hbx4.workers.dev", ignoreCase = true)
                                    if (cleanUrl.contains("redbee.live", ignoreCase = true) || isHbx4) {
                                        headersMap["Referer"] = "${Xr3edEventProvider.PLAYER_BASE}/"
                                        headersMap["Origin"] = Xr3edEventProvider.PLAYER_BASE
                                    }
                                    val headers = headersMap.toMap()
                                    
                                       val isDash = cleanUrl.contains(".mpd", ignoreCase = true) || cleanUrl.contains("mpd", ignoreCase = true)
                                       val streamType = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                                       var actualKid = if (licenseParam.contains(":")) licenseParam.substringBefore(":") else ""
                                       if (licenseParam.contains(":") && !licenseParam.contains(",")) {
                                           val colonIdx = licenseParam.indexOf(':')
                                           val keyId = licenseParam.substring(0, colonIdx).trim()
                                           val keyValue = licenseParam.substring(colonIdx + 1).trim()
                                           actualKid = keyId
                                           try {
                                               val mResp = app.get(cleanUrl, headers = headers, timeout = 10).text
                                               val kidMatch = Regex("""cenc:default_KID\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(mResp)
                                               if (kidMatch != null) {
                                                   actualKid = kidMatch.groupValues[1].replace("-", "")
                                               }
                                           } catch (e: Exception) {
                                               android.util.Log.e("EventProvider", "Failed to fetch manifest to get actual KID (CallSite 2)", e)
                                           }
                                       }
                                       
                                       val finalDrmParam = if (licenseParam.contains(",")) {
                                           licenseParam
                                       } else if (licenseParam.contains(":")) {
                                           val colonIdx = licenseParam.indexOf(':')
                                           val keyValue = licenseParam.substring(colonIdx + 1).trim()
                                           "$actualKid:$keyValue"
                                       } else {
                                           licenseParam
                                       }
                                       
                                       val streamUrl = if (isDash) {
                                            getDrmDashManifestUrl(cleanUrl, finalDrmParam, headers)
                                       } else {
                                            cleanUrl
                                       }
                                       
                                       android.util.Log.d("EventProvider", "Invoking CallSite 2: URL=$streamUrl, ALL_PAIRS=$finalDrmParam, HEADERS=$headers")
                                       callback.invoke(
                                            newDrmExtractorLink(
                                                "$serverName (NS Player)",
                                                "$serverName (NS Player)",
                                                streamUrl,
                                                streamType,
                                                CLEARKEY_UUID
                                            ) {
                                                quality = Qualities.Unknown.value
                                                this.headers = headers
                                                val clearkeyPair = buildClearKeyInjection(finalDrmParam)
                                                kty = "oct"
                                                kid = clearkeyPair.first
                                                this.key = clearkeyPair.second
                                            }
                                        )
                                        successDrm = true
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
                 
                 if (successDrm) return true
                if (!successDrm && currentTargetUrl.contains(".pages.dev/") && !currentTargetUrl.contains("bitmovin") && !currentTargetUrl.contains("nsplayer")) {
                    currentTargetUrl = "${Xr3edEventProvider.STREAM_BASE}/live/$idVal/index.m3u8"
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
                Xr3edEventProvider.PLAYER_BASE
            }
            val isRedbee = currentTargetUrl.contains("redbee.live", ignoreCase = true)
            val headers = mutableMapOf<String, String>().apply {
                put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                if (isRedbee) {
                    put("Referer", "${Xr3edEventProvider.PLAYER_BASE}/")
                    put("Origin", Xr3edEventProvider.PLAYER_BASE)
                } else {
                    put("Referer", cleanReferer)
                    put("Origin", cleanOrigin)
                }
            }.toMap()

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
