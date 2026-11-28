import com.jaredsburrows.license.LicenseReportExtension
import org.gradle.api.artifacts.dsl.LockMode
import java.io.File

// CI APKs use one persistent development certificate so Android can install
// successive builds as updates. Local builds retain the normal local debug key.
val ciKeystorePath = System.getenv("ROVEX_ANDROID_KEYSTORE_PATH")
val ciKeystorePassword = System.getenv("ROVEX_ANDROID_KEYSTORE_PASSWORD")
val ciKeyAlias = System.getenv("ROVEX_ANDROID_KEY_ALIAS")
val ciKeyPassword = System.getenv("ROVEX_ANDROID_KEY_PASSWORD")
val ciSigningValues = listOf(ciKeystorePath, ciKeystorePassword, ciKeyAlias, ciKeyPassword)
val ciSigningRequested = ciSigningValues.any { !it.isNullOrBlank() }
require(!ciSigningRequested || ciSigningValues.all { !it.isNullOrBlank() }) {
    "Incomplete persistent Android signing configuration; provide all ROVEX_ANDROID_KEYSTORE_* and ROVEX_ANDROID_KEY_* values."
}
if (ciSigningRequested) {
    require(File(ciKeystorePath!!).isFile) { "Persistent Android keystore file does not exist." }
}
val quantVersionCode = providers.gradleProperty("quantVersionCode").orNull?.toIntOrNull() ?: 1
require(quantVersionCode in 1..2_100_000_000) { "quantVersionCode must be a valid positive Android version code." }

buildscript {
    repositories {
        // Resolve the pinned license plugin from Maven Central, matching the
        // committed Gradle dependency-verification metadata.
        mavenCentral()
    }
    dependencies {
        // The license plugin is compiled against a newer KGP. It only needs the
        // app's already-loaded Kotlin/AGP APIs at runtime; exclude its private
        // compiler/daemon dependency to prevent a second Kotlin daemon client
        // from shadowing the application's pinned KGP.
        classpath("com.jaredsburrows:gradle-license-plugin:0.9.91") {
            // The license plugin is a build-time reporting tool, not an owner of
            // the application's Kotlin toolchain. Exclude its entire Kotlin
            // dependency graph so it cannot introduce a second compiler/daemon
            // version into Gradle's verification metadata or classpath. The
            // root build already supplies the pinned Kotlin Gradle plugins.
            exclude(group = "org.jetbrains.kotlin")
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
        versionCode = quantVersionCode
        versionName = "0.1.0-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (ciSigningRequested) {
            create("ciStable") {
                storeFile = file(ciKeystorePath!!)
                storePassword = ciKeystorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (ciSigningRequested) signingConfig = signingConfigs.getByName("ciStable")
        }
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
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
