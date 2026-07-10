version = 41

android {
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "en"
    description = "Add your own m3u playlists"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf(
        "Live"
    )
    iconUrl = "https://play-lh.googleusercontent.com/V4t4JeQV2Cu9u72hKuqOW5c0IfwcZuuVS1d9PF2uJsW3rlIq-aOMTrT5bABVGaAFTcM=w480-h960-rw"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}

project.afterEvaluate {
    tasks.register("printClassFields") {
        doLast {
            val extension = project.extensions.getByName("android") as com.android.build.gradle.LibraryExtension
            extension.libraryVariants.forEach { variant ->
                if (variant.name == "debug") {
                    variant.compileConfiguration.files.forEach { file ->
                        if (file.absolutePath.contains("cloudstream") || file.absolutePath.contains("lagradost")) {
                            println("CLOUDSTREAM_JAR: ${file.absolutePath}")
                        }
                    }
                }
            }
        }
    }
}

tasks.register<JavaExec>("runBenchmark") {
    mainClass.set("benchmark.BenchmarkKt")
    classpath = sourceSets["main"].runtimeClasspath
}
