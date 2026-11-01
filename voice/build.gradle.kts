plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Offline neural voice in plain Kotlin, so it can be tested on a desktop JVM. The ONNX Runtime
// Java API is identical on Android; the app supplies onnxruntime-android at runtime.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.onnxruntime.jvm)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
}

tasks.test {
    // Model-based tests run when VOICE_MODELS_DIR points at the downloaded model files.
    systemProperty("voice.models", System.getenv("VOICE_MODELS_DIR") ?: "")
    systemProperty("voice.out", layout.buildDirectory.dir("voice-samples").get().asFile.path)
}
