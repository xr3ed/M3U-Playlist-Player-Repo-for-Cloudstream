version = 4

android {
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    description = "Plugin Uji Coba live stream Event Piala Dunia 2026"
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
