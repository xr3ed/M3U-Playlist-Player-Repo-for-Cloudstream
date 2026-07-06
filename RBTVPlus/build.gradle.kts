version = 39


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
}

cloudstream {
    language = "id"
    description = "Ekstensi Cloudstream untuk streaming olahraga langsung dari RBTV+"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("Live")
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.17.2")
}
