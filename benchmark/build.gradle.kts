plugins {
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dsh.benchmark"
    compileSdk = 35
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "benchmark-rules.pro")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }

androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" } }

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
}
