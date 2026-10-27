plugins {
    id("com.android.library") version "8.9.0"
    id("org.jetbrains.kotlin.android") version "2.1.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10"
}

android {
    namespace = "com.deepseek.dsh.plugin.nativesdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation("junit:junit:4.13.2")
}
