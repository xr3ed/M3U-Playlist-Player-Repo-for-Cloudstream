plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    // URL repositori tempat plugin akan di-host (atau github repo)
    setRepo("https://github.com/user/RBTVPlusRepository")
}

android {
    namespace = "com.lagradost.RBTVPlus"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
    }
}

dependencies {
    // Cloudstream library API (Jitpack SNAPSHOT)
    implementation("com.github.recloudstream.cloudstream:library:-SNAPSHOT")
    
    // Kotlin Stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    
    // Jsoup untuk HTML parsing
    implementation("org.jsoup:jsoup:1.17.2")
    
    // Jackson / Gson untuk JSON parsing (Jackson biasanya disertakan di library, tapi mari kita pastikan Kotlin-x serialization atau json parser bawaan library bisa dipakai)
}
