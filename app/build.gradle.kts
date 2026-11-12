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

    testOptions {
        unitTests {
            // android.util.Log 등은 JVM 테스트에서 기본값을 돌려준다
            isReturnDefaultValues = true
            all { it.useJUnitPlatform() }
        }
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

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
