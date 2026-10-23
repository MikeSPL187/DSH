pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // 官方仓库优先：第三方镜像排在前面时，所有依赖实际都从镜像下载（供应链风险）。
    // 国内网络需要镜像时，可在 ~/.gradle/init.gradle 里按需追加，不写进仓库。
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dsh-android"

// Native UI SDK 有自己的仓库和目录（dsh-mobile-plugin-sdk）：用组合构建引用它，
// App 仓库里不再保留一份 SDK 源码，避免两边实现/版本漂移。
// 默认指向同级目录里的 SDK 仓库，可用 -PdshSdkRepo=<path> 或 gradle.properties 覆盖。
val dshSdkRepo = providers.gradleProperty("dshSdkRepo").getOrElse(
    "../deepseek mobile support/dsh-mobile-plugin-native-ui-sdk",
)
includeBuild("$dshSdkRepo/sdk") {
    dependencySubstitution {
        substitute(module("com.deepseek.dsh.plugin:native-sdk")).using(project(":"))
    }
}


// Core
include(":core:model")
include(":core:protocol")
include(":core:bridge")
include(":core:network")
include(":core:data")
include(":core:designsystem")
include(":core:ui")

// Features
include(":feature:runtime")
include(":feature:hosts")
include(":feature:sessions")
include(":feature:chat")
include(":feature:settings")
include(":feature:files")

// Plugin System
include(":plugin:runtime-web")
include(":plugin:surface")
include(":plugin:native-host")

// Runtime & Bridge
include(":runtime:host")
include(":runtime:bridge")

// Tooling
include(":tooling:codegen")
include(":tooling:contract")
include(":tooling:frontend-patch")

// App
include(":app")

