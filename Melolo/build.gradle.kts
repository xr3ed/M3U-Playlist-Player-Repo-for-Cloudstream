version = 3

android {
    namespace = "com.lagradost.Melolo"
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    description = "Ekstensi Cloudstream untuk menonton drama pendek Melolo"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("TvSeries")
    iconUrl = "https://play-lh.googleusercontent.com/pE_o4iwh3TLbIfoiEZsnUggStQFH2Vemw6B37Ql9swvZWmFPIGLJEaTbqECs4GlvEXvp7hr7speou3MDen5Reg=w240-h240-rw"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.17.2")
}
