version = 5

android {
    namespace = "com.xr3ed.klikxxixr"
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    authors = listOf("xr3ed")
    description = "KlikXXI — provider film terbaru subtitle Indonesia dengan kategori lengkap."
    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://klikxxi.shop&size=%size%"

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama"
    )
}
