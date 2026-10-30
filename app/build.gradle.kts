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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // The CI emulator is x86_64. Debug and androidTest only; release stays arm64-v8a.
        debug {
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = false
        }
        // Release speed with the shared debug key, so it installs over a debug build and keeps the models. Debug Compose builds stutter,
        // so motion and haptics are tuned on this one: ./gradlew :app:assembleFeel
        create("feel") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        // The feel build for driving the app from adb: its own app id (its own vault, beside the real app, which it never touches), no
        // fingerprint so a script can unlock with the password, and screenshots allowed (test data only). It downloads its own models.
        // ./gradlew :app:assembleQa
        create("qa") {
            initWith(getByName("feel"))
            applicationIdSuffix = ".qa"
            matchingFallbacks += listOf("feel", "release")
            buildConfigField("boolean", "BIOMETRIC", "false")
            buildConfigField("boolean", "SECURE_WINDOW", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // jvmTarget is set via the kotlin { compilerOptions } block below
    // The memory prompts ship in the app (assets/prompts/), so processing works on a fresh install without pushing files.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/promptAssets"))
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // The Compose runtime lint jar bundles an old kotlinx-metadata and crashes on Kotlin 2.4 types (LiteRT-LM needs Kotlin 2.4).
        // The detectors of that jar read Kotlin metadata and each crashes in turn on our Compose code, so all of androidx.compose.runtime's
        // lint ids (runtime-lint 1.6.8) are off. Re-enable once androidx ships lint built against newer metadata.
        disable += listOf(
            "AutoboxingStateCreation", "AutoboxingStateValueProperty", "ComposableLambdaParameterNaming", "ComposableLambdaParameterPosition",
            "ComposableNaming", "CompositionLocalNaming", "CoroutineCreationDuringComposition", "FlowOperatorInvokedInComposition",
            "MutableCollectionMutableState", "OpaqueUnitKey", "ProduceStateDoesNotAssignValue", "RememberReturnType",
            "StateFlowValueCalledInComposition", "UnrememberedMutableState",
        )
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    // JVM tests run code that logs through AppLog (android.util.Log); the stubs then return defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }

    buildTypes.configureEach {
        // Transcript text in logs. Keep false: this is a therapy app. See docs/LOGGING.md.
        buildConfigField("boolean", "LOG_TEXT", "false")
        if (name != "qa") {
            buildConfigField("boolean", "BIOMETRIC", "true")
            buildConfigField("boolean", "SECURE_WINDOW", "true")
        }
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
val promptAssets by tasks.registering(Copy::class) {
    from(rootProject.file("demo/prompts"))
    into(layout.buildDirectory.dir("generated/promptAssets/prompts"))
}
tasks.named("preBuild") { dependsOn(fetchSherpa, promptAssets) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1") // installs Compose's baseline profiles on sideloaded builds: smooth first frames
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation(files("libs/sherpa-onnx-2.0.0.aar"))
    implementation("net.zetetic:sqlcipher-android:4.12.0@aar") // encrypted SQLite for recordings.db and memory.db (patient data at rest)
    implementation("androidx.sqlite:sqlite:2.4.0") // SupportSQLite types required by sqlcipher-android
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2") // FragmentActivity for BiometricPrompt
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // the Android stub of org.json does nothing in unit tests
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest") // hosts the test activity for createComposeRule
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
