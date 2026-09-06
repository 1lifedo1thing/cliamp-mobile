import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Signing comes from keystore.properties locally and from environment variables
 * in CI. Both are gitignored or secret; neither is in the repo.
 */
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signing(key: String, env: String): String? =
    (keystoreProperties.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStore = signing("storeFile", "KEYSTORE_FILE")
val releaseStorePassword = signing("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = signing("keyAlias", "KEY_ALIAS")
val releaseKeyPassword = signing("keyPassword", "KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { it != null } && rootProject.file(releaseStore!!).exists()

android {
    namespace = "stream.cliamp.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "stream.cliamp.mobile"
        minSdk = 26
        targetSdk = 36
        // A tagged release overrides both; a local build keeps the defaults.
        versionCode = (System.getenv("CLIAMP_VERSION_CODE")?.toIntOrNull()) ?: 1
        versionName = System.getenv("CLIAMP_VERSION")?.removePrefix("v") ?: "0.0.1"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falling back to the debug key would be worse than it looks: CI
            // generates a fresh debug keystore per run, so every build would be
            // signed differently and could not install over the previous one.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.generateKotlin", "true")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // sshj brings bouncycastle and slf4j-api transitively, but declares both at
    // runtime scope, so bcprov has to be named again to be visible at compile
    // time - the SSH layer registers the provider itself. slf4j 2.x finds no
    // provider on its own, so without a binding its output would vanish.
    implementation(libs.sshj)
    implementation(libs.bouncycastle)
    implementation(libs.slf4j.android)
}
