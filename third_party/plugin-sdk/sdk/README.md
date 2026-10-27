# 源码接入

> 中文 | [English](README.en.md)

本目录是 Native UI SDK（API v1）的源码与独立构建配置，包名
`com.deepseek.dsh.plugin.nativesdk`。

## 作为独立模块构建

- 用 Android Studio 打开 `sdk/`（它本身就是 root project，无需 include 子模块）；
- 或复制 `build.gradle.kts`、`settings.gradle.kts` 到临时 Gradle 工程后运行：

```bash
gradle assembleRelease
```

产物在 `build/outputs/aar/sdk-release.aar`。

> 本目录没有附带 Gradle Wrapper；请使用与 AGP 8.9 兼容的 Gradle 8.11+。

## 作为伴侣 APK 的依赖

推荐直接使用 `../artifacts/dsh-native-ui-sdk-release.aar`，或把
`sdk/src/main/kotlin/...` 下的源码拷进自己的 library 模块。源码与 AAR 等价，
AAR 不包含传递依赖，需要自行声明 Compose 与序列化依赖（见根目录 README）。

## 主要 API

| 类型 | 用途 |
| --- | --- |
| `DshUiPlugin` | 伴侣 APK 的入口契约 |
| `PluginDescriptor` | 插件 id/名称/JS 包名/API 版本 |
| `PageEntry` / `PageContent` / `PageRegistry` | 设置页、插件页的原生页面 |
| `NativeSlot` / `SlotContribution` / `SlotRegistry` | 嵌入型落点贡献 |
| `NativeSlotMapping` | 内置 slot → 落点/显式不支持的显式映射表 |
| `PluginHostApi` / `PluginRpcHost` | 主题、存储、打开链接、RPC |
| `PluginTheme` | App 下发主题 token |
| `SpecRenderer` / `DshUiSpec` | JSON spec 渲染（可选） |
| `ToolRenderer` | 工具调用卡片渲染（可选） |

## 注意

- 伴侣 APK 只做 UI 与 UI 状态；业务逻辑、凭证留在 Host 侧 JS。
- 落点覆盖以根目录 `slot-mapping.md` 为准；未接入的落点不会显示。
- 安装后 App 自动扫描 `com.deepseek.dsh.action.PLUGIN_UI` 服务；不需要在 App 里额外注册。
