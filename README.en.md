# DSH — the DeepSeek Harness agent on your phone

[Русский](README.md) · **English**

DSH is an Android app that runs the full DeepSeek Harness agent on the phone itself: the real
Host, tools, terminal and plugins run locally, no computer required. It is an independent line
of development focused on **security, reliability and an interface on par with the best mobile
assistants**.

> Status: the interface is being redesigned. The technical audit and fixes are complete —
> see the [report (Russian)](docs/SECURITY_FIXES_REPORT.ru.md).

## What makes DSH different

- **Secure by default.**
  - Native plugins load only after you explicitly trust them: the package and certificate
    are pinned, and a changed signature is blocked.
  - Tokens are encrypted with an Android Keystore key.
  - A remote Host is reachable over HTTPS only, with fingerprint pinning.
  - The agent cannot read the clipboard by default.
  - The build verifies the SHA-256 of every dependency; releases are built with R8.
- **Reliable.**
  - Environment updates are atomic and roll back after a crash or power loss.
  - Agent replies reconnect after a network change.
  - State survives rotation.
  - Runs on Android 8 and later.
- **Battery-friendly.** The Host sleeps when idle, stays in the background only while a task
  runs, and the app does no background polling.
- **File privacy.** You can skip all-files access and keep the workspace inside the app.
- **Russian, English and Chinese** interface and messages.

## Features

| | |
|---|---|
| Agent | Full DeepSeek Harness sessions, streaming replies, tools, approval of risky actions |
| Workspace | Project files on the phone, import, preview, chat attachments |
| Terminal | Commands in a Linux environment (Debian + proot) next to the agent |
| Models | DeepSeek and other providers via API keys |
| Plugins | Host plugins and native plugins with a Compose UI |
| Multiple Hosts | A local Host on the phone and a remote Host via secure pairing |

## Building

Requires JDK 21 and Android SDK 35.

```bash
scripts/fetch-sdk.sh            # Native UI SDK at the pinned version (sdk.lock)
./gradlew test                  # tests
./gradlew :app:assembleDebug    # debug build
./gradlew :app:assembleRelease  # release (R8; needs keystore.properties)
```

After updating dependencies, regenerate the checksums:

```bash
./gradlew --write-verification-metadata sha256 test :app:assembleDebug :app:assembleRelease
```

## Origin

DSH is based on [deepseek-harness-mobile](https://github.com/EricFL1998/deepseek-harness-mobile)
(app-v1.0.6) and works with [DeepSeek Harness](https://github.com/deepseek-ai/dsh). The original
project publishes no license file — check the terms of use with its author before distributing
builds.
