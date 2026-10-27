> English | [中文](README.md)

# Source integration

This directory contains the source and standalone build configuration for the Native UI
SDK (API v1), package `com.deepseek.dsh.plugin.nativesdk`.

## Build as a standalone module

- Open `sdk/` in Android Studio (it is itself the root project; no submodule include is
  needed);
- Or copy `build.gradle.kts` and `settings.gradle.kts` into a temporary Gradle project
  and run:

```bash
gradle assembleRelease
```

The output is `build/outputs/aar/sdk-release.aar`.

> This directory does not ship a Gradle Wrapper; use Gradle 8.11+ compatible with AGP 8.9.

## Use as a companion APK dependency

The recommended path is to use `../artifacts/dsh-native-ui-sdk-release.aar` directly, or
copy the sources under `sdk/src/main/kotlin/...` into your own library module. Sources
and the AAR are equivalent; the AAR does not include transitive dependencies, so you
must declare Compose and serialization dependencies yourself (see the root README).

## Main APIs

| Type | Purpose |
| --- | --- |
| `DshUiPlugin` | Entry contract for the companion APK |
| `PluginDescriptor` | Plugin id/name/JS package name/API version |
| `PageEntry` / `PageContent` / `PageRegistry` | Native pages for settings and plugin pages |
| `NativeSlot` / `SlotContribution` / `SlotRegistry` | Embedded slot contributions |
| `NativeSlotMapping` | Explicit mapping from built-in slots to native slots/unsupported |
| `PluginHostApi` / `PluginRpcHost` | Theme, storage, opening links, RPC |
| `PluginTheme` | Theme tokens provided by the App |
| `SpecRenderer` / `DshUiSpec` | JSON spec rendering (optional) |
| `ToolRenderer` | Tool-call card rendering (optional) |

## Notes

- The companion APK only handles UI and UI state; business logic and credentials stay in
  the Host-side JS.
- Slot coverage follows the root `slot-mapping.md`; slots that are not wired are not
  displayed.
- After installation the App automatically scans the
  `com.deepseek.dsh.action.PLUGIN_UI` service; no extra registration in the App is needed.
