plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yonghyeok.term2"
    compileSdk = 36 // 최신 Stable 버전 사용

    defaultConfig {
        applicationId = "com.yonghyeok.term2"
        minSdk = 24
        targetSdk = 36 // compileSdk와 동일하게 설정
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // 배포 시 true로 설정 고려
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // 디버그 빌드 타입은 명시하지 않아도 기본적으로 존재함. 필요한 경우 추가.
    }

    // 자바 및 코틀린 컴파일 옵션
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // 빌드 피처 설정
    buildFeatures {
        compose = true
        viewBinding = true
    }
    composeOptions {
        // libs.versions.androidxComposeCompiler.get() 를 사용
        kotlinCompilerExtensionVersion = libs.versions.androidxComposeCompiler.get()
    }
}

dependencies {
    // --- 핵심 Android 및 Kotlin ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.google.android.gms:play-services-location:21.0.1")
    // --- Jetpack Compose ---
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom)) // Compose BOM 사용
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)



    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // --- CameraX (버전 통일 권장) ---
    // 버전은 build.gradle.kts가 아닌 'libs.versions.toml' 파일에서 관리하는 것이 더 좋습니다.
    val cameraXVersion = "1.3.1" // 예시: 현재 최신 안정화 버전(1.3.1)으로 가정하고 통일
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.camera:camera-extensions:$cameraXVersion")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.0") // 로그 확인용

    // --- TensorFlow Lite (버전 통일 권장) ---
    val tfLiteVersion = "2.14.0" // 기존 버전 사용
    implementation("org.tensorflow:tensorflow-lite:$tfLiteVersion")
    implementation("org.tensorflow:tensorflow-lite-gpu:$tfLiteVersion")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:${tfLiteVersion}")
    // support 라이브러리는 별도 버전 사용
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // --- Google Play Services ---
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // --- 유틸리티 ---
    implementation("com.google.code.gson:gson:2.10.1") // 이미지 처리(픽셀 변환, 전처리) 코멘트는 라이브러리 용도에 맞게 수정 권장 (GSON은 JSON 처리 라이브러리)

    // --- 테스트 ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}