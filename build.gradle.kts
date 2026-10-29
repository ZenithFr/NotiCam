// NotiCam — single-module Android app.
//
// The repository keeps its original directory layout (src/, res/, assets/, jni/,
// libs/, AndroidManifest.xml at the root); the sourceSets below map Gradle onto
// it so nothing had to move when the UI moved to Jetpack Compose.

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.zenith.auxcam"
    compileSdk = 35
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.zenith.auxcam"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "2.0-compose"

        // The engine and the CMF Phone 2 Pro (Dimensity 7300 Pro, Mali-G615) are
        // arm64 only.
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            ndkBuild {
                arguments += listOf("APP_PLATFORM=android-35")
            }
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        ndkBuild {
            path = file("jni/Android.mk")
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src"))
            aidl.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(listOf("assets"))
            // TFLite JNI binaries ship as prebuilts next to the NDK output.
            jniLibs.setSrcDirs(listOf("tflite_extracted/jni"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        // jni/Android.mk also builds on-device diagnostics executables; they must
        // not be packaged as if they were shared libraries.
        jniLibs.excludes += setOf(
            "**/coc_gpu_test",
            "**/still_bokeh_gpu_benchmark",
            "**/gpu_clock_probe",
            "**/gpu_freq_meter",
        )
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // Shizuku + TensorFlow Lite ship as local jars (no network needed).
    implementation(fileTree("libs") { include("*.jar") })

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}

// ─────────────────────────────────────────────────────────────────────────────
// SPIR-V shader compilation
//
// The engine loads prebuilt SPIR-V from assets/shaders, which glslc generates
// from jni/shaders/*.comp. The task fails loudly (instead of shipping an APK
// that crashes at runtime) when glslc is missing; skip with -PskipShaders if the
// .spv files are already committed.
// ─────────────────────────────────────────────────────────────────────────────
val shaderSrcDir = file("jni/shaders")
val shaderOutDir = file("assets/shaders")

val compileShaders = tasks.register("compileShaders") {
    group = "build"
    description = "Compiles jni/shaders/*.comp into assets/shaders/*.spv via glslc"

    val sources = shaderSrcDir.listFiles { f: File -> f.extension == "comp" }
        ?.sortedBy { it.name }
        ?: emptyList()
    inputs.files(sources)
    outputs.dir(shaderOutDir)

    doLast {
        if (project.hasProperty("skipShaders")) {
            logger.lifecycle("compileShaders: skipped (-PskipShaders)")
            return@doLast
        }
        val glslc = System.getenv("GLSLC") ?: "glslc"
        shaderOutDir.mkdirs()
        sources.forEach { src ->
            val outFile = File(shaderOutDir, src.nameWithoutExtension + ".spv")
            val process = ProcessBuilder(glslc, src.absolutePath, "-o", outFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val log = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit != 0) {
                throw GradleException(
                    "glslc failed for ${src.name} (exit $exit):\n$log\n" +
                        "Install the Vulkan SDK / shaderc, or set GLSLC=/path/to/glslc"
                )
            }
        }
        logger.lifecycle("compileShaders: ${sources.size} shader(s) → assets/shaders")
    }
}

tasks.named("preBuild") {
    dependsOn(compileShaders)
}
