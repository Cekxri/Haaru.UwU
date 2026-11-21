plugins {
    id("com.android.application") version "9.5.1"
    id("org.jetbrains.kotlin.android") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

android {
    namespace = "dev.cekxri.haaru"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig {
        applicationId = "dev.cekxri.haaru"
        minSdk = 29
        targetSdk = 36
        versionCode = 21
        versionName = "9.2"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.animation:animation:1.12.0")
    implementation("com.materialkolor:material-color-utilities:6.0.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.12.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
