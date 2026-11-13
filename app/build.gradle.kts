import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// release 서명 정보는 저장소에 넣지 않는다(keystore.properties, *.jks는 .gitignore).
// 파일이 없으면 서명 없이 release를 만든다(빌드는 되지만 설치하려면 따로 서명해야 함).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.reader().use { load(it) }
}
val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
    !keystoreProps.getProperty(it).isNullOrBlank()
} && rootProject.file(keystoreProps.getProperty("storeFile")).exists()
if (!hasReleaseKey) logger.warn("keystore.properties가 없거나 불완전해 release APK를 서명하지 않습니다.")

android {
    namespace = "ai.deulleulcar.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.deulleulcar.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Pleos SDK가 리플렉션·AIDL을 쓰므로 난독화·축소는 끈다.
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            // API 키는 APK에 넣지 않는다(BuildConfig 미사용). 키는 실행 중 설정 화면에서 입력한다.
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
