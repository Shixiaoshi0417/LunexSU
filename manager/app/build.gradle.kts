@file:Suppress("UnstableApiUsage")

import com.google.protobuf.gradle.id
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.lsplugin.apksign)
    alias(libs.plugins.protobuf)
    id("kotlin-parcelize")
}

val androidCompileSdkVersion = rootProject.extra["androidCompileSdkVersion"] as Int
val androidCompileSdkVersionMinor = rootProject.extra["androidCompileSdkVersionMinor"] as Int
val androidCompileNdkVersion = rootProject.extra["androidCompileNdkVersion"] as String
val androidBuildToolsVersion = rootProject.extra["androidBuildToolsVersion"] as String
val androidMinSdkVersion = rootProject.extra["androidMinSdkVersion"] as Int
val androidTargetSdkVersion = rootProject.extra["androidTargetSdkVersion"] as Int
val androidSourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
val androidTargetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion
val managerVersionCode = rootProject.extra["managerVersionCode"] as Int
val managerVersionName = rootProject.extra["managerVersionName"] as String

val isPrBuild = project.findProperty("IS_PR_BUILD")?.toString()?.toBoolean() ?: false
val noRootDemo = providers.gradleProperty("NO_ROOT_DEMO").map(String::toBooleanStrict).getOrElse(false)
val defaultManagerPackageName = if (isPrBuild) "com.shixiaoshi0417.lunexsu.pr" else "com.shixiaoshi0417.lunexsu"
val defaultManagerName = if (isPrBuild) "LunexSU PR" else "LunexSU"
val managerPackageName = if (noRootDemo) "com.shixiaoshi0417.lunexsu.demo" else
    project.findProperty("KSU_PACKAGE_NAME")?.toString() ?: defaultManagerPackageName
val managerName = if (noRootDemo) "LunexSU No Root Demo" else
    project.findProperty("KSU_NAME")?.toString() ?: defaultManagerName

// Configuring this plugin without a keystore signs every variant with its debug
// fallback. PR release artifacts must stay unsigned, even if key properties exist.
if (!isPrBuild) {
    apksign {
        storeFileProperty = "KEYSTORE_FILE"
        storePasswordProperty = "KEYSTORE_PASSWORD"
        keyAliasProperty = "KEY_ALIAS"
        keyPasswordProperty = "KEY_PASSWORD"
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        ofNonTest().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

val baseCFlags = listOf(
    "-Wall", "-Qunused-arguments", "-fvisibility=hidden", "-fvisibility-inlines-hidden",
    "-fno-exceptions", "-fno-stack-protector", "-fomit-frame-pointer",
    "-Wno-builtin-macro-redefined", "-Wno-unused-value", "-D__FILE__=__FILE_NAME__"
)
val baseCppFlags = baseCFlags + "-fno-rtti"

// Built-in Zygisk payloads are pinned by SHA-256. NextNext's ZIP container is normalized
// without changing payload bytes; sources and hashes are listed in docs/lunexsu-zygisk.md.
data class PinnedAsset(val url: String, val sha256: String)

val reZygiskZip = PinnedAsset(
    "https://github.com/PerformanC/ReZygisk/releases/download/v1.0.0/ReZygisk-v1.0.0-release.zip",
    "7904649b8dcaf2b060c3432df4fee302aeb1258da199d2b425335e82d49510e6",
)
val neoZygiskZip = PinnedAsset(
    "https://github.com/JingMatrix/NeoZygisk/releases/download/v2.4/NeoZygisk-v2.4-289-release.zip",
    "93a1425c67bb89f58a0dcb9fc823ec93f6472214f221e667a70b67c6a6e061a1",
)
val nextNextZip = PinnedAsset(
    "https://github.com/VeryBaaad/ZygiskNextNext/releases/download/v1.12.0/Zygisk-Next-Next-v1.12.0-202-ca4d4b7-release.zip",
    "d7bf2cc7eda22fa2e918029eddf71f2b368c3613f6cbf671acb21e38af498d74",
)
val nextNextStoredSha256 = "e818e03c522a6c1e5e513c76aa8e365589b2f17d87c0085dc5b348dee1f7330e"
val zygiskAssets = mapOf(
    "zygisk/re.zip" to reZygiskZip,
    "zygisk/re-LICENSE" to PinnedAsset(
        "https://raw.githubusercontent.com/PerformanC/ReZygisk/333d423cde1a959958d9fce380bd017cf6c1cf64/LICENSE",
        "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
    ),
    "zygisk/neo.zip" to neoZygiskZip,
    "zygisk/neo-LICENSE" to PinnedAsset(
        "https://raw.githubusercontent.com/JingMatrix/NeoZygisk/08080ef775c7fbbd1ad5c4a5d837f81933437abd/LICENSE",
        "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
    ),
    "zygisk/nextnext.zip" to nextNextZip,
    "zygisk/nextnext-LICENSE" to PinnedAsset(
        "https://raw.githubusercontent.com/VeryBaaad/ZygiskNextNext/ca4d4b7a5dd06f83c228ec23cb25ea49dfe5c8f2/LICENSE",
        "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
    ),
)

/** Downloads pinned files once into a cache outside `build/` and stages them as assets. */
abstract class FetchPinnedAssets : DefaultTask() {
    /** Asset path to "url sha256". */
    @get:Input
    abstract val files: MapProperty<String, String>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:InputFile
    abstract val normalizer: RegularFileProperty

    @get:Input
    abstract val normalizedSha256: Property<String>

    @TaskAction
    fun fetch() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        files.get().forEach { (path, spec) ->
            val (url, sha256) = spec.split(' ')
            val cached = cacheDir.get().file(sha256).asFile
            if (!cached.isFile || sha256Of(cached) != sha256) {
                cached.parentFile.mkdirs()
                val partial = File(cached.path + ".part")
                val connection = URI(url).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                }
                connection.getInputStream().use { input ->
                    partial.outputStream().use { input.copyTo(it) }
                }
                val actual = sha256Of(partial)
                if (actual != sha256) {
                    partial.delete()
                    throw GradleException("$url: expected SHA-256 $sha256, got $actual")
                }
                Files.move(partial.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val destination = File(out, path)
            destination.parentFile.mkdirs()
            if (path == "zygisk/nextnext.zip") {
                val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
                val process = ProcessBuilder(python, normalizer.get().asFile.absolutePath, cached.absolutePath, destination.absolutePath)
                    .inheritIO().start()
                if (process.waitFor() != 0 || sha256Of(destination) != normalizedSha256.get()) {
                    throw GradleException("ZygiskNextNext ZIP normalization failed")
                }
            } else {
                cached.copyTo(destination, overwrite = true)
            }
        }
    }

    private fun sha256Of(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}

val fetchZygiskAssets = tasks.register<FetchPinnedAssets>("fetchZygiskAssets") {
    files.set(zygiskAssets.mapValues { (_, asset) -> "${asset.url} ${asset.sha256}" })
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/pinned-assets"))
    outputDir.set(layout.buildDirectory.dir("generated/zygisk/assets"))
    normalizer.set(rootProject.layout.projectDirectory.file("../scripts/normalize_zygisk_zip.py"))
    normalizedSha256.set(nextNextStoredSha256)
}

// AGP's built-in Kotlin source-directory API has no per-file exclusion filter.
// Stage a filtered, disposable source tree instead of compiling live backends in the demo.
val noRootDemoSources = if (noRootDemo) tasks.register<Sync>("stageNoRootDemoSources") {
    from("src/main/java") {
        exclude("**/Natives.kt", "**/ui/util/KsuCli.kt", "**/data/zygisk/BuiltinZygisk.kt",
            "**/data/sui/SuiManager.kt",
            "**/data/repository/SuperUserRepositoryImpl.kt", "**/data/susfs/SusfsRepository.kt",
            "**/ui/KsuService.kt", "**/magica/AppZygotePreload.java", "**/magica/BootCompletedReceiver.java")
    }
    from("src/noRootDemo/java")
    into(layout.buildDirectory.dir("generated/noRootDemo/sources"))
} else null

val noRootDemoAidl = if (noRootDemo) tasks.register<Sync>("stageNoRootDemoAidl") {
    from("src/main/aidl") { exclude("moe/shizuku/**") }
    into(layout.buildDirectory.dir("generated/noRootDemo/aidl"))
} else null

android {
    namespace = "com.shixiaoshi0417.lunexsu"

    if (noRootDemo) {
        sourceSets.named("main") {
            aidl.directories.clear()
            aidl.directories.add(layout.buildDirectory.dir("generated/noRootDemo/aidl").get().asFile.absolutePath)
            val staged = layout.buildDirectory.dir("generated/noRootDemo/sources").get().asFile.absolutePath
            java.directories.clear()
            java.directories.add(staged)
            kotlin.directories.clear()
            kotlin.directories.add(staged)
            manifest.srcFile("src/noRootDemo/AndroidManifest.xml")
        }
        sourceSets.named("test") { kotlin.directories.add(file("src/noRootDemoTest/java").absolutePath) }
        providers.gradleProperty("NO_ROOT_DEMO_KEYSTORE").orNull?.let { path ->
            signingConfigs.getByName("debug").storeFile = file(path)
        }
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DCMAKE_CXX_FLAGS_DEBUG=-Og", "-DCMAKE_C_FLAGS_DEBUG=-Og")
                }
            }
        }
        release {
            if (noRootDemo) signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            externalNativeBuild {
                cmake {
                    arguments += "-DDEBUG_SYMBOLS_PATH=${layout.buildDirectory.get().asFile.absolutePath}/symbols"
                    arguments += "-DCMAKE_BUILD_TYPE=Release"

                    val releaseFlags = listOf(
                        "-flto", "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections",
                        "-fno-unwind-tables", "-fno-asynchronous-unwind-tables", "-Wl,--exclude-libs,ALL"
                    )
                    val configFlags = listOf("-Oz", "-DNDEBUG").joinToString(" ")

                    cppFlags += releaseFlags
                    cFlags += releaseFlags

                    arguments += listOf(
                        "-DCMAKE_CXX_FLAGS_RELEASE=$configFlags",
                        "-DCMAKE_C_FLAGS_RELEASE=$configFlags",
                        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--icf=all -s -Wl,--hash-style=sysv -Wl,-z,norelro"
                    )
                }
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        resValues = true
        compose = true
        prefab = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/*/libandroidx.graphics.path.so"
            if (noRootDemo) excludes += listOf("lib/*/libksud.so", "lib/*/liblunexsu.so")
        }
    }

    if (!noRootDemo) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        generateLocaleConfig = true
    }
    compileSdk {
        version =
            release(androidCompileSdkVersion) {
                minorApiLevel = androidCompileSdkVersionMinor
            }
    }
    buildToolsVersion = androidBuildToolsVersion
    ndkVersion = androidCompileNdkVersion

    defaultConfig {
        testInstrumentationRunner = "com.shixiaoshi0417.lunexsu.AuthorizationTestRunner"
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = managerVersionCode
        versionName = managerVersionName + if (noRootDemo) "-no-root-demo" else ""
        applicationId = managerPackageName

        buildConfigField("boolean", "IS_PR_BUILD", isPrBuild.toString())
        buildConfigField("boolean", "NO_ROOT_DEMO", noRootDemo.toString())
        buildConfigField("String", "ZYGISK_RE_SHA256", "\"${reZygiskZip.sha256}\"")
        buildConfigField("String", "ZYGISK_NEO_SHA256", "\"${neoZygiskZip.sha256}\"")
        buildConfigField("String", "ZYGISK_NEXTNEXT_SHA256", "\"$nextNextStoredSha256\"")
        resValue("string", "app_name", managerName)

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                cFlags += baseCFlags + "-std=c2x"
                cppFlags += baseCppFlags + "-std=c++2b"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "riscv64")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = androidSourceCompatibility
        targetCompatibility = androidTargetCompatibility
    }
}

androidComponents {
    onVariants {
        if (!noRootDemo) it.sources.assets?.addGeneratedSourceDirectory(fetchZygiskAssets, FetchPinnedAssets::outputDir)
    }
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes.addAll(listOf("META-INF/**", "kotlin/**", "**.bin"))
    }
}

noRootDemoSources?.let { sources -> tasks.named("preBuild") { dependsOn(sources) } }
noRootDemoAidl?.let { sources -> tasks.named("preBuild") { dependsOn(sources) } }

base {
    archivesName.set(
        "${managerName.replace(" ", "_")}_${managerVersionName}_${managerVersionCode}"
    )
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigationevent.compose)

    implementation(libs.com.github.topjohnwu.libsu.core)
    implementation(libs.com.github.topjohnwu.libsu.service)
    implementation(libs.com.github.topjohnwu.libsu.io)

    implementation(libs.dev.rikka.rikkax.parcelablelist)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)

    implementation(libs.androidx.webkit)

    implementation(libs.lsposed.cxx)

    implementation(libs.hiddenapibypass)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.liquid.android)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    implementation(libs.material.kolor)

    implementation(libs.appiconloader)

    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.protobuf.kotlin.lite)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}
