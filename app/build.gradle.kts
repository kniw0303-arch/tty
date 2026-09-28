plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.livetranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.livetranslator"
        minSdk = 29          // Android 10 이상 (다른 앱 소리 캡처 기능 필요)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 오프라인 음성 인식
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // 오프라인 번역 (Google ML Kit, 무료)
    implementation("com.google.mlkit:translate:17.0.3")
}
