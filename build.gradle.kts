import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The version (gradle.properties) as a Kotlin constant and as the exe's version info.
val versionName = version.toString()
val generated = layout.buildDirectory.dir("generated/version")
val generateVersion = tasks.register("generateVersion") {
    inputs.property("version", versionName)
    outputs.dir(generated)
    doLast {
        val (major, minor, patch) = (versionName.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)
        val dir = generated.get().asFile
        File(dir, "kotlin/app/gokeys/Version.kt").apply { parentFile.mkdirs() }
            .writeText("package app.gokeys\n\n/** From gradle.properties. */\ninternal const val VERSION = \"$versionName\"\n")
        File(dir, "res/version.rc").apply { parentFile.mkdirs() }.writeText(
            """
            1 VERSIONINFO
            FILEVERSION $major,$minor,$patch,0
            PRODUCTVERSION $major,$minor,$patch,0
            FILEOS 0x40004
            FILETYPE 0x1
            BEGIN
              BLOCK "StringFileInfo"
              BEGIN
                BLOCK "040904B0"
                BEGIN
                  VALUE "CompanyName", "govrse"
                  VALUE "FileDescription", "gokeys"
                  VALUE "FileVersion", "$versionName"
                  VALUE "InternalName", "gokeys"
                  VALUE "OriginalFilename", "gokeys.exe"
                  VALUE "ProductName", "gokeys"
                  VALUE "ProductVersion", "$versionName"
                END
              END
              BLOCK "VarFileInfo"
              BEGIN
                VALUE "Translation", 0x409, 1200
              END
            END
            """.trimIndent() + "\n",
        )
    }
}

// Icons and version info for the exe (Explorer, the tray, Task Manager), compiled with the windres Kotlin/Native ships.
val resources = layout.buildDirectory.file("res/gokeys.res")
val compileResources = tasks.register<Exec>("compileResources") {
    val konan = File(System.getenv("KONAN_DATA_DIR") ?: "${System.getProperty("user.home")}/.konan", "dependencies")
    val mingw = konan.listFiles { f -> f.name.startsWith("msys2-mingw-w64-x86_64") }?.maxOrNull()
        ?: error("Kotlin/Native's mingw toolchain isn't downloaded yet: build once without resources")
    inputs.files("src/mingwX64Main/resources/gokeys.rc", "src/mingwX64Main/resources/gokeys.manifest", fileTree("branding") { include("*.ico", "fonts/gokeys_*.ttf") })
    inputs.files(generateVersion)
    outputs.file(resources)
    doFirst { resources.get().asFile.parentFile.mkdirs() }
    commandLine(
        File(mingw, "bin/windres.exe"), "src/mingwX64Main/resources/gokeys.rc",
        "-I", generated.get().dir("res").asFile, "-O", "coff", "-o", resources.get().asFile,
    )
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
            kotlin.srcDir(generateVersion.map { generated.get().dir("kotlin") })
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.winhttp)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
