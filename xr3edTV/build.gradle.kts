import java.util.Properties

version = 1

val localProps = Properties().also { p ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
}
fun secret(key: String): String = System.getenv(key) ?: localProps.getProperty(key) ?: ""

android {
    namespace = "com.lagradost.xr3edTV"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "CLONER_SIGNATURE", "\"${secret("CLONER_SIGNATURE")}\"")
        buildConfigField("String", "BYPASS_PASSWORD",  "\"${secret("BYPASS_PASSWORD")}\"")
        buildConfigField("String", "XR3EDTV_API_BASE", "\"${secret("XR3EDTV_API_BASE")}\"")
        buildConfigField("String", "XR3EDTV_XOR_KEY",  "\"${secret("XR3EDTV_XOR_KEY")}\"")
        buildConfigField("String", "XR3EDTV_SALT_KEY", "\"${secret("XR3EDTV_SALT_KEY")}\"")
        buildConfigField("String", "XR3EDTV_ONDEMAND_API",     "\"${secret("XR3EDTV_ONDEMAND_API")}\"")
        buildConfigField("String", "XR3EDTV_ONDEMAND_REFERER", "\"${secret("XR3EDTV_ONDEMAND_REFERER")}\"")
        buildConfigField("String", "WORKER_BASE_URL",  "\"${secret("WORKER_BASE_URL")}\"")
        buildConfigField("String", "WORKER_AUTH_KEY",  "\"${secret("WORKER_AUTH_KEY")}\"")
        buildConfigField("String", "NASIONAL_SOURCE_URL", "\"${secret("NASIONAL_SOURCE_URL")}\"")
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "live"
    description = "Siaran Langsung Olahraga Realtime (Live Sports) dan Saluran TV 24/7 Nasional maupun Mancanegara"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/main/live_icon.png"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
