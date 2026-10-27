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

// SDK нативных плагинов — составная сборка из копии в third_party/plugin-sdk (источник и коммит —
// third_party/README.md): сборка не ходит в чужие репозитории. Другую копию можно указать -PdshSdkRepo=<путь>.
val dshSdkRepo = providers.gradleProperty("dshSdkRepo").getOrElse("third_party/plugin-sdk")
includeBuild("$dshSdkRepo/sdk") {
    dependencySubstitution {
        substitute(module("com.deepseek.dsh.plugin:native-sdk")).using(project(":"))
    }
}


// Core
include(":core:model")
include(":core:protocol")
include(":core:bridge")
include(":core:data")
include(":core:designsystem")

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

