import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Icons and version info for the exe (Explorer, the tray, Task Manager), compiled with the windres Kotlin/Native ships.
val resources = layout.buildDirectory.file("res/gokeys.res")
val compileResources = tasks.register<Exec>("compileResources") {
    val konan = File(System.getenv("KONAN_DATA_DIR") ?: "${System.getProperty("user.home")}/.konan", "dependencies")
    val mingw = konan.listFiles { f -> f.name.startsWith("msys2-mingw-w64-x86_64") }?.maxOrNull()
        ?: error("Kotlin/Native's mingw toolchain isn't downloaded yet: build once without resources")
    inputs.files("src/mingwX64Main/resources/gokeys.rc", "src/mingwX64Main/resources/gokeys.manifest", fileTree("branding") { include("*.ico", "fonts/gokeys_*.ttf") })
    outputs.file(resources)
    doFirst { resources.get().asFile.parentFile.mkdirs() }
    commandLine(File(mingw, "bin/windres.exe"), "src/mingwX64Main/resources/gokeys.rc", "-O", "coff", "-o", resources.get().asFile)
}
tasks.withType<KotlinNativeLink>().configureEach { dependsOn(compileResources) }

kotlin {
    // gradlew linkReleaseExecutableMingwX64 -> build/bin/mingwX64/releaseExecutable/gokeys.exe
    mingwX64 {
        binaries.executable {
            baseName = "gokeys"
            entryPoint = "app.gokeys.main"
            linkerOpts("-Wl,--subsystem,windows", resources.get().asFile.path) // tray app: no console window
        }
    }
    sourceSets {
        mingwX64Main {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.winhttp)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
