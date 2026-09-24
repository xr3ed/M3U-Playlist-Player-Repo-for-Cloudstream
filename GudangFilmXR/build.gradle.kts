import java.util.Properties as JavaProperties

version = 35

android {
    namespace = "com.sad25kag.gudangfilmxr"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        val properties = JavaProperties()
        val localPropFile = project.rootProject.file("local.properties")
        if (localPropFile.exists()) {
            localPropFile.inputStream().use { properties.load(it) }
        }
        val tmdbApi = System.getenv("XSTREAM_TMDB_API") ?: (properties.getProperty("XSTREAM_TMDB_API") ?: "")
        buildConfigField("String", "XSTREAM_TMDB_API", "\"$tmdbApi\"")
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    authors = listOf("sad25kag", "XR3ED")
    language = "id"
    description = "GudangFilmXR provider modifikasi oleh XR3ED untuk domain aktif 154.203.167.147 dengan resolver Playsobat dan AsiaStream."
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama"
    )

    iconUrl = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/icon.png"
}
