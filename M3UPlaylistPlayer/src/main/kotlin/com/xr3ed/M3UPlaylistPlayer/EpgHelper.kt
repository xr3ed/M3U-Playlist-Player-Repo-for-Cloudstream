package com.xr3ed.M3UPlaylistPlayer

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.GZIPInputStream
import com.lagradost.cloudstream3.app
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EpgProgram(
    val title: String,
    val desc: String,
    val startUnixMs: Long,
    val stopUnixMs: Long
)

object EpgHelper {
    private val tagRegex = Regex("\\s*[\\[(].*?[\\])]")
    private val qualityRegex = Regex("\\s+(hd|fhd|sd|hevc|h265|1080p|720p|id|indo|indonesia|tv|asia)\\b")
    private val nonAlphaNumRegex = Regex("[^a-z0-9\\s]")
    private val multipleSpaceRegex = Regex("\\s+")

    var lastError: String? = null
    private val cachedProgramsMap = java.util.concurrent.ConcurrentHashMap<String, Map<String, List<EpgProgram>>>()
    private val cachedChannelNamesMap = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val lastFetchTimeMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val cacheDurationMs = 60 * 60 * 1000L // 1 hour cache

    private val epgMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val globalEpgMutex = Mutex()
    private val cleanedNamesCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun getMutexForEpgUrl(url: String): Mutex {
        return globalEpgMutex.withLock {
            epgMutexes.getOrPut(url) { Mutex() }
        }
    }

    private val TIMEZONE_COLON_REGEX = Regex("([+-]\\d{2}):(\\d{2})$")
    private val OFFSET_SPACE_REGEX = Regex("(\\d{14})([+-]\\d{4})$")
    private val XMLTV_DATE_FORMATS = arrayOf(
        "yyyyMMddHHmmss Z",
        "yyyyMMddHHmmss",
        "yyyy-MM-dd HH:mm:ss Z",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
    )

    fun clearCache() {
        cachedProgramsMap.clear()
        cachedChannelNamesMap.clear()
        lastFetchTimeMap.clear()
        cleanedNamesCache.clear()
        epgMutexes.clear()
    }

    // Menggunakan ThreadLocal untuk SimpleDateFormat agar parsing puluhan ribu data EPG sangat cepat dan thread-safe
    private val formattersHolder = object : ThreadLocal<Array<SimpleDateFormat>>() {
        override fun initialValue(): Array<SimpleDateFormat> {
            return XMLTV_DATE_FORMATS.map { SimpleDateFormat(it, Locale.US) }.toTypedArray()
        }
    }

    private fun parseXmltvDate(dateStr: String): Long {
        // Hapus titik dua pada timezone offset (misal: +07:00 menjadi +0700) agar mudah di-parse
        var clean = dateStr.trim().replace(TIMEZONE_COLON_REGEX, "$1$2")
        
        // Pastikan ada spasi sebelum offset jika ada offset tanpa spasi (misal: 20260625170000+0700 menjadi 20260625170000 +0700)
        clean = clean.replace(OFFSET_SPACE_REGEX, "$1 $2")

        val formatters = formattersHolder.get() ?: return 0L
        for (sdf in formatters) {
            try {
                val date = sdf.parse(clean)
                if (date != null) {
                    return date.time
                }
            } catch (e: java.text.ParseException) {
                // ignore parse exception to try next format
            }
        }
        return 0L
    }

    val GithubMirrorsMap = mapOf(
        "raw.githubusercontent.com" to listOf(
            "raw.githubusercontent.com",
            "cdn.jsdelivr.net/gh",
            "raw.githack.com"
        )
    )

    fun getGithubMirrors(url: String): List<String> {
        val cleanUrl = url.trim()
        if (cleanUrl.contains("raw.githubusercontent.com")) {
            val parts = cleanUrl.split("/")
            if (parts.size < 6) return listOf(cleanUrl)
            
            val username = parts[3]
            val repo = parts[4]
            val branch = parts[5]
            val path = parts.drop(6).joinToString("/")
            
            val jsdelivr = "https://cdn.jsdelivr.net/gh/$username/$repo@$branch/$path"
            val githack = "https://raw.githack.com/$username/$repo/$branch/$path"
            
            return listOf(cleanUrl, jsdelivr, githack)
        }
        
        // Handle git branch files
        if (cleanUrl.contains("github.com") && cleanUrl.contains("/raw/")) {
            val parts = cleanUrl.split("/")
            if (parts.size < 7) return listOf(cleanUrl)
            
            val username = parts[3]
            val repo = parts[4]
            val branch = parts[6]
            val path = parts.drop(7).joinToString("/")
            
            val jsdelivr = "https://cdn.jsdelivr.net/gh/$username/$repo@$branch/$path"
            val githack = "https://raw.githack.com/$username/$repo/$branch/$path"
            
            return listOf(cleanUrl, jsdelivr, githack)
        }
        return listOf(url)
    }

    suspend fun getEpg(context: Context?, epgUrl: String): Pair<Map<String, List<EpgProgram>>, Map<String, String>> {
        val now = System.currentTimeMillis()
        val cachedProgs = cachedProgramsMap[epgUrl]
        val cachedNames = cachedChannelNamesMap[epgUrl]
        val lastFetch = lastFetchTimeMap[epgUrl] ?: 0L
        if (cachedProgs != null && cachedNames != null && (now - lastFetch) < cacheDurationMs) {
            return Pair(cachedProgs, cachedNames)
        }

        val mutex = getMutexForEpgUrl(epgUrl)
        return mutex.withLock {
            val cachedProgsSec = cachedProgramsMap[epgUrl]
            val cachedNamesSec = cachedChannelNamesMap[epgUrl]
            val lastFetchSec = lastFetchTimeMap[epgUrl] ?: 0L
            if (cachedProgsSec != null && cachedNamesSec != null && (System.currentTimeMillis() - lastFetchSec) < cacheDurationMs) {
                return@withLock Pair(cachedProgsSec, cachedNamesSec)
            }

            val urlsToTry = getGithubMirrors(epgUrl)
            var errorBuilder = StringBuilder()
            for (url in urlsToTry) {
                try {
                    android.util.Log.d("EpgHelper", "Fetching EPG XML from $url ...")
                    // Gunakan User-Agent browser agar request tidak diblokir oleh server EPG kustom/Cloudflare
                    val response = app.get(
                        url,
                        headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"),
                        timeout = 25
                    )
                    
                    val rawStream = response.body.byteStream()
                    val isGzip = url.endsWith(".gz", ignoreCase = true) || 
                                 response.headers["Content-Encoding"]?.contains("gzip", ignoreCase = true) == true
                    
                    val stream = if (isGzip) {
                        GZIPInputStream(rawStream)
                    } else {
                        rawStream
                    }
                    
                    val (progs, names) = stream.use { parseEpgXml(it) }
                    if (progs.isNotEmpty() || names.isNotEmpty()) {
                        cachedProgramsMap[epgUrl] = progs
                        cachedChannelNamesMap[epgUrl] = names
                        lastFetchTimeMap[epgUrl] = System.currentTimeMillis()
                        lastError = null // Clear error on success
                        android.util.Log.d("EpgHelper", "Successfully loaded EPG from $url: ${progs.size} channels mapped.")
                        return@withLock Pair(progs, names)
                    } else {
                        errorBuilder.append("[$url]: Map terurai kosong. ")
                    }
                } catch (e: Exception) {
                    errorBuilder.append("[$url]: ${e.message}. ")
                    android.util.Log.e("EpgHelper", "Error loading EPG from $url", e)
                }
            }
            lastError = "Semua mirror EPG gagal: " + errorBuilder.toString()
            Pair(cachedProgramsMap[epgUrl] ?: emptyMap(), cachedChannelNamesMap[epgUrl] ?: emptyMap())
        }
    }

    fun cleanChannelName(name: String): String {
        return cleanedNamesCache.getOrPut(name) {
            var clean = name.lowercase()
            // Hapus tag seperti [HD], (HD), (BACKUP), dll.
            clean = clean.replace(tagRegex, "")
            // Hapus kata-kata penanda kualitas/sumber umum di akhir
            clean = clean.replace(qualityRegex, "")
            // Hanya sisakan karakter alfanumerik dan spasi
            clean = clean.replace(nonAlphaNumRegex, "")
            // Buang spasi ganda
            clean = clean.replace(multipleSpaceRegex, " ").trim()
            clean
        }
    }

    fun parseEpgXml(input: InputStream): Pair<Map<String, List<EpgProgram>>, Map<String, String>> {
        val programMap = mutableMapOf<String, MutableList<EpgProgram>>()
        val nameToIdMap = mutableMapOf<String, String>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(input, null)

            var eventType = parser.eventType
            
            // State pencatatan channel
            var currentChannelId: String? = null
            
            // State pencatatan programme
            var currentProgChannel: String? = null
            var currentProgStart: String? = null
            var currentProgStop: String? = null
            var currentProgTitle: String? = null
            var currentProgDesc: String? = null

            var currentTextTag: String? = null
            val textBuilder = StringBuilder()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (name) {
                            "channel" -> {
                                currentChannelId = parser.getAttributeValue(null, "id")
                                if (currentChannelId != null) {
                                    val lowerId = currentChannelId.lowercase()
                                    nameToIdMap[lowerId] = lowerId
                                }
                            }
                            "display-name", "title", "desc" -> {
                                currentTextTag = name
                                textBuilder.setLength(0)
                            }
                            "programme" -> {
                                currentProgChannel = parser.getAttributeValue(null, "channel")
                                currentProgStart = parser.getAttributeValue(null, "start")
                                currentProgStop = parser.getAttributeValue(null, "stop")
                                currentProgTitle = null
                                currentProgDesc = null
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (currentTextTag != null) {
                            val txt = parser.text
                            if (txt != null) {
                                textBuilder.append(txt)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (name) {
                            "display-name" -> {
                                if (currentTextTag == "display-name") {
                                    val displayName = textBuilder.toString().trim()
                                    if (displayName.isNotEmpty() && currentChannelId != null) {
                                        val lowerId = currentChannelId.lowercase()
                                        nameToIdMap[displayName.lowercase()] = lowerId
                                        val cleanName = cleanChannelName(displayName)
                                        if (cleanName.isNotEmpty()) {
                                            nameToIdMap[cleanName] = lowerId
                                        }
                                    }
                                    currentTextTag = null
                                }
                            }
                            "title" -> {
                                if (currentTextTag == "title") {
                                    currentProgTitle = textBuilder.toString().trim()
                                    currentTextTag = null
                                }
                            }
                            "desc" -> {
                                if (currentTextTag == "desc") {
                                    currentProgDesc = textBuilder.toString().trim()
                                    currentTextTag = null
                                }
                            }
                            "channel" -> {
                                currentChannelId = null
                            }
                            "programme" -> {
                                if (currentProgChannel != null && currentProgStart != null && currentProgStop != null) {
                                    val title = currentProgTitle ?: ""
                                    val desc = currentProgDesc ?: ""
                                    val startMs = parseXmltvDate(currentProgStart)
                                    val stopMs = parseXmltvDate(currentProgStop)
                                    val epgProg = EpgProgram(title, desc, startMs, stopMs)
                                    
                                    val lowerChannel = currentProgChannel.lowercase()
                                    programMap.getOrPut(lowerChannel) { mutableListOf() }.add(epgProg)
                                }
                                currentProgChannel = null
                                currentProgStart = null
                                currentProgStop = null
                                currentProgTitle = null
                                currentProgDesc = null
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            // Sortir program berdasarkan waktu mulai dan bersihkan data duplikat
            for (key in programMap.keys) {
                val uniqueSorted = programMap[key].orEmpty()
                    .distinctBy { it.startUnixMs to it.title }
                    .sortedBy { it.startUnixMs }
                programMap[key] = uniqueSorted.toMutableList()
            }
        } catch (t: Throwable) {
            lastError = "Parser crash: " + t.toString() + "\n" + t.stackTrace.take(4).joinToString("\n")
            android.util.Log.e("EpgHelper", "Error parsing EPG XML dengan XmlPullParser", t)
        }
        return Pair(programMap, nameToIdMap)
    }

    fun getProgramsForChannel(
        item: PlaylistItem,
        epgData: Map<String, List<EpgProgram>>,
        nameToIdMap: Map<String, String>
    ): List<EpgProgram> {
        val tvgId = item.attributes["tvg-id"]?.trim()
        val tvgName = item.attributes["tvg-name"]?.trim()
        val title = item.title.trim()

        val keysToTry = listOfNotNull(
            tvgId,
            tvgName,
            title
        )

        // 1. Coba pencocokan langsung secara case-insensitive
        for (key in keysToTry) {
            val lowercaseKey = key.lowercase()
            
            // Coba cari langsung sebagai channel ID (lowercase)
            if (epgData.containsKey(lowercaseKey)) {
                return epgData[lowercaseKey] ?: emptyList()
            }
            
            // Coba cari melalui mapping nama channel (lowercase)
            val mappedId = nameToIdMap[lowercaseKey]
            if (mappedId != null && epgData.containsKey(mappedId)) {
                return epgData[mappedId] ?: emptyList()
            }
        }

        // 2. Coba pencocokan fuzzy (dengan pembersihan nama channel)
        for (key in keysToTry) {
            val cleanKey = cleanChannelName(key)
            if (cleanKey.isNotEmpty()) {
                // Coba cari melalui mapping nama bersih
                val mappedId = nameToIdMap[cleanKey]
                if (mappedId != null && epgData.containsKey(mappedId)) {
                    return epgData[mappedId] ?: emptyList()
                }
                // Coba cari langsung sebagai channel ID bersih
                if (epgData.containsKey(cleanKey)) {
                    return epgData[cleanKey] ?: emptyList()
                }
            }
        }

        return emptyList()
    }

    private val timeFormatterHolder = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("HH:mm", Locale.getDefault())
        }
    }

    fun getCurrentAndUpcomingText(programs: List<EpgProgram>): Pair<String?, String> {
        if (programs.isEmpty()) {
            return Pair(null, "Tidak ada data jadwal acara (EPG).")
        }

        val now = System.currentTimeMillis()
        var currentProgram: EpgProgram? = null
        val upcomingPrograms = mutableListOf<EpgProgram>()

        var low = 0
        var high = programs.size - 1
        var latestBeforeOrAtNow = -1

        while (low <= high) {
            val mid = low + (high - low) / 2
            if (programs[mid].startUnixMs <= now) {
                latestBeforeOrAtNow = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        if (latestBeforeOrAtNow != -1) {
            for (i in latestBeforeOrAtNow downTo 0) {
                val p = programs[i]
                if (now in p.startUnixMs until p.stopUnixMs) {
                    currentProgram = p
                    break
                }
            }

            // If no program matched exactly (e.g. gap or slightly outdated), find the latest one before now
            if (currentProgram == null) {
                for (i in latestBeforeOrAtNow downTo 0) {
                    val p = programs[i]
                    if (p.stopUnixMs <= now) {
                        currentProgram = p
                        break
                    }
                }
            }
        }

        var startIndex = if (latestBeforeOrAtNow != -1) latestBeforeOrAtNow else 0
        while (startIndex > 0 && programs[startIndex - 1].startUnixMs >= now) {
            startIndex--
        }

        for (i in startIndex until programs.size) {
            val p = programs[i]
            if (now in p.startUnixMs until p.stopUnixMs) {
                continue
            } else if (p.startUnixMs >= now) {
                upcomingPrograms.add(p)
            }
        }

        val timeSdf = timeFormatterHolder.get() ?: SimpleDateFormat("HH:mm", Locale.getDefault())
        
        val currentText = currentProgram?.let { p ->
            val startStr = if (p.startUnixMs > 0) timeSdf.format(p.startUnixMs) else "--:--"
            val stopStr = if (p.stopUnixMs > 0) timeSdf.format(p.stopUnixMs) else "--:--"
            val remainingMs = p.stopUnixMs - now
            val remainingMin = remainingMs / (60 * 1000)
            val remainingText = if (remainingMin > 0) " (Sisa ${remainingMin}m)" else ""
            "Sedang Tayang: ${p.title} ($startStr - $stopStr)$remainingText"
        }

        val scheduleBuilder = StringBuilder()
        
        if (currentProgram != null) {
            val startStr = if (currentProgram.startUnixMs > 0) timeSdf.format(currentProgram.startUnixMs) else "--:--"
            val stopStr = if (currentProgram.stopUnixMs > 0) timeSdf.format(currentProgram.stopUnixMs) else "--:--"
            val remainingMs = currentProgram.stopUnixMs - now
            val remainingMin = remainingMs / (60 * 1000)
            val remainingText = if (remainingMin > 0) " [Sisa ${remainingMin} menit]" else ""
            
            scheduleBuilder.append("📺 SEDANG TAYANG:\n")
            scheduleBuilder.append("• $startStr - $stopStr : ${currentProgram.title}$remainingText\n")
            if (currentProgram.desc.isNotEmpty()) {
                scheduleBuilder.append("  📝 Deskripsi: ${currentProgram.desc}\n")
            }
            scheduleBuilder.append("\n")
        }

        if (upcomingPrograms.isNotEmpty()) {
            scheduleBuilder.append("⏰ ACARA SELANJUTNYA:\n")
            // Show up to 8 upcoming programs
            for (p in upcomingPrograms.take(8)) {
                val startStr = if (p.startUnixMs > 0) timeSdf.format(p.startUnixMs) else "--:--"
                val stopStr = if (p.stopUnixMs > 0) timeSdf.format(p.stopUnixMs) else "--:--"
                scheduleBuilder.append("• $startStr - $stopStr : ${p.title}\n")
                if (p.desc.isNotEmpty()) {
                    scheduleBuilder.append("  📝 ${p.desc}\n")
                }
            }
        } else {
            scheduleBuilder.append("⏰ Tidak ada jadwal acara selanjutnya untuk hari ini.")
        }

        return Pair(currentText, scheduleBuilder.toString())
    }
}
