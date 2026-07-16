version = 45

android {
    namespace = "com.sad25kag.anichinxr"
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    description = "Anichin Moe modifikasi oleh XR3ED"
    language = "id"
    authors = listOf("sad25kag", "XR3ED")
    status = 1
    tvTypes = listOf("AnimeMovie", "Anime", "Cartoon")
    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://anichin.club&size=%size%"
}
