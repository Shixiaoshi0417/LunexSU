/*
 * This file is part of Sui.
 *
 * Sui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2026 Sui Contributors
 */
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import java.io.File
import java.util.Properties

buildscript {
    configurations.classpath {
        resolutionStrategy {
            force("org.bitbucket.b_c:jose4j:0.9.7")
            force("org.jdom:jdom2:2.0.6.1")
            force("org.apache.commons:commons-lang3:3.21.0")
            force("org.bouncycastle:bcpkix-jdk18on:1.86")
            force("org.bouncycastle:bcprov-jdk18on:1.86")
            force("org.bouncycastle:bcutil-jdk18on:1.86")
        }
    }
}

plugins {
    idea
    alias(libs.plugins.spotless)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.refine) apply false
}

val forcedDependencies = listOf(
    "org.bitbucket.b_c:jose4j:0.9.7",            // Fix CVE-2024-29371
    "org.jdom:jdom2:2.0.6.1",                    // Fix CVE-2021-33813
    "org.apache.commons:commons-lang3:3.21.0",  // Fix CVE-2025-48924
    "org.bouncycastle:bcpkix-jdk18on:1.86",     // Fix GHSA-8xfc-gm6g-vgpv via AGP classpath
    "org.bouncycastle:bcprov-jdk18on:1.86",
    "org.bouncycastle:bcutil-jdk18on:1.86"
)

fun propertyString(name: String): String = providers.gradleProperty(name).get()
fun propertyInt(name: String): Int = propertyString(name).toInt()

fun ApplicationExtension.configureAndroidAppDefaults() {
    compileSdk = androidCompileSdk
    buildToolsVersion = androidBuildToolsVersion
    ndkVersion = androidNdkVersion

    defaultConfig {
        minSdk = 23
        targetSdk = androidTargetSdk
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        aidl = true
    }
}

fun LibraryExtension.configureAndroidLibraryDefaults() {
    compileSdk = androidCompileSdk
    buildToolsVersion = androidBuildToolsVersion
    ndkVersion = androidNdkVersion

    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        aidl = true
    }
}

allprojects {
    configurations.configureEach {
        resolutionStrategy {
            forcedDependencies.forEach { force(it) }
        }
    }
}

val androidCompileSdk = propertyInt("android.compileSdk")
val androidTargetSdk = propertyInt("android.targetSdk")
val androidBuildToolsVersion = propertyString("android.buildToolsVersion")
val androidNdkVersion = propertyString("android.ndkVersion")

idea {
    module {
        excludeDirs = excludeDirs + file("out")
        resourceDirs = resourceDirs + file("template") + file("scripts")
    }
}

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension>("android") {
            configureAndroidAppDefaults()
        }
    }

    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension>("android") {
            configureAndroidLibraryDefaults()
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Xlint:deprecation")
    }
}

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX

    java {
        target("**/src/*/java/**/*.java")
        targetExclude("**/build/**")
        palantirJavaFormat()
        importOrder()
        removeUnusedImports()
        formatAnnotations()
    }

    kotlin {
        target("**/src/*/kotlin/**/*.kt", "**/src/*/java/**/*.kt")
        targetExclude("**/build/**")
        ktlint().editorConfigOverride(mapOf(
            "ktlint_standard_max-line-length" to "disabled"
        ))
    }
    format("cpp") {
        target("**/src/main/cpp/**/*.c", "**/src/main/cpp/**/*.cpp", "**/src/main/cpp/**/*.h", "**/src/main/cpp/**/*.hpp")
        targetExclude("**/build/**")

        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { localProperties.load(it) }
        }

        var sdkDir = localProperties.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")

        if (sdkDir.isNullOrEmpty()) {
            val commonPaths = listOf("/opt/android-sdk", "/usr/local/lib/android/sdk")
            sdkDir = commonPaths.firstOrNull { file(it).exists() } ?: ""
        }

        val osName = System.getProperty("os.name").lowercase()
        val platform = when {
            "linux" in osName -> "linux-x86_64"
            "mac" in osName -> "darwin-x86_64"
            else -> "windows-x86_64"
        }

        var clangPath = "$sdkDir/ndk/$androidNdkVersion/toolchains/llvm/prebuilt/$platform/bin/clang-format"
        if ("windows" in osName) clangPath += ".exe"

        if (file(clangPath).exists()) {
            clangFormat("21.0.0").style("file").pathToExe(clangPath)
        } else {
            println("Spotless Warning: Clang-format not found at $clangPath")
            clangFormat().style("file")
        }
    }
}

tasks.register("format") {
    dependsOn("spotlessApply")
    group = "formatting"
    description = "Formats the code using Spotless"
}

// Managed release provenance is captured before any release compilation. A
// subsequent packaging command can verify this record, but cannot attest an old
// ZIP against whatever source happens to be checked out at packaging time.
val provenanceScript = rootProject.file("../../scripts/sui_build_provenance.py")
val provenanceSnapshot = layout.buildDirectory.file("sui-provenance/release-inputs.json")
val provenancePython = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
fun provenanceArguments(phase: String, archive: File): List<String> {
    val arguments = mutableListOf(
        provenancePython, provenanceScript.absolutePath, phase,
        "--source", rootDir.absolutePath,
        "--snapshot", provenanceSnapshot.get().asFile.absolutePath,
        "--archive", archive.absolutePath,
    )
    mapOf(
        "lunexsu.managerPackage" to "--manager-package",
        "lunexsu.managerCertSize" to "--manager-size",
        "lunexsu.managerCertSha256" to "--manager-hash",
    ).forEach { (property, option) ->
        providers.gradleProperty(property).orNull?.let { arguments.addAll(listOf(option, it)) }
    }
    return arguments
}

val captureReleaseInputs = tasks.register<Exec>("captureSuiReleaseInputs") {
    outputs.file(provenanceSnapshot)
    outputs.upToDateWhen { false }
    doFirst {
        val local = Properties()
        rootProject.file("local.properties").takeIf(File::isFile)?.inputStream()?.use(local::load)
        val api = if (local.getProperty("api.useLocal") == "true") {
            rootProject.file(local.getProperty("api.dir") ?: "api")
        } else rootProject.file("api")
        require(api.canonicalFile == rootProject.file("api").canonicalFile) {
            "Managed Sui provenance requires the vendored API source"
        }
        val archive = project(":module").tasks.named<Zip>("zipRelease").get().archiveFile.get().asFile
        commandLine(provenanceArguments("begin", archive))
    }
}

captureReleaseInputs.configure {
    mustRunAfter(rootProject.allprojects.map { project -> project.tasks.matching { it.name == "clean" } })
}
allprojects {
    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        dependsOn(captureReleaseInputs)
    }
}
project(":module").tasks.withType<Zip>().configureEach {
    if (name == "zipRelease") {
        dependsOn(captureReleaseInputs)
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Provenance belongs to this source-build invocation") { true }
        doLast {
            // assembleRelease also finalizes zipRelease after a failed build.
            check(gradle.taskGraph.allTasks.none { it.state.failure != null }) {
                "Cannot publish Sui provenance after a failed build"
            }
            providers.exec {
                commandLine(provenanceArguments("finish", archiveFile.get().asFile))
            }.result.get().assertNormalExitValue()
        }
    }
}
