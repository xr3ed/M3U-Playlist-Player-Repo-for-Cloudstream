version = 47


android {
    namespace = "com.lagradost.RBTVPlus"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        // Secrets RBTV+ — tidak terekspos di source code, diisi via GitHub Secrets
        buildConfigField("String", "RBTV_MAIN_URL", "\"${System.getenv("RBTV_MAIN_URL") ?: ""}\"")
        buildConfigField("String", "RBTV_API_HOST", "\"${System.getenv("RBTV_API_HOST") ?: ""}\"")
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
