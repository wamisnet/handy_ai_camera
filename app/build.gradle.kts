plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.hirameq.handycam"
    compileSdk = 34

    defaultConfig {
        applicationId = "jp.hirameq.handycam"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // ハンディターミナルは armeabi-v7a / arm64-v8a が大半。エミュレータ用に x86_64 も残す。
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    packaging { jniLibs { useLegacyPackaging = true } }

    // 端末配布用に ABI 別 APK も出す(OpenCV/TFLite のネイティブが大きいため)。universal も併せて生成。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // OpenCV 公式 Android AAR (Maven Central)。SIFT/AKAZE/ORB/HOG/Hu モーメント等を使用。
    implementation("org.opencv:opencv:4.10.0")

    // 埋め込みベクトル比較 (任意)。モデル(.tflite)を端末に置いたときだけ有効化される。
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
