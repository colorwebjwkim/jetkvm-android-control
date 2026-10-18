plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "kr.co.nexacore.jetkvm"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.co.nexacore.jetkvm"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "kr.co.nexacore.jetkvm.WebRtcSmokeInstrumentation"
        versionCode = 24
        versionName = "0.7.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // All three app languages must be available offline after installation.
    bundle { language { enableSplit = false } }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:144.7559.12")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
