package com.cncverse.M3UPlaylistPlayer

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import com.lagradost.cloudstream3.app
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class EpgProgram(
    val title: String,
    val desc: String,
    val startUnixMs: Long,
    val stopUnixMs: Long
)

object EpgHelper {
    private val cachedProgramsMap = java.util.concurrent.ConcurrentHashMap<String, Map<String, List<EpgProgram>>>()
    private val cachedChannelNamesMap = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val lastFetchTimeMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val cacheDurationMs = 60 * 60 * 1000L // 1 hour cache

    private fun parseXmltvDate(dateStr: String): Long {
        // Hapus titik dua pada timezone offset (misal: +07:00 menjadi +0700) agar mudah di-parse
        var clean = dateStr.trim().replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        
        // Pastikan ada spasi sebelum offset jika ada offset tanpa spasi (misal: 20260625170000+0700 menjadi 20260625170000 +0700)
        clean = clean.replace(Regex("(\\d{14})([+-]\\d{4})$"), "$1 $2")

        val formats = listOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmmss",
            "yyyy-MM-dd HH:mm:ss Z",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (f in formats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US)
                val date = sdf.parse(clean)
                if (date != null) {
                    return date.time
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        return 0L
    }

    fun getGithubMirrors(url: String): List<String> {
        if (!url.contains("raw.githubusercontent.com", ignoreCase = true)) {
            return listOf(url)
        }
        val cleanUrl = url.trim()
        val parts = cleanUrl.removePrefix("https://").removePrefix("http://").split('/')
        if (parts.size >= 4 && parts[0].equals("raw.githubusercontent.com", ignoreCase = true)) {
            val username = parts[1]
            val repo = parts[2]
            val branch = parts[3]
            val path = parts.drop(4).joinToString("/")
            
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

        val urlsToTry = getGithubMirrors(epgUrl)
        for (url in urlsToTry) {
            try {
                android.util.Log.d("EpgHelper", "Fetching EPG XML from $url ...")
                // Gunakan User-Agent browser agar request tidak diblokir oleh server EPG kustom/Cloudflare
                val response = app.get(
                    url,
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"),
                    timeout = 25
                )
                val xmlText = response.text
                if (xmlText.isNotBlank()) {
                    val (progs, names) = parseEpgXml(xmlText)
                    if (progs.isNotEmpty() || names.isNotEmpty()) {
                        cachedProgramsMap[epgUrl] = progs
                        cachedChannelNamesMap[epgUrl] = names
                        lastFetchTimeMap[epgUrl] = now
                        android.util.Log.d("EpgHelper", "Successfully loaded EPG from $url: ${progs.size} channels mapped.")
                        return Pair(progs, names)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("EpgHelper", "Error loading EPG from $url", e)
            }
        }
        return Pair(cachedProgramsMap[epgUrl] ?: emptyMap(), cachedChannelNamesMap[epgUrl] ?: emptyMap())
    }

    fun cleanChannelName(name: String): String {
        var clean = name.lowercase()
        // Hapus tag seperti [HD], (HD), (BACKUP), dll.
        clean = clean.replace(Regex("\\s*[\\[(].*?[\\])]"), "")
        // Hapus kata-kata penanda kualitas/sumber umum di akhir
        clean = clean.replace(Regex("\\s+(hd|fhd|sd|hevc|h265|1080p|720p|id|indo|indonesia|tv|asia)\\b"), "")
        // Hanya sisakan karakter alfanumerik dan spasi
        clean = clean.replace(Regex("[^a-z0-9\\s]"), "")
        // Buang spasi ganda
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return clean
    }

    fun parseEpgXml(xmlText: String): Pair<Map<String, List<EpgProgram>>, Map<String, String>> {
        val programMap = mutableMapOf<String, MutableList<EpgProgram>>()
        val nameToIdMap = mutableMapOf<String, String>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlText))

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

            // Sortir program berdasarkan waktu mulai
            for (entry in programMap) {
                entry.value.sortBy { it.startUnixMs }
            }
        } catch (t: Throwable) {
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

    fun getCurrentAndUpcomingText(programs: List<EpgProgram>): Pair<String?, String> {
        if (programs.isEmpty()) {
            return Pair(null, "Tidak ada data jadwal acara (EPG).")
        }

        val now = System.currentTimeMillis()
        var currentProgram: EpgProgram? = null
        val upcomingPrograms = mutableListOf<EpgProgram>()

        for (p in programs) {
            if (now in p.startUnixMs until p.stopUnixMs) {
                currentProgram = p
            } else if (p.startUnixMs >= now) {
                upcomingPrograms.add(p)
            }
        }

        // If no program matched exactly (e.g. gap or slightly outdated), find the latest one before now
        if (currentProgram == null) {
            currentProgram = programs.lastOrNull { it.stopUnixMs <= now }
        }

        val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        
        val currentText = currentProgram?.let { p ->
            val startStr = if (p.startUnixMs > 0) timeSdf.format(p.startUnixMs) else "--:--"
            val stopStr = if (p.stopUnixMs > 0) timeSdf.format(p.stopUnixMs) else "--:--"
            "Sedang Tayang: ${p.title} ($startStr - $stopStr)"
        }

        val scheduleBuilder = StringBuilder()
        scheduleBuilder.append("--- JADWAL ACARA ---\n\n")

        if (currentProgram != null) {
            val startStr = if (currentProgram.startUnixMs > 0) timeSdf.format(currentProgram.startUnixMs) else "--:--"
            val stopStr = if (currentProgram.stopUnixMs > 0) timeSdf.format(currentProgram.stopUnixMs) else "--:--"
            scheduleBuilder.append("• [SEDANG TAYANG] $startStr - $stopStr : ${currentProgram.title}\n")
            if (currentProgram.desc.isNotEmpty()) {
                scheduleBuilder.append("  Deskripsi: ${currentProgram.desc}\n")
            }
            scheduleBuilder.append("\n")
        }

        if (upcomingPrograms.isNotEmpty()) {
            scheduleBuilder.append("Acara Selanjutnya:\n")
            // Show up to 8 upcoming programs
            for (p in upcomingPrograms.take(8)) {
                val startStr = if (p.startUnixMs > 0) timeSdf.format(p.startUnixMs) else "--:--"
                val stopStr = if (p.stopUnixMs > 0) timeSdf.format(p.stopUnixMs) else "--:--"
                scheduleBuilder.append("• $startStr - $stopStr : ${p.title}\n")
                if (p.desc.isNotEmpty()) {
                    scheduleBuilder.append("  ${p.desc}\n")
                }
            }
        } else {
            scheduleBuilder.append("Tidak ada jadwal acara selanjutnya untuk hari ini.")
        }

        return Pair(currentText, scheduleBuilder.toString())
    }
}
