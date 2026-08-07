// use an integer for version numbers
version = 1

android {
    namespace = "com.xr3ed.TestBox"
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    description = "TestBox – Movies, Series & TV powered by Vibox with auto-refresh guest token"
    authors = listOf("xr3ed")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/TestBox.png"

    requiresResources = false
    isCrossPlatform = false
}
