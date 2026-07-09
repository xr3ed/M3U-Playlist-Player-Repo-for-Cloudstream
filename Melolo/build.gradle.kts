version = 2

android {
    namespace = "com.lagradost.Melolo"
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "id"
    description = "Ekstensi Cloudstream untuk menonton drama pendek Melolo"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.cutad.web.id/favicon.svg"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.17.2")
}
