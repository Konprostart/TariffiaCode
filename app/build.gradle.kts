import java.io.File
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")
}

val repoRoot = rootProject.projectDir
val githubClientId =
    (
        System.getenv("GITHUB_CLIENT_ID")
            ?: findProperty("GITHUB_CLIENT_ID")?.toString()
            ?: ""
    ).trim()
val generatedRuntimeAssets = rootProject.layout.buildDirectory.dir("generated/runtime-assets")
val generatedRuntimeJni = rootProject.layout.buildDirectory.dir("generated/runtime-jni")
val generatedRuntimeNativeWork = rootProject.layout.buildDirectory.dir("native-source-work")

// Builds the embedded PRoot runtime from pinned first-party sources with the pinned Android NDK
// (see runtime_tools/native_sources.lock.json). This replaces the former build-time .deb fetch
// (scripts/prepare_android_runtime_assets.py + runtime_tools/termux_assets.lock.json), which is kept
// in the repository as provenance only. The output prefix layout is unchanged, so the JNI mapping
// step below and all runtime Kotlin/Java behaviour are untouched.
val prepareOpenCodeRuntimeAssets =
    tasks.register<Exec>("prepareOpenCodeRuntimeAssets") {
        inputs.file(repoRoot.resolve("runtime_tools/termux_assets.py"))
        inputs.file(repoRoot.resolve("runtime_tools/native_sources.lock.json"))
        inputs.file(repoRoot.resolve("scripts/build_android_runtime_from_source.py"))
        outputs.dir(generatedRuntimeAssets)
        commandLine(
            "python3",
            repoRoot.resolve("scripts/build_android_runtime_from_source.py").absolutePath,
            "--output-dir",
            generatedRuntimeAssets.get().asFile.absolutePath,
            "--lock-file",
            repoRoot.resolve("runtime_tools/native_sources.lock.json").absolutePath,
            "--work-dir",
            generatedRuntimeNativeWork.get().asFile.absolutePath,
            "--download-ndk",
        )
    }

val prepareOpenCodeRuntimeNativeLibs =
    tasks.register<Exec>("prepareOpenCodeRuntimeNativeLibs") {
        dependsOn(prepareOpenCodeRuntimeAssets)
        inputs.dir(generatedRuntimeAssets)
        inputs.file(repoRoot.resolve("scripts/prepare_android_runtime_native_libs.py"))
        outputs.dir(generatedRuntimeJni)
        commandLine(
            "python3",
            repoRoot.resolve("scripts/prepare_android_runtime_native_libs.py").absolutePath,
            "--linux-assets-dir",
            generatedRuntimeAssets.get().asFile.absolutePath,
            "--output-dir",
            generatedRuntimeJni.get().asFile.absolutePath,
        )
    }

val releaseStoreFile =
    (
        System.getenv("AND_CODE_STORE_FILE")
            ?: findProperty("AND_CODE_STORE_FILE")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val releaseStorePassword =
    (
        System.getenv("AND_CODE_STORE_PASSWORD")
            ?: findProperty("AND_CODE_STORE_PASSWORD")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val releaseKeyAlias =
    (
        System.getenv("AND_CODE_KEY_ALIAS")
            ?: findProperty("AND_CODE_KEY_ALIAS")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val releaseKeyPassword =
    (
        System.getenv("AND_CODE_KEY_PASSWORD")
            ?: findProperty("AND_CODE_KEY_PASSWORD")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val hasReleaseSigning =
    listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() } &&
        releaseStoreFile!!.let { path ->
            val resolved =
                if (File(path).isAbsolute) {
                    File(path)
                } else {
                    File(rootProject.projectDir, path)
                }
            resolved.isFile
        }

// Dedicated, STABLE debug signing key so repeated debug APKs share one identity and can update each
// other in place. Supplied only in CI; local debug builds fall back to AGP's default debug keystore.
val debugStoreFile =
    (
        System.getenv("DEBUG_KEYSTORE_FILE")
            ?: findProperty("DEBUG_KEYSTORE_FILE")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val debugStorePassword =
    (
        System.getenv("DEBUG_KEYSTORE_PASSWORD")
            ?: findProperty("DEBUG_KEYSTORE_PASSWORD")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val debugKeyAlias =
    (
        System.getenv("DEBUG_KEY_ALIAS")
            ?: findProperty("DEBUG_KEY_ALIAS")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val debugKeyPassword =
    (
        System.getenv("DEBUG_KEY_PASSWORD")
            ?: findProperty("DEBUG_KEY_PASSWORD")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val hasDebugSigning =
    listOf(
        debugStoreFile,
        debugStorePassword,
        debugKeyAlias,
        debugKeyPassword,
    ).all { !it.isNullOrBlank() } &&
        debugStoreFile!!.let { path ->
            val resolved =
                if (File(path).isAbsolute) {
                    File(path)
                } else {
                    File(rootProject.projectDir, path)
                }
            resolved.isFile
        }

android {
    namespace = "com.konprostart.tariffiacode"
    compileSdk = 35

    // F-Droid's scanner rejects the encrypted "Dependency metadata" signing block AGP adds.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.konprostart.tariffiacode"
        minSdk = 26
        targetSdk = 35
        versionCode = 70
        versionName = "1.2.31"
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")
        // Proprietary agent CLIs (Claude Code, Antigravity, Codex) are downloaded at runtime only when
        // the user opts in. The official F-Droid catalog forbids that, so the `fdroid` flavor turns
        // the whole feature off; every other distribution keeps it. See ProprietaryAgents.kt.
        buildConfigField("boolean", "PROPRIETARY_AGENTS_ENABLED", "true")

        // The on-device runtime (see ANDROID_ABIS in scripts/prepare_android_runtime_native_libs.py)
        // only exists for these two ABIs. Without the filter JNA and Vosk drag in libraries for
        // armeabi, mips, mips64, x86 and armeabi-v7a that nothing can use.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Distributed via GitHub Releases (and the self-hosted F-Droid repo built from those
        // release APKs).
        create("github") {
            dimension = "distribution"
        }
        // Built from source by F-Droid's own build server for the official F-Droid catalog.
        // Contains no proprietary analytics or crash-reporting code, and no proprietary agent CLIs:
        // Claude Code, Antigravity and Codex are disabled here (see ProprietaryAgents.kt) so the
        // build can never download non-free binaries at runtime. OpenCode, SSH/VPS and the remote
        // workflow are unaffected.
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "PROPRIETARY_AGENTS_ENABLED", "false")
        }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                val storeFilePath = releaseStoreFile!!
                storeFile =
                    if (File(storeFilePath).isAbsolute) {
                        File(storeFilePath)
                    } else {
                        File(rootProject.projectDir, storeFilePath)
                    }
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Some third-party app store upload verifiers (e.g. APKPure) still read the
                // signer certificate via the legacy JAR (v1) signing block. AGP omits v1 by
                // default once minSdk >= 24, so it must be requested explicitly here.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // Stable debug signing config, only when the CI-provided debug keystore is present. When absent
    // (normal local development) the debug build type keeps AGP's default debug signing.
    if (hasDebugSigning) {
        signingConfigs {
            create("debugStable") {
                val storeFilePath = debugStoreFile!!
                storeFile =
                    if (File(storeFilePath).isAbsolute) {
                        File(storeFilePath)
                    } else {
                        File(rootProject.projectDir, storeFilePath)
                    }
                storePassword = debugStorePassword
                keyAlias = debugKeyAlias
                keyPassword = debugKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Debug-only package name, so a debug build can be installed alongside the release app
        // instead of replacing it. Release and F-Droid keep the production applicationId.
        debug {
            applicationIdSuffix = ".debug"
            // Use the stable CI debug key when provided so repeated debug APKs share one signing
            // identity; otherwise keep AGP's default debug signing for local development.
            if (hasDebugSigning) {
                signingConfig = signingConfigs.getByName("debugStable")
            }
        }
    }
    testOptions {
        unitTests {
            // android.util.Log is a stub on the unit test classpath and throws on every call.
            // Returning defaults instead lets tests exercise code that logs on its error paths.
            isReturnDefaultValues = true
        }
    }
    lint {
        // The androidx.startup Initializers are deliberately not auto-started: the manifest removes
        // their <meta-data> entries with tools:node="remove" so they cannot run inside
        // InitializationProvider (which fires before Application.onCreate, where the dependencies
        // they need are built). TariffiaCodeApplication initializes them itself instead. Without this,
        // lintVitalRelease fails the check and no release APK can be produced.
        disable += "EnsureInitializerMetadata"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs +=
            listOf(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main").jniLibs.srcDir(generatedRuntimeJni)
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/{LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareOpenCodeRuntimeNativeLibs)
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")

    // QR code scanning for connection setup
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Pure-JVM SSH client (Apache MINA SSHD) for the on-device SSH foundation. Kept isolated behind
    // core/ssh; BouncyCastle supplies the modern cipher/key algorithms MINA expects.
    implementation("org.apache.sshd:sshd-core:2.18.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")

    // Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Vosk (wake word detection). The speech model is downloaded on first use rather than
    // packaged: the smallest usable pair is about 90 MB against a 37 MB APK, and it is only
    // needed by people who switch the wake word on.
    implementation("com.alphacephei:vosk-android:0.3.75")

    // DI
    implementation("io.insert-koin:koin-android:4.0.1")
    implementation("io.insert-koin:koin-androidx-compose:4.0.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Baseline Profiles
    baselineProfile(project(":benchmark"))

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Real org.json implementation for unit tests that exercise the schedule bridge protocol.
    testImplementation("org.json:json:20231013")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Self-signed TLS for MockWebServer, so the update flow's HTTPS-only transport is tested for real.
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Regenerates THIRD_PARTY_LICENSES/NOTICE-aggregate.txt by extracting embedded NOTICE files
// straight out of the resolved releaseRuntimeClasspath artifacts. AGP's resource merging keeps at
// most one arbitrarily-chosen copy of META-INF/NOTICE(.txt) in the final APK (pickFirst
// deduplication, not a per-artifact preservation guarantee), so the actual NOTICE text an
// artifact ships has to be read from the dependency archive itself, not from the built APK.
tasks.register("generateNoticeAggregate") {
    doLast {
        val noticeEntryNames =
            listOf("META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md", "NOTICE", "NOTICE.txt")
        val outputFile = repoRoot.resolve("THIRD_PARTY_LICENSES/NOTICE-aggregate.txt")
        val artifacts =
            configurations.getByName("releaseRuntimeClasspath").incoming.artifacts.artifacts
                .sortedBy { it.id.componentIdentifier.displayName }

        fun readNoticeFrom(zip: ZipFile): Pair<String, String>? {
            for (name in noticeEntryNames) {
                val entry = zip.getEntry(name) ?: continue
                return name to zip.getInputStream(entry).bufferedReader().use { reader -> reader.readText() }.trim()
            }
            return null
        }

        val sections = mutableListOf<String>()
        artifacts.forEach { artifact ->
            val file = artifact.file
            if (!file.name.endsWith(".jar") && !file.name.endsWith(".aar")) return@forEach
            val found = mutableListOf<Pair<String, String>>()
            ZipFile(file).use { outer ->
                readNoticeFrom(outer)?.let { found += it }
                // AARs nest their compiled classes in classes.jar; NOTICE can live at either level.
                outer.getEntry("classes.jar")?.let { classesEntry ->
                    val tempJar = File.createTempFile("classes", ".jar")
                    try {
                        outer.getInputStream(classesEntry).use { input -> tempJar.outputStream().use { out -> input.copyTo(out) } }
                        ZipFile(tempJar).use { inner -> readNoticeFrom(inner)?.let { found += it } }
                    } finally {
                        tempJar.delete()
                    }
                }
            }
            if (found.isNotEmpty()) {
                val (entryName, text) = found.first()
                sections +=
                    "==== ${artifact.id.componentIdentifier.displayName} ($entryName) ====\n$text\n"
            }
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            "# Aggregated NOTICE files\n" +
                "# Generated by `./gradlew :app:generateNoticeAggregate` (see scripts/generate_notice_aggregate.sh) " +
                "from the resolved releaseRuntimeClasspath - do not hand-edit.\n" +
                "# Artifacts with no embedded NOTICE file are omitted; that is not a claim they have none.\n\n" +
                sections.joinToString("\n"),
        )
        println("Wrote ${sections.size} embedded NOTICE file(s) out of ${artifacts.size} artifacts to ${outputFile.path}")
    }
}
