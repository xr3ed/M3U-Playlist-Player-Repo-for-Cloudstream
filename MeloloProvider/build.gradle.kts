version = 3

android {
    namespace = "com.lagradost.MeloloProvider"
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "id"
    description = "Penyedia Short Dramas & Reels dari Melolo"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("TvSeries")
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
