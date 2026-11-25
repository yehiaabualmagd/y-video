import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.cinematy.video"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cinematy.video"
        minSdk = 26
        targetSdk = 34
        versionCode = 24
        versionName = "0.5.10"
        resourceConfigurations += setOf("en", "ar")
        vectorDrawables { useSupportLibrary = true }
        buildConfigField(
            "String",
            "SHARE_URL",
            "\"${(project.findProperty("shareUrl") as String?) ?: "https://example.com/y-video"}\"",
        )
    }

    val signProps = Properties()
    val signPropsFile = rootProject.file("signing.properties")
    if (signPropsFile.exists()) {
        signPropsFile.inputStream().use { signProps.load(it) }
        signingConfigs {
            create("persistent") {
                storeFile = file(signProps.getProperty("storeFile"))
                storePassword = signProps.getProperty("storePassword")
                keyAlias = signProps.getProperty("keyAlias")
                keyPassword = signProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            signingConfig = if (signPropsFile.exists()) signingConfigs.getByName("persistent")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += setOf("META-INF/DEPENDENCIES", "META-INF/*.kotlin_module") } }
}

dependencies {
    val media3 = "1.3.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.biometric:biometric:1.1.0")
}
