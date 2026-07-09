version = 23

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
    iconUrl = "https://play-lh.googleusercontent.com/GGi0g65ySR86svFuxny2J2FgfkTZuFlJcftmeAZ_bZ7sM86XfwUNL1h9omSyoRKha1c1-_BoFkUbyJz2VSXI=w240-h240-rw"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.17.2")
}
