version = 2

android {
    namespace = "com.xr3ed.support"
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    authors = listOf("xr3ed")
    language = "id"
    description = "Plugin Support untuk grup Telegram dan Donasi CloudstreamXR."
    status = 1

    tvTypes = listOf(
        "Others"
    )

    iconUrl = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/icon/support_icon.png"
}
