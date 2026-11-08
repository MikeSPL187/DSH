plugins {
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dsh.benchmark"
    compileSdk = 35
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
}
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" } }

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
}
