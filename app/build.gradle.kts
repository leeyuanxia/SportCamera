plugins {
    alias(libs.plugins.android.application)
    // AGP 9 已内置 Kotlin 支持，无需再单独应用 kotlin-android 插件
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cn.leeyuanxia.sportcamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.leeyuanxia.sportcamera"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("sportcamera") {
            storeFile = file("../keystore")
            storePassword = "1152557928"
            keyAlias = "sportcamera"
            keyPassword = "1152557928"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sportcamera")
        }
        release {
            signingConfig = signingConfigs.getByName("sportcamera")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_19
        targetCompatibility = JavaVersion.VERSION_19
    }

    kotlin {
        jvmToolchain(19)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // KWS JNI 预编译库路径
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }
}

dependencies {
    // AndroidX 核心
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose（使用 BOM 管理版本）
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.view)

    // Kotlin 协程
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    // DataStore 设置持久化
    implementation(libs.androidx.datastore.preferences)

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}