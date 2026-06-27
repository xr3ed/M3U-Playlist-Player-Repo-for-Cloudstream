version = 19


android {
    namespace = "com.lagradost.RBTVPlus"
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "id"
    description = "Ekstensi Cloudstream untuk streaming olahraga langsung dari RBTV+"
    authors = listOf("Antigravity")
    status = 1
    tvTypes = listOf("Live")
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.17.2")
}
