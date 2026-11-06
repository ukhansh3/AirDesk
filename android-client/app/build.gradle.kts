import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val debugToken = (localProps.getProperty("AIRDESK_DEV_TOKEN")
    ?: System.getenv("AIRDESK_DEV_TOKEN") ?: "").replace("\"", "")
val diagnosticWsUrl = (localProps.getProperty("AIRDESK_WS_URL")
    ?: System.getenv("AIRDESK_WS_URL") ?: "ws://10.0.2.2:8765").replace("\"", "")

android {
    sourceSets.getByName("main").assets.srcDir("../../contracts")
    sourceSets.getByName("test").resources.srcDir("../../contracts")
    namespace = "com.pleos.airdesk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pleos.airdesk"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "DEV_TOKEN", "\"" + debugToken + "\"")
            buildConfigField("String", "DIAGNOSTIC_WS_URL", "\"" + diagnosticWsUrl + "\"")
        }
        getByName("release") {
            buildConfigField("String", "DEV_TOKEN", "\"\"")
            buildConfigField("String", "DIAGNOSTIC_WS_URL", "\"\"")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

