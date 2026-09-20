plugins {
    id("com.android.application")
}

android {
    namespace = "com.yishai.parasha"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yishai.parasha"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
}
