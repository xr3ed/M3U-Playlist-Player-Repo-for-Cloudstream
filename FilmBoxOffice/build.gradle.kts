version = 1

android {
    namespace = "com.sad25kag.filmboxoffice"
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
    description = "Plugin untuk Film Box Office Baru dengan dukungan login akun Google Drive Premium."
    status = 1

    tvTypes = listOf(
        "Movie"
    )

    iconUrl = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/icon.png"
}
