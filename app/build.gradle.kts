plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.am.voice"
    compileSdk = 35
    defaultConfig { applicationId = "com.am.voice"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0" }
}
dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
