import com.jaredsburrows.license.LicenseReportExtension
import org.gradle.api.artifacts.dsl.LockMode

buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies {
        // The license plugin is compiled against a newer KGP. It only needs the
        // app's already-loaded Kotlin/AGP APIs at runtime; exclude its private
        // compiler/daemon dependency to prevent a second Kotlin daemon client
        // from shadowing the application's pinned KGP.
        classpath("com.jaredsburrows:gradle-license-plugin:0.9.91") {
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-gradle-plugin")
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-daemon-client")
        }
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(plugin = "com.jaredsburrows.license")

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

android {
    namespace = "com.rovexquant.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rovexquant.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
extensions.configure<LicenseReportExtension>("licenseReport") {
    generateCsvReport = false
    generateHtmlReport = false
    generateJsonReport = true
    generateJsonFullReport = true
    generateTextReport = false
    copyCsvReportToAssets = false
    copyHtmlReportToAssets = false
    copyJsonReportToAssets = false
    copyJsonFullReportToAssets = false
    copyTextReportToAssets = false
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    debugImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
