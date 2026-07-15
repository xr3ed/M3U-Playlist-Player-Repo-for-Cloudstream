import java.util.Properties as JavaProperties

version = 47

android {
    namespace = "com.lagradost.xr3edFlix"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        val properties = JavaProperties()
        val localPropFile = project.rootProject.file("local.properties")
        if (localPropFile.exists()) {
            localPropFile.inputStream().use { properties.load(it) }
        }
        val mbDefault = System.getenv("MOVIEBOX_SECRET_KEY_DEFAULT") ?: (properties.getProperty("MOVIEBOX_SECRET_KEY_DEFAULT") ?: "")
        val mbAlt = System.getenv("MOVIEBOX_SECRET_KEY_ALT") ?: (properties.getProperty("MOVIEBOX_SECRET_KEY_ALT") ?: "")
        buildConfigField("String", "XSTREAM_TMDB_API", "\"${System.getenv("XSTREAM_TMDB_API") ?: ""}\"")
        buildConfigField("String", "MOVIEBOX_SECRET_KEY_DEFAULT", "\"$mbDefault\"")
        buildConfigField("String", "MOVIEBOX_SECRET_KEY_ALT", "\"$mbAlt\"")
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
