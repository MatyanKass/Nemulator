plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Ключ подписи приходит из секрета в CI (см. .github/workflows/apk.yml).
// Без него release подписывается debug-ключом раннера — такой APK
// поставится, но поверх него следующая сборка уже не встанет.
val releaseKeystore: String? = System.getenv("NEMU_KEYSTORE")

android {
    namespace = "com.matyankass.nemulator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.matyankass.nemulator"
        minSdk = 26
        // Не поднимать до 29+: с ними Android запрещает запускать программы
        // из папки приложения (W^X), а QEMU живёт именно там. Тот же приём,
        // что у Termux. Из-за этого APK не для Google Play, только напрямую.
        targetSdk = 28
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storeType = "pkcs12"
                storePassword = System.getenv("NEMU_KEYSTORE_PASSWORD")
                keyAlias = "nemu"
                keyPassword = System.getenv("NEMU_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // Бандл QEMU уже сжат xz — повторно жать его в APK бессмысленно.
    androidResources {
        noCompress += listOf("xz")
    }

    lint {
        // targetSdk 28 оставлен нарочно (см. выше).
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.tukaani:xz:1.10")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
