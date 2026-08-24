import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Credentials live in local.properties (gitignored) so they never reach source control.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.fastpix.uploads.java"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.fastpix.uploads.java"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "FASTPIX_TOKEN", "\"${localProps.getProperty("fastpix.token", "")}\"")
        buildConfigField("String", "FASTPIX_SECRET_KEY", "\"${localProps.getProperty("fastpix.secretKey", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":uploader"))

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.okhttp)
}
