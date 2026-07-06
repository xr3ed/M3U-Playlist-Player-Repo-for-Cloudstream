import kotlin.system.measureNanoTime

data class EpgProgram(
    val title: String,
    val desc: String,
    val startUnixMs: Long,
    val stopUnixMs: Long
)

fun getCurrentAndUpcomingTextOriginal(programs: List<EpgProgram>, now: Long): Pair<EpgProgram?, List<EpgProgram>> {
    var currentProgram: EpgProgram? = null
    val upcomingPrograms = mutableListOf<EpgProgram>()

    for (p in programs) {
        if (now in p.startUnixMs until p.stopUnixMs) {
            currentProgram = p
        } else if (p.startUnixMs >= now) {
            upcomingPrograms.add(p)
        }
    }

    if (currentProgram == null) {
        currentProgram = programs.lastOrNull { it.stopUnixMs <= now }
    }

    return Pair(currentProgram, upcomingPrograms)
}

fun getCurrentAndUpcomingTextOptimized(programs: List<EpgProgram>, now: Long): Pair<EpgProgram?, List<EpgProgram>> {
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
        // Find current program (last one that matches)
        for (i in latestBeforeOrAtNow downTo 0) {
            val p = programs[i]
            if (now in p.startUnixMs until p.stopUnixMs) {
                currentProgram = p
                break
            }
        }

        // Find current program (fallback: last one with stopUnixMs <= now)
        if (currentProgram == null) {
            for (i in latestBeforeOrAtNow downTo 0) {
                val p = programs[i]
                if (p.stopUnixMs <= now) {
                    currentProgram = p
                    break
                }
            }
        }

        // Find start index for upcoming programs
        var startIndex = latestBeforeOrAtNow
        while (startIndex > 0 && programs[startIndex - 1].startUnixMs == now) {
            startIndex--
        }

        for (i in startIndex until programs.size) {
            val p = programs[i]
            // We already found currentProgram, we just need to populate upcomingPrograms
            if (!(now in p.startUnixMs until p.stopUnixMs) && p.startUnixMs >= now) {
                upcomingPrograms.add(p)
            }
        }
    } else {
        // All programs have startUnixMs > now
        for (i in 0 until programs.size) {
            val p = programs[i]
            if (p.startUnixMs >= now) {
                upcomingPrograms.add(p)
            }
        }
    }

    return Pair(currentProgram, upcomingPrograms)
}

fun main() {
    val programs = mutableListOf<EpgProgram>()
    val baseTime = 1600000000000L
    for (i in 0 until 10000) {
        programs.add(EpgProgram("Title $i", "Desc", baseTime + i * 3600000L, baseTime + (i + 1) * 3600000L))
    }

    val now = baseTime + 5000 * 3600000L + 1800000L // middle

    // Warmup
    for (i in 0..100) {
        getCurrentAndUpcomingTextOriginal(programs, now)
        getCurrentAndUpcomingTextOptimized(programs, now)
    }

    var origTime = 0L
    var optTime = 0L
    val iters = 1000

    for (i in 0..iters) {
        origTime += measureNanoTime { getCurrentAndUpcomingTextOriginal(programs, now) }
        optTime += measureNanoTime { getCurrentAndUpcomingTextOptimized(programs, now) }
    }

    println("Original time avg: ${origTime / iters} ns")
    println("Optimized time avg: ${optTime / iters} ns")

    val res1 = getCurrentAndUpcomingTextOriginal(programs, now)
    val res2 = getCurrentAndUpcomingTextOptimized(programs, now)
    println("Result match: ${res1 == res2}")
}
