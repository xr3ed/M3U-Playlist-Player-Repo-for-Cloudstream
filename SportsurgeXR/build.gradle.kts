import java.util.Properties

version = 13

val localProps = Properties().also { p ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
}
fun secret(key: String): String {
    val localVal = localProps.getProperty("SPORTSURGE_$key")
        ?: localProps.getProperty("RBTV_$key")
    val envVal = System.getenv("SPORTSURGE_$key")
        ?: System.getenv("RBTV_$key")
    return envVal ?: localVal ?: ""
}

android {
    namespace = "com.lagradost.SportsurgeXR"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "RBTV_MAIN_URL", "\"${secret("MAIN_URL")}\"")
        buildConfigField("String", "RBTV_API_HOST", "\"${secret("API_HOST")}\"")
        buildConfigField("String", "RBTV_GIST_URL", "\"${secret("GIST_URL")}\"")
        buildConfigField("String", "RBTV_PATH_BS", "\"${secret("PATH_BS")}\"")
        buildConfigField("String", "RBTV_PATH_LIVE", "\"${secret("PATH_LIVE")}\"")
        buildConfigField("String", "RBTV_PATH_DETAIL", "\"${secret("PATH_DETAIL")}\"")
        buildConfigField("String", "RBTV_PATH_STREAM_DETAIL", "\"${secret("PATH_STREAM_DETAIL")}\"")
        buildConfigField("String", "RBTV_USER_AGENT", "\"${secret("USER_AGENT")}\"")
        buildConfigField("String", "RBTV_AES_KEY", "\"${secret("AES_KEY")}\"")
        buildConfigField("String", "RBTV_AES_IV", "\"${secret("AES_IV")}\"")
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    description = "Ekstensi Cloudstream untuk streaming olahraga langsung dari SportsurgeXR"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://cdn.jsdelivr.net/gh/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream@main/live_icon.png"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.17.2")
}
