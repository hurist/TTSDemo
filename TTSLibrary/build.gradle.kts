plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.qq.wx.offlinevoice.synthesizer"
    compileSdk = 36

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    sourceSets["main"].jniLibs.srcDir("libs")


}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    // kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

afterEvaluate {
    tasks.register<Jar>("androidSourcesJar") {
        archiveClassifier.set("sources")

        // Android 已统一 Java/Kotlin 源集，这里包含 Java + Kotlin 文件夹
        from(android.sourceSets["main"].java.srcDirs)

        // 额外包含 manifest 与 res（可选）
        from("src/main/aidl")
        from("src/main/manifest")
    }

    artifacts {
        add("archives", tasks.named("androidSourcesJar"))
    }
}
