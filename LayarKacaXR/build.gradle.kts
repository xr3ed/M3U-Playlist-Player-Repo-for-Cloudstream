version = 5

android {
    namespace = "com.xr3ed.layarkacaxr"
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    authors = listOf("xr3ed")
    description = "LayarKaca — provider film dan series subtitle Indonesia dengan kategori LK21/Nontondrama lengkap serta parser API/player yang diperkuat."
    iconUrl = "https://assets.lk21.party/favicons/apple-icon-144x144.png"

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama"
    )
}
