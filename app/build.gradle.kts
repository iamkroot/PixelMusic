import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    id("kotlin-parcelize")
}

// Load keystore properties early to avoid unresolved references inside the android block
val keystoreProperties = Properties().apply {
    val propFile = rootProject.file("keystore.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

val localProperties = Properties().apply {
    val propFile = rootProject.file("local.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

val defaultReleaseKeystorePath = "${System.getProperty("user.home")}/.local/share/android.jks"
val releaseKeystorePath =
    localProperties.getProperty("RELEASE_KEYSTORE_PATH")?.trim()
        ?: System.getenv("RELEASE_KEYSTORE_PATH")?.trim()
        ?: keystoreProperties.getProperty("storeFile")?.trim()
        ?: defaultReleaseKeystorePath
val releaseKeystoreFile = file(releaseKeystorePath).let {
    if (it.isFile) it else file("vz-pixelmusic.jks").let { pm -> if (pm.isFile) pm else file("vz-pixelplay.jks") }
}
val releaseStorePassword =
    localProperties.getProperty("RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("STORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: System.getenv("RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: System.getenv("STORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty("storePassword")?.takeIf { it.isNotBlank() }
val releaseKeyAlias =
    localProperties.getProperty("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("KEY_ALIAS")?.takeIf { it.isNotBlank() }
        ?: System.getenv("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
        ?: System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty("keyAlias")?.takeIf { it.isNotBlank() }
        ?: "key0"
val releaseKeyPassword =
    localProperties.getProperty("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty("keyPassword")?.takeIf { it.isNotBlank() }
        ?: releaseStorePassword
val disableReleaseSigning = providers.gradleProperty("pixelmusic.disableReleaseSigning")
    .getOrElse(providers.gradleProperty("pixelplayer.disableReleaseSigning").getOrElse("false"))
    .toBoolean()
val hasReleaseSigningConfig = !disableReleaseSigning &&
    releaseKeystoreFile.isFile &&
    releaseStorePassword != null &&
    releaseKeyPassword != null

if (hasReleaseSigningConfig) {
    println("[SIGNING] Release signing config is ACTIVE using: ${releaseKeystoreFile.absolutePath} (alias: $releaseKeyAlias)")
} else {
    println("[SIGNING] Release signing config is NOT ACTIVE (falling back to debug)")
}

val enableAbiSplits = providers.gradleProperty("pixelmusic.enableAbiSplits")
    .getOrElse("false")
    .toBoolean()

val enableComposeCompilerReports = providers.gradleProperty("pixelmusic.enableComposeCompilerReports")
    .getOrElse("false")
    .toBoolean()

val onlyArm64 = providers.gradleProperty("pixelmusic.onlyArm64")
    .getOrElse("false")
    .toBoolean()

val targetOem = providers.gradleProperty("pixelmusic.targetOem").orNull
val isVivoBuild = targetOem?.equals("vivo", ignoreCase = true) == true

@Suppress("DEPRECATION")
android {
    namespace = "com.unshoo.pixelmusic"
    compileSdk = 37

    sourceSets {
        getByName("androidTest") {
            assets.directories.add(file("$projectDir/schemas").path)
        }
    }

    androidResources {
        noCompress.add("tflite")
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "/META-INF/io.netty.versions.properties",
                "META-INF/CONTRIBUTORS.md",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md"
            )
            pickFirsts += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt"
            )
        }
        jniLibs {
            // Enable compressed JNI libraries in APK to maintain compact APK filesize
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = localProperties.getProperty("APPLICATION_ID")?.trim()
            ?: (project.findProperty("APPLICATION_ID") as? String)?.trim()
            ?: (if (isVivoBuild) "com.netease.cloudmusic" else "dev.kroot.pixelmusic")
        minSdk = 30
        targetSdk = 37
        versionCode = localProperties.getProperty("VERSION_CODE")?.toIntOrNull()
            ?: (project.findProperty("APP_VERSION_CODE") as? String)?.toInt()
            ?: 1
        versionName = localProperties.getProperty("VERSION_NAME")?.trim()
            ?: (project.findProperty("APP_VERSION_NAME") as? String)
            ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val telegramApiId = localProperties.getProperty("TELEGRAM_API_ID") ?: ""
        val telegramApiHash = localProperties.getProperty("TELEGRAM_API_HASH") ?: ""
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.ifEmpty { "0" })
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")

        val lastfmApiKey = localProperties.getProperty("LASTFM_API_KEY")
            ?: System.getenv("LASTFM_API_KEY")
            ?: ""
        val lastfmSecret = localProperties.getProperty("LASTFM_SECRET")
            ?: System.getenv("LASTFM_SECRET")
            ?: ""
        buildConfigField("String", "LASTFM_API_KEY", "\"$lastfmApiKey\"")
        buildConfigField("String", "LASTFM_SECRET", "\"$lastfmSecret\"")

        val gitCommitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() }.getOrElse("")
        buildConfigField("String", "GIT_COMMIT_HASH", "\"$gitCommitHash\"")

        // Release vs Nightly/Commit build detection:
        // Official releases have clean version names (e.g. v1.8, 1.7.10), while
        // nightly and regular commit builds include the git commit hash in About.
        val hasReleaseProperty = (project.findProperty("IS_RELEASE_BUILD") as? String)?.toBoolean()
            ?: (project.findProperty("pixelmusic.isReleaseBuild") as? String)?.toBoolean()
        val isTaggedRelease = providers.exec {
            commandLine("git", "tag", "--points-at", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { output ->
            output.lines().any { it.trim().matches(Regex("^v?[0-9].*")) }
        }.getOrElse(false)
        val isReleaseCommitMsg = providers.exec {
            commandLine("git", "log", "-1", "--pretty=%B")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { msg ->
            msg.trim().startsWith("release:", ignoreCase = true)
        }.getOrElse(false)

        val isReleaseBuild = hasReleaseProperty ?: (isTaggedRelease || isReleaseCommitMsg)
        buildConfigField("Boolean", "IS_RELEASE_BUILD", "$isReleaseBuild")

        // ── ArchiveTune Source Pool (lossless streaming) ───────────────────────────
        // SOURCE_PROVIDER_URL: base URL of the ArchivePool deployment (client appends
        //   /api/accounts, /api/sources, /api/report, /api/discovery/{tidal,qobuz}).
        // SOURCE_PROVIDER_KEY: the `atp_...` read key sent as `Authorization: Bearer`.
        //   Can also be entered at runtime in Settings → Lossless Sources (runtime value wins).
        // POOL_CLIENT_KEY: optional static 32-byte base64 AES key for legacy pool feeds; the
        //   current feed derives the key from the read key (SHA-256("archivepool-client:" + key)).
        val sourceProviderUrl =
            (
                localProperties.getProperty("SOURCE_PROVIDER_URL")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("SOURCE_PROVIDER_URL")?.takeIf { it.isNotBlank() }
                    ?: "https://archivepool.vercel.app"
                ).trim().trimEnd('/')
        buildConfigField("String", "SOURCE_PROVIDER_URL", "\"$sourceProviderUrl\"")

        val sourceProviderKey =
            (
                localProperties.getProperty("SOURCE_PROVIDER_KEY")
                    ?: System.getenv("SOURCE_PROVIDER_KEY")
                    ?: ""
                ).trim()
        buildConfigField("String", "SOURCE_PROVIDER_KEY", "\"$sourceProviderKey\"")

        val poolClientKey =
            (
                localProperties.getProperty("POOL_CLIENT_KEY")
                    ?: System.getenv("POOL_CLIENT_KEY")
                    ?: ""
                ).trim()
        buildConfigField("String", "POOL_CLIENT_KEY", "\"$poolClientKey\"")
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    flavorDimensions += listOf("distribution", "device", "abi")
    productFlavors {
        create("gms") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("String", "DISTRIBUTION", "\"gms\"")
        }
        create("foss") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"foss\"")
        }
        create("mobile") {
            dimension = "device"
            buildConfigField("String", "DEVICE", "\"mobile\"")
        }
        create("automotive") {
            dimension = "device"
            isDefault = true
            buildConfigField("String", "DEVICE", "\"automotive\"")
        }
        create("universal") {
            dimension = "abi"
            isDefault = true
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
            buildConfigField("String", "ARCHITECTURE", "\"universal\"")
        }
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
            buildConfigField("String", "ARCHITECTURE", "\"arm64\"")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            // AGP 8+ enables R8 full mode by default. Keep project-specific
            // rules in proguard-rules.pro and verify release behavior in CI.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useJUnitPlatform() }
    }

    lint {
        // BUGFIX (was: checkReleaseBuilds = false): Lint on release builds
        // catches the kind of bugs that ship into production and never
        // get seen. The build will be slightly slower because of the
        // extra checks, but on CI/laptop that's well worth it.
        checkReleaseBuilds = true
        // Treat errors as fatal but allow warnings. Tightening this to
        // `warningsAsErrors = true` would also work, but historically
        // this project has had Compose- and Material3-related lint
        // warnings that don't affect runtime behaviour (e.g. missing
        // translations, ordering of modifier calls). We surface the
        // issues for the developer to see without breaking CI.
        abortOnError = true
    }

    splits {
        abi {
            isEnable = enableAbiSplits
            reset()
            if (enableAbiSplits) {
                if (onlyArm64) {
                    include("arm64-v8a")
                    isUniversalApk = false
                } else {
                    include("arm64-v8a", "armeabi-v7a", "x86_64")
                    isUniversalApk = true
                }
            }
        }
    }

    bundle {
        abi.enableSplit = true
        density.enableSplit = true
        language.enableSplit = true
    }
}

composeCompiler {
    // StrongSkipping is now enabled by default.
}

baselineProfile {
    // Keep release builds fast to invoke locally, but make generated profiles usable as
    // startup dex-layout input once they are checked into the app.
    automaticGenerationDuringBuild = false
    saveInSrc = true
    dexLayoutOptimization = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)

        if (enableComposeCompilerReports) {
            val buildDir = project.layout.buildDirectory.get().asFile.absolutePath
            freeCompilerArgs.addAll(
                "-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=$buildDir/compose_compiler_reports",
                "-P", "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$buildDir/compose_compiler_metrics"
            )
        }

        freeCompilerArgs.addAll(
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:stabilityConfigurationPath=${project.rootDir.absolutePath}/app/compose_stability.conf"
        )
    }
}

dependencies {
    // InnerTube dependencies merged
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(libs.brotli)
    implementation(libs.re2j)
    implementation(libs.rhino)
    // Core & Optimization
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.metrics.performance) // JankStats for real frame-drop measurement
    "baselineProfile"(project(":baselineprofile"))

    // AndroidX & Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.lifecycleprocess)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.ui.text.google.fonts)
    implementation(libs.material)
    implementation(libs.androidx.appcompat)
    implementation("androidx.webkit:webkit:1.16.0")

    // DI & Navigation
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.runtime.ktx)

    // Storage & Paging
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.paging.common)

    // Media & Files
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.ffmpeg)
    implementation(libs.androidx.media3.exoplayer.midi)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.media)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.taglib)
    implementation(libs.jaudiotagger)
    implementation(libs.vorbisjava.core)
    implementation(libs.wavy.slider)
    implementation(libs.androidx.graphics.shapes)

    // Networking & Serialization
    implementation(libs.newpipe.extractor)
    implementation(libs.fuel.android)
    implementation(libs.fuel.json)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.quickjs.kt)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.gson)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    // Identity & Background
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.security.crypto)
    implementation(libs.google.play.services.cast.framework)
    implementation(libs.tdlib)

    // UI Utilities & Performance

    implementation(libs.timber)
    // Gemini uses our lightweight REST client. Avoid the deprecated official SDK because
    // it depends on Ktor 2.x and crashes when the app's Ktor 3.x stack is present.
    // implementation(libs.generativeai)
    implementation(libs.smooth.corner.rect.android.compose)
    implementation(libs.reorderables)
    implementation(libs.codeview)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.pinyin4j.core)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.accompanist.permissions)
    implementation(libs.capturable) {
        exclude(group = "androidx.compose.animation")
        exclude(group = "androidx.compose.foundation")
        exclude(group = "androidx.compose.material")
        exclude(group = "androidx.compose.runtime")
        exclude(group = "androidx.compose.ui")
    }

    // Snapchat Creative Kit
    implementation("com.snapchat.kit.sdk:creative:1.13.2")
    implementation("com.snapchat.kit.sdk:core:1.13.2")





    // Testing (Unit)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // JUnit 4 (Vintage) — required for legacy JUnit 4 tests under useJUnitPlatform()
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junitplatformlauncher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(kotlin("test"))

    // Testing (Instrumentation)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.mockk)
    androidTestImplementation(libs.worktesting)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.benchmark.macro.junit4)
    androidTestImplementation(libs.androidx.uiautomator)

    // Debug
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    constraints {
        // Fix vulnerabilities in transitive dependencies
        implementation(libs.netty.common)
        implementation(libs.netty.handler)
        implementation(libs.netty.codec.http)
        implementation(libs.netty.codec.http2)
        implementation(libs.bouncycastle.bcprov)
        implementation(libs.bouncycastle.bcpkix)
        implementation(libs.commons.lang3)
        implementation(libs.jdom2)
        implementation(libs.jose4j)
        implementation(libs.apache.httpclient)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

configurations.all {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
}
