version = 24

android {
    namespace = "com.sad25kag.gudangfilmxr"
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    authors = listOf("sad25kag", "XR3ED")
    language = "id"
    description = "GudangFilmXR provider modifikasi oleh XR3ED untuk domain aktif huazai6.com dengan dukungan resolver media player Morencius dan Turbovidhls."
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama"
    )

    iconUrl = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/icon.png"
}
