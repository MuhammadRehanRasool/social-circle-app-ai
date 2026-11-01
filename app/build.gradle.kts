import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing comes from CI secrets. When they are absent the release build is
// signed with the debug key so the optimized APK is still installable.
val releaseKeystorePath: String? = System.getenv("BRAINDUMP_KEYSTORE_PATH")
val releaseKeystorePassword: String? = System.getenv("BRAINDUMP_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("BRAINDUMP_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("BRAINDUMP_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() } && file(releaseKeystorePath!!).exists()

val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "dev.braindump.splitter"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.braindump.splitter"
        minSdk = 31 // on-device SpeechRecognizer floor
        targetSdk = 35
        versionCode = ciBuildNumber
        versionName = "1.0.$ciBuildNumber"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    lint {
        // CI is the only build machine; lint findings shouldn't block the APK.
        checkReleaseBuilds = false
        abortOnError = false
    }

    androidResources {
        // The embedder memory-maps its model; a compressed asset would have to be inflated into RAM first.
        noCompress += listOf("tflite")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("stability_config.conf"))
}

ksp {
    arg("room.generateKotlin", "true")
    // Each database version's schema as JSON, so migrations can be reviewed and diffed.
    arg("room.schemaLocation", "$projectDir/schemas")
}

/*
 * The embedding model is the only binary the app ships besides its code. The build refuses to
 * package anything but the exact file that was reviewed, so it can't be swapped silently.
 * Source: https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite
 */
val embedderModel = layout.projectDirectory.file("src/main/assets/universal_sentence_encoder.tflite")
val embedderModelSha256 = "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749"

val verifyEmbedderModel by tasks.registering {
    inputs.file(embedderModel)
    doLast {
        val bytes = embedderModel.asFile.readBytes()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(digest == embedderModelSha256) {
            "universal_sentence_encoder.tflite has SHA-256 $digest, expected $embedderModelSha256"
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyEmbedderModel) }

dependencies {
    implementation(platform(libs.kotlinx.coroutines.bom))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.speech)
    implementation(libs.mediapipe.tasks.text)

    testImplementation(libs.junit)
}
