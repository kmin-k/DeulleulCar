plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.deulleulcar.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.deulleulcar.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.vehicle.data)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.fusedlocation)
    implementation(libs.pleos.fusedlocation.data)
}
