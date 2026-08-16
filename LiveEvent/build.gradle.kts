version = 11

android {
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    description = "Beranda Kustom Khusus Siaran Langsung (Live Events) dengan Sinkronisasi Ultima"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://cdn.jsdelivr.net/gh/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream@main/live_icon.png"
    requiresResources = true
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
