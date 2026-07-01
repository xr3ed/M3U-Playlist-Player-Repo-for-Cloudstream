version = 89

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "id"
    description = "Plugin untuk live stream Event Piala Dunia 2026"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf(
        "Live"
    )
    iconUrl = "https://raw.githubusercontent.com/xr3ed/Auto-IPTV/main/logo/fifa.png"
}

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
}


dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
