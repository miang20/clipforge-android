plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.clipforge.studio"
 compileSdk = 35
 defaultConfig { applicationId = "com.clipforge.studio"; minSdk = 26; targetSdk = 28; versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 100; versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("androidx.media3:media3-transformer:1.5.1")
 implementation("androidx.media3:media3-common:1.5.1")
 implementation("androidx.media3:media3-effect:1.5.1")
}