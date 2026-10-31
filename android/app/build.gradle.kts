import java.util.Properties

// upload-keystore.jks 와 keystore.properties 가 있으면 release 빌드를 자동 서명합니다.
// 두 파일은 git 에 올라가지 않습니다 (.gitignore).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase 설정 파일(app/google-services.json)이 있을 때만 알림 기능이 켜집니다.
// 파일이 없어도 앱은 정상적으로 빌드되고 돌아갑니다 (알림만 동작하지 않음).
val firebaseReady = file("google-services.json").exists()
if (firebaseReady) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "kr.woorichurch.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.woorichurch.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0"

        // ─────────────────────────────────────────────────────────────
        // 서버 주소. Cloudflare Worker 를 배포한 뒤 아래 값을 바꾸세요.
        //   예) https://woori-church.내계정이름.workers.dev
        // ─────────────────────────────────────────────────────────────
        buildConfigField("String", "API_BASE", "\"https://woori-church.lej0812.workers.dev\"")
        buildConfigField("boolean", "FIREBASE_READY", firebaseReady.toString())
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    // 권한 요청(ActivityResult) 이 요구하는 최소 버전을 맞추기 위해 명시합니다
    implementation(libs.androidx.fragment)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    // 알림 (Firebase Cloud Messaging)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.coroutines.play.services)
}
