import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.therapylocal.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.therapylocal.app"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Shared debug key: every laptop and CI signs the same, so updates install over the old app and keep the downloaded model
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // jvmTarget is set via the kotlin { compilerOptions } block below
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // The Compose runtime lint jar bundles an old kotlinx-metadata and crashes on Kotlin 2.4 types (LiteRT-LM needs Kotlin 2.4).
        // These two detectors read Flow metadata. Re-enable once androidx ships lint built against newer metadata.
        disable += listOf("FlowOperatorInvokedInComposition", "StateFlowValueCalledInComposition")
    }
    packaging { jniLibs { useLegacyPackaging = true } }

    buildTypes.configureEach {
        // Transcript text in logs. Keep false: this is a therapy app. See docs/LOGGING.md.
        buildConfigField("boolean", "LOG_TEXT", "false")
    }
}

// sherpa-onnx (Whisper runtime) is a GitHub release asset, not on Maven. Fetch and verify it instead of committing 49 MB.
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-2.0.0.aar").asFile
val sherpaSha256 = "f0f8716982d27cb4ce695e3934018b1342d9d3d6fdb6ef1f1658fe8bad50b4fc"
val fetchSherpa by tasks.registering {
    outputs.file(sherpaAar)
    doLast {
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (!sherpaAar.exists() || sha(sherpaAar) != sherpaSha256) {
            sherpaAar.parentFile.mkdirs()
            URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.0/sherpa-onnx-2.0.0.aar")
                .toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
            check(sha(sherpaAar) == sherpaSha256) { "sherpa-onnx AAR checksum mismatch" }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchSherpa) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation(files("libs/sherpa-onnx-2.0.0.aar"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
