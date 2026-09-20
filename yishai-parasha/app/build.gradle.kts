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
        versionCode = 3
        versionName = "0.3.0"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
