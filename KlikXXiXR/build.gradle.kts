import java.util.Properties
import java.io.FileInputStream

version = 2
android {
    namespace = "com.xr3ed.klikxxixr"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        val localPropertiesFile = rootProject.file("local.properties")
        val properties = Properties()
        if (localPropertiesFile.exists()) {
            properties.load(FileInputStream(localPropertiesFile))
        }
        val aesKey = (System.getenv("KLIKXXI_AES_KEY") ?: properties.getProperty("KLIKXXI_AES_KEY", "")).trim()
                buildConfigField("String", "KLIKXXI_AES_KEY", "\"${aesKey}\"")
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    authors = listOf("xr3ed")
    description = "KlikXXI — provider film terbaru subtitle Indonesia dengan kategori lengkap."
    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://klikxxi.shop&size=%size%"

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama"
    )
}
