version = 1

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
    description = "Beranda Kustom Tanpa Sync"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf(
        "Movie", "TvSeries", "Anime"
    )
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
