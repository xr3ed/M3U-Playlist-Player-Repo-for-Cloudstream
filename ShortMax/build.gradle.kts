version = 13

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
    iconUrl = "https://play-lh.googleusercontent.com/NOT1CZ461en8dArwro-Nz0UCyD6aJ3tLm3WkPArTDTh-8ApKbBlTSi8mpObdK48SKw7Ve2RgVNOIU1U4nTEAw8U=w240-h240-rw"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.17.2")
}
