# Android and Host optimization

Target: ARM64 phones with at least 8 GiB RAM, tested at fixed 60 and 120 Hz. Preserve Android compatibility, native-plugin ABI, session history and permission enforcement. Physical measurements remain required to close N19.

## Implementation status

| Workstream | Status |
|---|---|
| Directory virtualization | Implemented: one lazy item per entry, stable path keys, continuous card outline cached by geometry. Metadata is read on IO; child counts are loaded only for composed rows. |
| Streaming presentation | Implemented: append to a buffer; publish immutable UI snapshots at most once per 8 ms. Baselines replace the buffer. Session switches and terminal events cancel pending publications. Raw session events and journal durability remain unchanged. |
| Markdown preparation | Implemented: block parsing on Default dispatcher, cancelled effects cannot publish obsolete results. Perfetto section `DSH.Markdown.parse`. Inline styling and rendering remain on the UI thread. |
| Installed dependencies | Lockfile has one Zod 4.4.3 instance instead of 50 nested 4.4.3 instances and one 4.6.7 instance. All declared dependency and peer ranges accept 4.4.3; `npm ls --package-lock-only --all` passes. New rootfs image must be built, tested and pinned before this affects installed apps. |
| Host entry bundle | Removed unused aggregate bundle creation from the rootfs recipe. Android already uses canonical `lib/bin.js`, sharing module identity with plugins. |
| Regression coverage | Added a 1000-folder Compose scenario, filesystem attribute snapshot test, and 10,000-Unicode-delta buffer tests including replay and session invalidation. Local focused verification passed: 29 filesystem tests, 41 chat tests, 2 streaming buffer tests. The app Compose 1000-folder scenario is added but has not run yet. |
| Build validation | Dependency resolution check added to Android CI. Gradle wrapper download timeout increased from 10 to 60 seconds after observed timeouts; later upstream HTTP 504 is still a separate infrastructure failure. |
| Macrobenchmark / Baseline Profile | Pending. No generated profile or physical-device result is claimed. |
| Sandbox preflight / profile diagnostics | Pending. No permission expansion or diagnostic failure suppression. |
| Lifecycle subscriptions / journal / state ownership | Pending. The application-owned journal still publishes partial text separately; its checkpoint and recovery guarantees must be retained when optimizing it. |
| Kotlin quality gates and architecture rules | Pending. No formatting-only changes to existing code. |

## Acceptance workloads

Use generated content only, no API key or paid requests: folders with 100/1000/10,000 entries; 200-turn chat with code and tables; fast text/reasoning stream; cancellation, reconnect, force-stop, theme/language/font changes, background automation, and release native-plugin loading. Include existing chat recovery and terminal-state tests after changing persistence or status handling.

Measure startup initial display separately from interactive UI and Host readiness. Capture all app-owned processes: Android, Node/PRoot and WebView. Keep memory and CPU endpoints separate from external model latency. Published rootfs archives, compressed bytes, installed bytes, file counts and APK bytes are distinct measurements.

Initial physical-device goals: UI interactive p95 <=1500 ms, warm return <=500 ms, local response <=100 ms, <=1% missed presentation deadlines at fixed refresh rate. A repeated >5% regression in comparable timing or memory blocks acceptance. Report device, build source, compilation mode, screen refresh rate, font scale, temperature, power state and scenario with raw traces. Do not compare different Android jank classifications or use debug SwiftShader percentages as release performance proof.

## Verification record

- Dependency graph before and after: `npm ls --package-lock-only --all --prefix infra/rootfs/agent`, exit 0 on both.
- Whitespace: `git diff --check`.
- Offline local Android tests could not resolve missing cached dependencies. Online retry is tracked separately.
- Android CI 42464830733 failed on Gradle download timeout before compilation (both attempts).
- Android CI 42465033252 failed before compilation on an upstream Gradle-distribution HTTP 504 (both attempts).
- Rootfs CI 42464830630 SUCCESS: image builds and PRoot Debian-package install/removal checks passed on x86_64 and arm64; candidate packaging passed, release skipped. Candidate source `da092e24`. No release or APK manifest change has been made.
- Actual gzip archives from candidate job logs: x86_64 264,518,982 bytes (published 2.0.3: 299,916,757; -35,397,775, -11.80%); arm64 266,669,308 bytes (published: 302,072,206; -35,402,898, -11.72%). These are archive-byte comparisons, not installed-memory or frame-rate measurements. The automated candidate retains the old version label and must receive a new immutable release version before being pinned in an APK.
- Source overlay checks: `node --test infra/rootfs/tests/overlay-agent.test.mjs`, 3 passed.

Official benchmark setup reference: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview

## Focused Android verification

Functional source: `f6de85443e6e810f8e6228b537e1c6a73a4e8c3c`.

`./gradlew :feature:files:testDebugUnitTest :feature:chat:testDebugUnitTest :core:data:testDebugUnitTest --tests '*StreamingTextBufferTest' --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process` succeeded locally (72 tests, 0 failures). `--tests` applies to the last named task; filesystem and chat suites ran in full. Files/chat/data production code compiled. The wider core:data run was attempted: six existing Robolectric cases could not initialize because the managed home directory is read-only. A new JUnit method initially returned a value instead of void; it was corrected and both buffer tests passed in the final run. The app UI suite and full signed APK build remain pending.

The Gradle 9.1.0 distribution SHA-256 is pinned to the checksum obtained from the official `services.gradle.org/distributions/gradle-9.1.0-bin.zip.sha256` endpoint. No mirror or dependency-verification bypass was introduced.
