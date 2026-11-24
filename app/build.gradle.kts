import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// CI passes -PbuildNumber=N (the release number) and, off main, -PversionSuffix=-dev.<run>:
// versionCode = N, versionName = <callscribe.version>.N<suffix> (see docs/plans/cicd.md).
val buildNumber = providers.gradleProperty("buildNumber").orNull?.toInt()
val versionSuffix = providers.gradleProperty("versionSuffix").orNull.orEmpty()
val baseVersion = providers.gradleProperty("callscribe.version").getOrElse("0.1")

// Release signing from environment variables (CI secrets); falls back to the debug key when absent.
val releaseKeystore = providers.environmentVariable("CALLSCRIBE_KEYSTORE_FILE").orNull
    ?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "app.callscribe"
    compileSdk = 37

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("CALLSCRIBE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("CALLSCRIBE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("CALLSCRIBE_KEY_PASSWORD").get()
            }
        }
    }

    defaultConfig {
        applicationId = "app.callscribe"
        minSdk = 33
        targetSdk = 36
        versionCode = buildNumber ?: 1
        versionName = if (buildNumber != null) "$baseVersion.$buildNumber$versionSuffix" else "$baseVersion.0-local"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // On-device transcription ships native libraries for 64-bit ARM only (every phone this app targets).
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded app: signed with the local debug key unless a release keystore is configured.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            // `-PminifyDebug` runs R8 on a debug build, to smoke-test keep rules with the adb debug hooks.
            if (project.hasProperty("minifyDebug")) {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * sherpa-onnx (on-device speech recognition, Apache-2.0) is not on Maven Central; its prebuilt AAR is published on
 * GitHub releases. Download it once into the Gradle user home, pinned by SHA-256 — no NDK and no binary in git.
 */
val sherpaOnnxAar: File = run {
    val version = "2.0.3"
    val sha256 = "4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50"
    val file = gradle.gradleUserHomeDir.resolve("callscribe-deps/sherpa-onnx-$version.aar")
    fun digest(f: File) = f.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    if (!file.exists() || digest(file) != sha256) {
        file.parentFile.mkdirs()
        val part = File(file.path + ".part")
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"
        logger.lifecycle("Downloading $url")
        URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        check(digest(part) == sha256) { "sherpa-onnx AAR checksum mismatch" }
        file.delete(); check(part.renameTo(file))
    }
    file
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("com.anthropic:anthropic-java:3.8.0")
    // Google sign-in for the optional Drive backup (AuthorizationClient only; Drive is called over plain REST).
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation(files(sherpaOnnxAar))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
}
