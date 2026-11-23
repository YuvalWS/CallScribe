plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// CI passes -PbuildNumber=<run number>: versionCode = N, versionName = <callscribe.version>.N (see docs/plans/cicd.md).
val buildNumber = providers.gradleProperty("buildNumber").orNull?.toInt()
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
        versionName = if (buildNumber != null) "$baseVersion.$buildNumber" else "$baseVersion.0-local"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
