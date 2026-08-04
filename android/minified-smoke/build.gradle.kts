// Minified consumer app: proves the library's consumer-rules.pro survives R8.
// Building only the library AAR never exercises consumer shrinking; this module
// applies it (minifyEnabled) and instruments the RELEASE build on device.
plugins {
    id("com.android.application")
}

android {
    namespace = "ai.botisan.tantivy.minifiedsmoke"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.botisan.tantivy.minifiedsmoke"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Instrument the minified release build — that is the whole point here.
    testBuildType = "release"

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("test-proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":lib"))

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
