plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.change.randomcomic"
    compileSdk = 34 // 或者 35/36，取决于你的 Android Studio 版本支持

    defaultConfig {
        applicationId = "com.change.randomcomic"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // 【关键】确保 Kotlin 编译为 Java 8 或更高，保证兼容性
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // 【针对 16KB 页大小的额外保险】
    // 虽然你现在没有原生库，但加上这个配置可以确保
    // 如果你未来添加了库，它们会自动使用未压缩的方式打包（zipalign 对齐），
    // 这是 16KB 兼容性的关键要求。
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // 添加 DocumentFile 库 (为了处理文件夹选择)
    implementation("androidx.documentfile:documentfile:1.0.1")
}