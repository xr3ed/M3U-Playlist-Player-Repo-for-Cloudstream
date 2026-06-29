version = 34

android {
    namespace = "com.cncverse.xr3edtv"
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "id"
    description = "Ekstensi Cloudstream untuk live TV gratis di XR3EDTV"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf(
        "Live"
    )
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
