import java.util.Properties

version = 65

val localProps = Properties().also { p ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
}
fun secret(key: String): String = System.getenv(key) ?: localProps.getProperty(key) ?: ""

android {
    namespace = "com.xr3ed.dracinaiov2"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "DRACINAIO_V2_URL", "\"${secret("DRACINAIO_V2_URL")}\"")
        buildConfigField("String", "CLONER_SIGNATURE", "\"${secret("CLONER_SIGNATURE")}\"")
        buildConfigField("String", "BYPASS_PASSWORD", "\"${secret("BYPASS_PASSWORD")}\"")
        buildConfigField("String", "DRACINAIO_V2_PATH_SECTIONS", "\"${secret("DRACINAIO_V2_PATH_SECTIONS")}\"")
        buildConfigField("String", "DRACINAIO_V2_PATH_GATE_UNLOCK", "\"${secret("DRACINAIO_V2_PATH_GATE_UNLOCK")}\"")
        buildConfigField("String", "DRACINAIO_V2_PATH_CONSENT", "\"${secret("DRACINAIO_V2_PATH_CONSENT")}\"")
        buildConfigField("String", "DRACINAIO_V2_PATH_EDGE", "\"${secret("DRACINAIO_V2_PATH_EDGE")}\"")
        buildConfigField("String", "DRACINAIO_V2_PATH_REFRESH", "\"${secret("DRACINAIO_V2_PATH_REFRESH")}\"")
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    description = "Ekstensi DracinAio V2 - #Dracin All in One [Backup]"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf("TvSeries")
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.17.2")
}
