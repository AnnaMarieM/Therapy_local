import java.net.URI
import java.util.zip.ZipFile
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The one place the app's name lives: the launcher label, notifications and every screen read it as @string/app_name.
val appName = "MnemoThera"

// AD-04: libquietcrash.so, the native handler that makes an engine host exit quietly on a fatal signal. One C file, compiled with the pinned
// NDK clang by the tasks below (no CMake: see docker/Dockerfile for why). Same flags and the fixed /workspace path give the same bytes on x86_64
// and arm64 builders. arm64-v8a goes in every APK; x86_64 only in debug (the CI emulator).
val quietNdkVersion = "27.2.12479018" // keep equal to NDK_VERSION in docker/Dockerfile
val quietOut = layout.buildDirectory.dir("quietcrash")
val quietTasks = listOf("arm64-v8a" to "aarch64-linux-android31", "x86_64" to "x86_64-linux-android31").map { (abi, triple) ->
    tasks.register<Exec>("buildQuietCrash_$abi") {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: ""
        val clang = File(sdk, "ndk/$quietNdkVersion/toolchains/llvm/prebuilt/linux-x86_64/bin/clang")
        val src = file("src/main/cpp/quietcrash.c")
        // debug-only ABIs go in their own folder so the main source set never carries them into a release APK
        val outFile = quietOut.get().dir(if (abi == "x86_64") "debugOnly/$abi" else "main/$abi").file("libquietcrash.so").asFile
        val obj = File(quietOut.get().asFile, "obj/$abi/quietcrash.o")
        inputs.file(src)
        inputs.property("abi", abi)
        inputs.property("ndk", quietNdkVersion)
        outputs.file(outFile)
        workingDir = projectDir
        onlyIf { !outFile.exists() }
        doFirst {
            check(clang.canExecute()) { "NDK $quietNdkVersion not found at $clang: build with scripts/build.sh (the pinned image has it)" }
            outFile.parentFile.mkdirs()
            obj.parentFile.mkdirs()
        }
        // Two steps: with compile and link in one clang call the driver starts the compiler as a child process, which the x86_64 launcher
        // trick under qemu-user (arm64 builders) cannot do. A separate -c runs the compiler inside the driver process.
        executable = "sh"
        args(
            "-c", "\"\$0\" \"\$@\" -c -o \"\$OBJ\" && \"\$0\" --target=$triple -shared -Wl,--build-id=none -Wl,-soname,libquietcrash.so " +
                "-Wl,-z,max-page-size=16384 -Wl,-z,noexecstack -Wl,--no-undefined -s -o \"\$OUT\" \"\$OBJ\"",
            clang.path,
            "--target=$triple", "-fPIC", "-O2", "-std=c11", "-Wall", "-Wextra", "-Werror", "-fvisibility=hidden", "-fno-ident", "-g0",
            "-ffile-prefix-map=${rootDir.path}=/workspace", "src/main/cpp/quietcrash.c",
        )
        environment("OBJ", obj.path)
        environment("OUT", outFile.path)
    }
}

// AGP does not follow builtBy of a srcDir, so everything that reads the jniLibs folders waits for the library explicitly.
tasks.configureEach {
    if (name.endsWith("JniLibFolders") || name.startsWith("lint")) dependsOn(quietTasks)
}

val gitCommit: String = System.getenv("GIT_COMMIT")?.takeIf { it.isNotBlank() }?.take(40)
    ?: runCatching {
        ProcessBuilder("git", "rev-parse", "--short=12", "HEAD").directory(rootDir).redirectErrorStream(true).start()
            .let { p -> p.inputStream.bufferedReader().readText().trim().also { p.waitFor() } }
    }.getOrNull()?.takeIf { Regex("[0-9a-f]{7,40}").matches(it) } ?: "unknown"

android {
    namespace = "com.therapylocal.app"
    compileSdk = 34
    buildToolsVersion = "35.0.0" // the build tools of docker/Dockerfile (BUILD_TOOLS), so the pinned image never downloads another
    ndkVersion = quietNdkVersion // the NDK of docker/Dockerfile (NDK_VERSION); compiles libquietcrash.so (AD-04)

    defaultConfig {
        applicationId = "com.therapylocal.app"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "app_name", appName)
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
            // The commit this APK was built from, for the control API's `status` (AD-16). GIT_COMMIT from the environment (scripts/build.sh passes it
            // into the container, where there is no usable .git), else git, else "unknown". Only in qa: no other build type has the field.
            buildConfigField("String", "GIT_COMMIT", "\"" + gitCommit + "\"")
            // A launcher label that differs from the real app. The QA launcher icon itself lives in src/qa/res (docs/icon-qa.svg).
            resValue("string", "app_name", "$appName QA")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // jvmTarget is set via the kotlin { compilerOptions } block below
    // The memory prompts ship in the app (assets/prompts/), so processing works on a fresh install without pushing files.
    sourceSets.getByName("main").jniLibs.srcDir(files(quietOut.map { it.dir("main") }).builtBy(quietTasks[0]))
    sourceSets.getByName("debug").jniLibs.srcDir(files(quietOut.map { it.dir("debugOnly") }).builtBy(quietTasks[1]))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/promptAssets"))
    // The fault seam: debug has the real Faults (src/debug); every other build type gets the no-op copy.
    for (bt in listOf("release", "feel", "qa")) sourceSets.getByName(bt).java.srcDir("src/noFaults/java")
    // One copy of the log-story helpers (LogTape, assertDeadClear, LogFileTape) for the JVM tests and the device tests (catalogue finding 30).
    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
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
    // Keep the libraries as they come: no stripping with the NDK (the prebuilt ones were never stripped here), so adding the NDK changes none of them.
    // qnn-runtime also carries the QNN GPU and DSP backends and the Hexagon V66/V68 skels, none of which LiteRT's Qualcomm dispatch uses (about 135 MB).
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so"; excludes += listOf("**/libQnnGpu.so", "**/libQnnDsp*.so", "**/libQnnHtpV68*.so") } }
    // JVM tests run code that logs through AppLog (android.util.Log); the stubs then return defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }

    buildTypes.configureEach {
        // Transcript text in logs. True ONLY in qa (test data only); false in debug, feel and release. See docs/LOGGING.md.
        buildConfigField("boolean", "LOG_TEXT", if (name == "qa") "true" else "false")
        // AD-01 emergency switch: true forces every model back into the main process (LocalEngineHost). Default false.
        buildConfigField("boolean", "ENGINE_IN_PROCESS", "false")
        // A decrypted wav of each retained recording, for debugging with adb (see DebugAudio). Never in a release.
        buildConfigField("boolean", "EXPORT_AUDIO", if (name == "debug" || name == "qa") "true" else "false")
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
    // Qualcomm NPU (AD-06). The dispatch library and compiler plugin from Google, the QNN/HTP runtime from Qualcomm: ONE QAIRT version (2.50.0),
    // the one the Gemma NPU bundles on Hugging Face were built with. A different pair gives "Unsupported context binary version".
    implementation("com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.50.0")
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

// Gate 1 of the fault lab (AD-01, plan S11): no fault code in an APK that ships. Scans the dex files of the release, feel and qa
// (applicationIdSuffix .qa) APKs for the lab classes and for the format marker in the dex string table, so it still holds after a later
// minify renames classes. Fails the build on a hit. `-PfaultScanApks=a.apk,b.apk` scans those files instead (to prove the check bites:
// scan the debug APK and it must fail).
val verifyNoFaultsInRelease by tasks.registering {
    group = "verification"
    description = "Fails if the release, feel or qa APK contains the fault lab (classes or the FAULT_PLAN_V1 marker)."
    val override = providers.gradleProperty("faultScanApks")
    if (!override.isPresent) dependsOn("assembleRelease", "assembleFeel", "assembleQa")
    val outDir = layout.buildDirectory.dir("outputs/apk")
    doLast {
        val forbidden = listOf("FaultPlan", "FakeRunners", "EngineLabKills", "EngineLabActivity", "EngineLabService", "HostOnlyException", "FAULT_PLAN_V1")
        val apks: List<File> = if (override.isPresent) override.get().split(',').map { File(it) }
        else listOf("release", "feel", "qa").flatMap { bt -> outDir.get().dir(bt).asFile.walkTopDown().filter { it.extension == "apk" }.toList() }
        check(apks.isNotEmpty()) { "verifyNoFaultsInRelease: no APK found" }
        val hits = ArrayList<String>()
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                val dex = zip.entries().toList().filter { Regex("classes\\d*\\.dex").matches(it.name) }
                check(dex.isNotEmpty()) { "${apk.name}: no dex file" }
                for (e in dex) {
                    val bytes = zip.getInputStream(e).readBytes()
                    for (word in forbidden) {
                        val needle = word.toByteArray(Charsets.US_ASCII)
                        outer@ for (i in 0..bytes.size - needle.size) {
                            if (bytes[i] != needle[0]) continue
                            for (j in 1 until needle.size) if (bytes[i + j] != needle[j]) continue@outer
                            hits += "${apk.name}!${e.name}: $word"
                            break
                        }
                    }
                }
            }
        }
        if (hits.isNotEmpty()) throw GradleException("Fault lab code found in a non-debug APK:\n  " + hits.joinToString("\n  "))
        println("verifyNoFaultsInRelease: ${apks.joinToString { it.name }} are clean")
    }
}

// The qa unit tests (src/testQa) cover the QA control API; src/test already runs under testDebugUnitTest, so it is not run a second time here.
tasks.matching { it.name == "testQaUnitTest" }.configureEach { (this as Test).filter.includeTestsMatching("com.therapylocal.app.qaapi.*") }

// AD-16: the QA control API (src/qa, package com.therapylocal.app.qaapi) must be in the qa APK and in no other. Scans the dex files and the binary
// manifest of the release and feel APKs for its marker strings (the check fails on any hit), and requires them in the qa APK, which proves the
// scan can see them. Runs in the `build` job beside verifyNoFaultsInRelease (same assemble tasks, so the critical path does not grow).
// `-PqaApiScanApks=a.apk,b.apk` scans those files as the non-qa set instead (to prove the check bites: scan the qa APK and it must fail).
val verifyNoQaApiInRelease by tasks.registering {
    group = "verification"
    description = "Fails if the release or feel APK contains the QA control API (classes, provider, strings), or if the qa APK lacks it."
    val override = providers.gradleProperty("qaApiScanApks")
    if (!override.isPresent) dependsOn("assembleRelease", "assembleFeel", "assembleQa")
    val outDir = layout.buildDirectory.dir("outputs/apk")
    doLast {
        val markers = listOf("QaApi", "qaapi", "STANDARD_QA_PASSWORD")
        fun scan(apk: File): List<String> {
            val hits = ArrayList<String>()
            ZipFile(apk).use { zip ->
                val parts = zip.entries().toList().filter { Regex("classes\\d*\\.dex").matches(it.name) || it.name == "AndroidManifest.xml" }
                check(parts.any { it.name.endsWith(".dex") }) { "${apk.name}: no dex file" }
                for (e in parts) {
                    val bytes = zip.getInputStream(e).readBytes()
                    for (m in markers) for (needle in listOf(m.toByteArray(Charsets.US_ASCII), m.toByteArray(Charsets.UTF_16LE))) {
                        outer@ for (i in 0..bytes.size - needle.size) {
                            if (bytes[i] != needle[0]) continue
                            for (j in 1 until needle.size) if (bytes[i + j] != needle[j]) continue@outer
                            hits += "${apk.name}!${e.name}: $m"
                            break
                        }
                    }
                }
            }
            return hits
        }
        fun apksOf(bt: String) = outDir.get().dir(bt).asFile.walkTopDown().filter { it.extension == "apk" }.toList()
        val others: List<File> = if (override.isPresent) override.get().split(',').map { File(it) } else listOf("release", "feel").flatMap { apksOf(it) }
        check(others.isNotEmpty()) { "verifyNoQaApiInRelease: no APK found" }
        val hits = others.flatMap { scan(it) }
        if (hits.isNotEmpty()) throw GradleException("QA control API found in a non-qa APK:\n  " + hits.joinToString("\n  "))
        if (!override.isPresent) {
            val qa = apksOf("qa")
            check(qa.isNotEmpty()) { "verifyNoQaApiInRelease: no qa APK" }
            check(qa.all { scan(it).isNotEmpty() }) { "verifyNoQaApiInRelease: the qa APK does not contain the control API (is the scan still valid?)" }
        }
        println("verifyNoQaApiInRelease: ${others.joinToString { it.name }} are clean")
    }
}
