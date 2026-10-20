import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Rilis dibuat CI dari tag "v<N>": N = versionCode. Lokal default 1.
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.zihad.room"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zihad.room"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "2.$appVersionCode"
    }
    // Semua HP harus memakai kunci yang sama supaya update bisa menimpa. Kunci rilis dari env (CI);
    // tanpa env, build lokal memakai kunci debug seperti biasa.
    signingConfigs {
        create("release") {
            val path = System.getenv("ROOM_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("ROOM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ROOM_KEY_ALIAS")
                keyPassword = System.getenv("ROOM_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("ROOM_KEYSTORE") != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
