plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.velotune"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.velotune"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // 1. Google Play Services 定位服務 (提供 FusedLocationProviderClient)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // 2. Jetpack DataStore (用於儲存 App 偏好設定與 GPS 丟失策略)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // 3. Kotlin 協程核心與 Android 擴充
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")
}