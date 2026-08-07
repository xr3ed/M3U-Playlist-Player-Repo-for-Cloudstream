// use an integer for version numbers
version = 12

android {
    namespace = "com.xr3ed.animesail"
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "AnimeSail — Streaming Anime, Donghua Subtitle Indonesia (Improved WIP)"
    authors = listOf("xr3ed")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://aghanim.xyz/wp-content/themes/animesail/assets/images/ico.png"
}
