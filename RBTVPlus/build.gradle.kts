import java.util.Properties

version = 61

val localProps = Properties().also { p ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
}
fun secret(key: String): String = System.getenv(key) ?: localProps.getProperty(key) ?: ""

android {
    namespace = "com.lagradost.RBTVPlus"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        // Secrets RBTV+ — tidak terekspos di source code, diisi via GitHub Secrets
        buildConfigField("String", "RBTV_MAIN_URL", "\"${secret("RBTV_MAIN_URL")}\"")
        buildConfigField("String", "RBTV_API_HOST", "\"${secret("RBTV_API_HOST")}\"")
        buildConfigField("String", "RBTV_GIST_URL", "\"${secret("RBTV_GIST_URL")}\"")
        buildConfigField("String", "RBTV_PATH_BS", "\"${secret("RBTV_PATH_BS")}\"")
        buildConfigField("String", "RBTV_PATH_LIVE", "\"${secret("RBTV_PATH_LIVE")}\"")
        buildConfigField("String", "RBTV_PATH_DETAIL", "\"${secret("RBTV_PATH_DETAIL")}\"")
        buildConfigField("String", "RBTV_PATH_STREAM_DETAIL", "\"${secret("RBTV_PATH_STREAM_DETAIL")}\"")
        buildConfigField("String", "RBTV_USER_AGENT", "\"${secret("RBTV_USER_AGENT")}\"")
        buildConfigField("String", "RBTV_AES_KEY", "\"${secret("RBTV_AES_KEY")}\"")
        buildConfigField("String", "RBTV_AES_IV", "\"${secret("RBTV_AES_IV")}\"")
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    description = "Ekstensi Cloudstream untuk streaming olahraga langsung dari RBTV+"
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
