plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dorama.cutter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dorama.cutter"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation("androidx.media3:media3-common:1.6.1")
    implementation("com.google.guava:guava:33.4.0-android")
}
