plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "net.monindev.shelfie.app"
    compileSdk = 37
    defaultConfig {
        applicationId = if (providers.gradleProperty("shelfieValidation").getOrElse("false") == "true") "net.monindev.shelfie.validation" else "net.monindev.shelfie.lab"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0-books"
        val serviceUrl = providers.gradleProperty("shelfieApiUrl").getOrElse("https://monindev.net/shelfie")
        require(serviceUrl.matches(Regex("https://[A-Za-z0-9./:-]+|http://127\\.0\\.0\\.1:[0-9]+")))
        buildConfigField("String", "SERVICE_URL", "\"$serviceUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        applicationIdSuffix = ".filament"
        resValue("string", "app_name", if (providers.gradleProperty("shelfieValidation").getOrElse("false") == "true") "Shelfie 検証用" else "Shelfie")
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("assets"))
    packaging { resources.excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":renderer-api"))
    implementation(project(":renderer-filament"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha27")
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // Android 16 removed the reflective InputManager API used by Espresso 3.6.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.0")
}
