// use an integer for version numbers
version = 1

android {
    namespace = "com.xr3ed.kepalabergetar"
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "ms"
    description = "Kepala Bergetar — Tonton Drama Melayu, Telefilem & Filem Online"
    authors = listOf("xr3ed")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://kepalabergetar.cfd/wp-content/uploads/2023/08/fav.png"
}
