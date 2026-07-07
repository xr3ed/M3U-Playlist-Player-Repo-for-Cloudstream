version = 3

android {
    namespace = "com.lagradost.DramaBox"
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "id"
    description = "Ekstensi Cloudstream untuk menonton drama pendek DramaBox"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("TvSeries")
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.17.2")
}
