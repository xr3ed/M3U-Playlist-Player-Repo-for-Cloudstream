version = 4

android {
    namespace = "com.lagradost.xr3edFlix"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "XSTREAM_TMDB_API", "\"${System.getenv("XSTREAM_TMDB_API") ?: ""}\"")
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    description = "Ekstensi Cloudstream untuk menonton Film dan TV Series gratis dari autoembed"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://watch-v2.autoembed.app/apple-touch-icon.png"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.17.2")
}
