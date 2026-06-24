package com.cncverse.M3UPlaylistPlayer

import android.content.Context
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
    private var cachedPrograms: Map<String, List<EpgProgram>>? = null
    private var cachedChannelNames: Map<String, String>? = null // Maps display name to channel ID
    private var lastFetchTime: Long = 0L
    private val cacheDurationMs = 60 * 60 * 1000L // 1 hour cache

    private fun parseXmltvDate(dateStr: String): Long {
        val clean = dateStr.trim()
        val formats = listOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmmss"
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

    suspend fun getEpg(): Pair<Map<String, List<EpgProgram>>, Map<String, String>> {
        val now = System.currentTimeMillis()
        val cachedProgs = cachedPrograms
        val cachedNames = cachedChannelNames
        if (cachedProgs != null && cachedNames != null && (now - lastFetchTime) < cacheDurationMs) {
            return Pair(cachedProgs, cachedNames)
        }

        val epgUrl = "https://raw.githubusercontent.com/dhasap/dhanytv/main/epg.xml"
        try {
            android.util.Log.d("EpgHelper", "Fetching EPG XML from $epgUrl ...")
            val response = app.get(epgUrl, timeout = 30)
            val xmlText = response.text
            if (xmlText.isNotBlank()) {
                val (progs, names) = parseEpgXml(xmlText)
                cachedPrograms = progs
                cachedChannelNames = names
                lastFetchTime = now
                android.util.Log.d("EpgHelper", "Successfully loaded EPG: ${progs.size} channels mapped.")
                return Pair(progs, names)
            }
        } catch (e: Exception) {
            android.util.Log.e("EpgHelper", "Error loading EPG from $epgUrl", e)
        }
        return Pair(cachedPrograms ?: emptyMap(), cachedChannelNames ?: emptyMap())
    }

    fun parseEpgXml(xmlText: String): Pair<Map<String, List<EpgProgram>>, Map<String, String>> {
        val programMap = mutableMapOf<String, MutableList<EpgProgram>>()
        val nameToIdMap = mutableMapOf<String, String>()

        try {
            // Parse <channel id="..."> ... </channel>
            val channelRegex = Regex("<channel\\s+id=\"([^\"]+)\">(.*?)</channel>", RegexOption.DOT_MATCHES_ALL)
            val displayNameRegex = Regex("<display-name[^>]*>(.*?)</display-name>", RegexOption.DOT_MATCHES_ALL)
            
            channelRegex.findAll(xmlText).forEach { match ->
                val channelId = match.groupValues[1]
                val content = match.groupValues[2]
                nameToIdMap[channelId.lowercase()] = channelId
                
                displayNameRegex.findAll(content).forEach { dnMatch ->
                    val name = dnMatch.groupValues[1].trim()
                    if (name.isNotEmpty()) {
                        nameToIdMap[name.lowercase()] = channelId
                    }
                }
            }

            // Parse <programme start="..." stop="..." channel="..."> ... </programme>
            val programmeRegex = Regex("<programme\\s+start=\"([^\"]+)\"\\s+stop=\"([^\"]+)\"\\s+channel=\"([^\"]+)\">(.*?)</programme>", RegexOption.DOT_MATCHES_ALL)
            val titleRegex = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            val descRegex = Regex("<desc[^>]*>(.*?)</desc>", RegexOption.DOT_MATCHES_ALL)

            programmeRegex.findAll(xmlText).forEach { match ->
                val start = match.groupValues[1]
                val stop = match.groupValues[2]
                val channelId = match.groupValues[3]
                val content = match.groupValues[4]

                val titleMatch = titleRegex.find(content)
                val title = titleMatch?.groupValues?.get(1)?.trim() ?: ""
                
                val descMatch = descRegex.find(content)
                val desc = descMatch?.groupValues?.get(1)?.trim() ?: ""

                val startMs = parseXmltvDate(start)
                val stopMs = parseXmltvDate(stop)

                val epgProg = EpgProgram(title, desc, startMs, stopMs)
                programMap.getOrPut(channelId) { mutableListOf() }.add(epgProg)
            }

            // Sort each list by start time
            for (entry in programMap) {
                entry.value.sortBy { it.startUnixMs }
            }
        } catch (t: Throwable) {
            android.util.Log.e("EpgHelper", "Error parsing EPG XML details", t)
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

        for (key in keysToTry) {
            val lowercaseKey = key.lowercase()
            // 1. Try directly as channel id
            if (epgData.containsKey(key)) {
                return epgData[key] ?: emptyList()
            }
            // 2. Try lowercase key as channel id
            val directMatchId = nameToIdMap[lowercaseKey]
            if (directMatchId != null && epgData.containsKey(directMatchId)) {
                return epgData[directMatchId] ?: emptyList()
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
