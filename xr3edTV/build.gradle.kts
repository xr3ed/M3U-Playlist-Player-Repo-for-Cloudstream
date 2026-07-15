import java.util.Properties

version = 5

// Baca secrets dari local.properties atau environment variable
val localProps = Properties().also { p ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
}
fun secret(key: String) = System.getenv(key) ?: localProps.getProperty(key) ?: ""

android {
    namespace = "com.lagradost.xr3edTV"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        // Secrets — diisi via local.properties (lokal) atau GitHub Secrets (CI/CD)
        // Tidak pernah hardcode di source code
        buildConfigField("String", "XR3EDTV_WORKER_URL",   "\"${secret("XR3EDTV_WORKER_URL")}\"")
        buildConfigField("String", "XR3EDTV_WORKER_TOKEN", "\"${secret("XR3EDTV_WORKER_TOKEN")}\"")
    }
}

cloudstream {
    language = "id"
    description = "Streaming TV Live — Sports & Entertainment"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://cdn.jsdelivr.net/gh/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream@main/live_icon.png"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
