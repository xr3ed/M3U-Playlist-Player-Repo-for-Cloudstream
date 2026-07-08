version = 1

android {
    namespace = "com.lagradost.ShortMax"
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "id"
    description = "Ekstensi Cloudstream untuk menonton drama pendek ShortMax"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("TvSeries")
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.17.2")
}
