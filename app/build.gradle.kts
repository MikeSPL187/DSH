import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.dsh"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.dsh.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 112
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Client id OAuth App владельца для входа в GitHub (device flow, без client secret): не секрет, его видно всем.
        // По умолчанию — OAuth App «DSH» владельца; другой — -PdshGithubClientId=… или DSH_GITHUB_CLIENT_ID в CI.
        val githubClientId = (findProperty("dshGithubClientId") as String?) ?: System.getenv("DSH_GITHUB_CLIENT_ID")?.ifBlank { null } ?: "Ov23likx4krz7JXH4v5K"
        require(githubClientId.matches(Regex("[A-Za-z0-9._-]*"))) { "dshGithubClientId: unexpected characters" }
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.isFile) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Тестовая сборка ставится рядом с релизной (другой applicationId и своё
        // signature-разрешение плагинов), не затирая данные установленной версии.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["bindPluginPermission"] = "app.dsh.mobile.debug.permission.BIND_PLUGIN"
        }
        release {
            manifestPlaceholders["bindPluginPermission"] = "com.deepseek.dsh.permission.BIND_PLUGIN"
            // Аудит F-18: R8 включён. Публичная поверхность, с которой линкуются нативные
            // плагины (SDK, Compose, AndroidX, Kotlin), сохранена в proguard-rules.pro;
            // релиз проверен на эмуляторе вместе с загрузкой companion-плагина.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        // proot 需要从 nativeLibraryDir 直接 exec（extractNativeLibs=false 时该目录不存在），
        // 因此关闭 APK 内直接加载，改为安装时解压 native 库。
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // Офлайн-образ android-all для Robolectric: -ProbolectricDepsDir=<папка с jar>.
            providers.gradleProperty("robolectricDepsDir").orNull?.let { dir ->
                test.systemProperty("robolectric.offline", "true")
                test.systemProperty("robolectric.dependency.dir", dir)
            }
            test.maxHeapSize = "3g"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:protocol"))
    implementation(project(":core:bridge"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))

    implementation(project(":feature:runtime"))
    implementation(project(":feature:hosts"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:files"))

    implementation(project(":plugin:runtime-web"))
    implementation(project(":plugin:surface"))
    implementation("com.deepseek.dsh.plugin:native-sdk")
    implementation(project(":plugin:native-host"))

    implementation(project(":runtime:host"))
    implementation(project(":runtime:bridge"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
    // UI-прогон экранов на JVM (Robolectric + нативная графика), см. app/src/test/.../ui/.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Снимочные тесты экранов запускают ComponentActivity из ui-test-manifest, который подключён
// только к debug (тестовая Activity не должна попадать в релизный APK). В release-варианте
// их не запускаем — те же экраны проверяются в testDebugUnitTest.
tasks.withType<Test>().configureEach {
    if (name == "testReleaseUnitTest") {
        exclude("**/ScreensGalleryTest*", "**/DesignSystemGalleryTest*", "**/DshScreensSweepTest*", "**/KeyCheckTargetTest*", "**/TerminalPasteTest*", "**/LockedContentTest*", "**/QaRegressionUiTest*")
    }
}

// Robolectric 4.14 (GraphicsMode.NATIVE): DefaultNativeRuntimeLoader.maybeCopyFonts открывает jar
// nativeruntime-dist-compat как zip-ФС и закрывает её не в finally. Одно исключение при копировании —
// и ФС остаётся открытой на всю JVM: каждая следующая песочница падает с FileSystemAlreadyExistsException,
// а за ней — UnsatisfiedLinkError во всех нативных тестах (полный прогон падал, повторный проходил).
// Папка fonts/ на classpath раньше jar даёт загрузчику обычный каталог — zip-ФС не открывается вовсе.
val extractRobolectricFonts by tasks.registering(Sync::class) {
    val runtime = configurations.named("debugUnitTestRuntimeClasspath")
    // Только внешний модуль: проектные модули AGP без атрибута artifactType разрешить нельзя.
    val jars = runtime.map { cfg ->
        cfg.incoming.artifactView {
            componentFilter { id -> id is org.gradle.api.artifacts.component.ModuleComponentIdentifier && id.module == "nativeruntime-dist-compat" }
        }.files
    }
    from({ jars.get().map { zipTree(it) } }) {
        include("fonts/**")
    }
    into(layout.buildDirectory.dir("robolectric-fonts"))
}
tasks.withType<Test>().configureEach {
    dependsOn(extractRobolectricFonts)
    val fonts = layout.buildDirectory.dir("robolectric-fonts")
    doFirst { classpath = files(fonts) + classpath }
}
