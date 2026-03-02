plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.finalimageclassify"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.finalimageclassify"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        // DISABLE mlModelBinding - this stops the auto-generated class errors
        mlModelBinding = false
    }

    aaptOptions {
        noCompress("tflite")
    }
}

dependencies {
    // Only use tensorflow-lite (no support, metadata, gpu, or litert)
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    // Your app dependencies
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.camera.core)
    implementation(libs.camera.view)
    implementation(libs.camera.lifecycle)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation("androidx.camera:camera-camera2:1.5.3")      // ← ADD THIS
    implementation("com.google.guava:guava:31.1-android")    // ← ADD THIS
}