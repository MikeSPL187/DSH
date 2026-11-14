# Android and Host optimization

Target: ARM64 phones with at least 8 GiB RAM, tested at fixed 60 and 120 Hz. Preserve Android compatibility, native-plugin ABI, session history and permission enforcement. Physical measurements remain required to close N19.

## Implementation status

| Workstream | Status |
|---|---|
| Directory virtualization | Implemented: one lazy item per entry, stable path keys, continuous card outline cached by geometry. Metadata is read on IO; child counts are loaded only for composed rows. |
| Streaming presentation | Implemented: append to a buffer; publish immutable UI snapshots at most once per 8 ms. Baselines replace the buffer. Session switches and terminal events cancel pending publications. Every raw session event still reaches the application-owned journal. |
| Markdown preparation | Implemented: block parsing on Default dispatcher, cancelled effects cannot publish obsolete results. Perfetto section `DSH.Markdown.parse`. Inline styling and rendering remain on the UI thread. |
| Installed dependencies | Lockfile has one Zod 4.4.3 instance instead of 50 nested 4.4.3 instances and one 4.6.7 instance. All declared dependency and peer ranges accept 4.4.3; `npm ls --package-lock-only --all` passes. New rootfs image must be built, tested and pinned before this affects installed apps. |
| Host entry bundle | Removed unused aggregate bundle creation from the rootfs recipe. Android already uses canonical `lib/bin.js`, sharing module identity with plugins. |
| Regression coverage | Added a 1000-folder Compose scenario, filesystem attribute snapshot test, and 10,000-Unicode-delta buffer tests including replay and session invalidation. Local focused verification passed: 29 filesystem tests, 41 chat tests, 2 streaming buffer tests. The app Compose 1000-folder scenario passed in full Android unit CI 42469004964. |
| Build validation | Dependency resolution check added to Android CI. Gradle wrapper download timeout increased from 10 to 60 seconds after observed timeouts; later upstream HTTP 504 is still a separate infrastructure failure. |
| Macrobenchmark / Baseline Profile | Opt-in release-like target and separate instrumentation controller implemented with keyless startup, directory and chat workloads. Signed-pair acceptance build 42472168962 passed with strict dependency verification and APK validation; no generated Baseline Profile or physical-device result is claimed. |
| Sandbox preflight / profile diagnostics | Pending. No permission expansion or diagnostic failure suppression. |
| Lifecycle subscriptions / journal / state ownership | Selected-session projections share one selection monitor. Room flows are remembered by repository/host/session. The journal buffers recovery tails, schedules checkpoints during pauses and exposes a separate history/navigation projection. Notification ownership and lifecycle collection remain pending. |
| Kotlin quality gates and architecture rules | Dependency-direction guard covers 16 production modules (4 tests); performance comparator has 10 tests; verified compiler tooling has 3 tests, UI/rootfs separation has 3 tests and pinned-rootfs download has 5 tests. Detekt and Kotlin formatting gates remain pending. |

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

`./gradlew :feature:files:testDebugUnitTest :feature:chat:testDebugUnitTest :core:data:testDebugUnitTest --tests '*StreamingTextBufferTest' --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process` succeeded locally (72 tests, 0 failures). `--tests` applies to the last named task; filesystem and chat suites ran in full. Files/chat/data production code compiled. The wider core:data run was attempted: six existing Robolectric cases could not initialize because the managed home directory is read-only. A new JUnit method initially returned a value instead of void; it was corrected and both buffer tests passed in the final run. The later complete Android CI 42469004964 passed all debug/release unit tasks and produced the signed release APK, including the app UI suite and 1000-folder scenario.

The Gradle 9.1.0 distribution SHA-256 is pinned to the checksum obtained from the official `services.gradle.org/distributions/gradle-9.1.0-bin.zip.sha256` endpoint. No mirror or dependency-verification bypass was introduced.

## Continued verification

- Complete Android tests and signed release build: [42469004964](https://github.com/MikeSPL187/DSH/actions/runs/42469004964), source `4406bc30ac422773011bc4262327f88cef06d39d`. Unit stage 13m45s, release build 4m57s.
- Release APK: 14,403,217 bytes, SHA-256 `e1df6ca4497d8b11e475e80a4c86d6ca94c1ff710f552817b9aee6066d9411f7`. Local Android SDK apksigner/aapt2 checked the signature, app.dsh.mobile 1.1.4/114, embedded source and source-matching rootfs pins. Signer matches the previously verified public 1.1.3 certificate. Benchmark fixture is absent from release DEX and manifest.
- Targeted native UI: [42470141980](https://github.com/MikeSPL187/DSH/actions/runs/42470141980) SUCCESS, fixture source `36c1d3d18523f771222144bea0ac2ca8e1bc5937`. Three assertions/tests per API30/33/35, 9 PASS total, crash logs empty. Cached debug APK source `f63c58aaf2f8a9cf9836702d9ae907186c9bb3ba`; workflow verified identical tracked Android inputs before reuse. Active and completed reasoning screenshots, 320dp/font1.3 settings and composer on API35 reviewed. These are component-functional tests on an emulator, not release physical-performance evidence.
- UI-only runs now skip Linux image download and do not claim Host coverage. Integration/full runs still require an image matching APK SHA-256 pins. Three selection tests cover missing images, valid images and checksum rejection, including explicitly supplied images in UI mode.
- Official Gradle release asset is downloaded through GitHub API with the wrapper-pinned SHA-256. The Kotlin compiler JAR and POM are verified against existing reviewed dependency hashes and exposed through an exclusive CI-only local Maven repository, preventing failing remote HEAD probes. All normal dependency verification stays enabled. Local forced offline core:model compilation passed; corrupt/missing compiler hashes are rejected by tests.

Remaining acceptance: physical benchmark execution/Perfetto/absolute budgets, Baseline Profile generation, sandbox/profile diagnostics, notification/lifecycle ownership and remaining Kotlin quality gates. The optimized rootfs candidate is not pinned in the released/supplied APK yet; it continues to use published immutable rootfs 2.0.3.

Benchmark build 42471211772 compiled successfully with strict dependency verification, but post-build inspection found an unsigned controller APK. That artifact pair is rejected. The controller now has explicit debug signing and CI verifies both APK signatures, equal signers, separate instrumentation target, non-debuggable/profileable app, embedded source and R8 mapping presence. A negative check rejected the unsigned pair. Local opt-in Gradle task configuration passed. Acceptance run 42472168962 subsequently passed. Both APKs are signed with the same debug certificate; the controller targets its own package, the app is profileable/non-debuggable, and both R8 mappings exist. This confirms build/configuration/signatures, not physical execution. Ordinary release APK 42469004964 and native UI proof 42470141980 are independent and remain valid.

Final benchmark evidence: [42472168962](https://github.com/MikeSPL187/DSH/actions/runs/42472168962), source `b8dca43adfc7baa99d57fef48657a15dac323a00`, artifact `13613961818`. Target package `app.dsh.mobile.benchmark`, 14,407,949 bytes, SHA-256 `d2deee7536c8adb16de49ba5af8376df7f84380d1c2a5312990d3d6172e4f4c2`; controller package `com.dsh.benchmark`, 40,906,341 bytes, SHA-256 `0b77f21d578bda148664ec3fdfa8801850c61d926ea5eccf3d86bd19447a13c3`. The controller contains test/tracing libraries and is not distributed as the ordinary mobile app. Neither benchmark APK is a replacement for the release APK. The normal Android build inputs are unchanged between release source 4406bc30 and benchmark source b8dca43a.

Software preparation is verified. Physical execution, absolute performance targets, Baseline Profile, Host capability/profile diagnostics and remaining state/quality refactors are still pending; no claim of completing the whole optimization program is made.

## Journal checkpoints and presentation

Production source `898eee42384dd7c73544156443f0b98bba1acf4f` addresses a second streaming path. The repository already coalesces live replies, but the journal also concatenated the entire partial and notified the root UI for every delta. Chat history only needs a partial when a request fails or is interrupted.

The journal now appends to a bounded recovery buffer and materializes immutable text for a checkpoint or structural change. The retained snapshot remains at most 128 Ki UTF-16 code units; trimming avoids a split surrogate pair. In-memory compaction happens after another tail's worth of data, rather than moving the retained prefix on every token at capacity. Large deltas copy only their required suffix.

`records` now means committed snapshots: file write, fsync and atomic rename complete before publication. A single application-owned IO job checkpoints a trailing partial even when the provider pauses. The target interval remains 500 ms, measured with a monotonic clock; scheduling and storage latency are not hard real-time guarantees. A kill may lose text since the last successful checkpoint. Prompts, attachments, queue order, binding, model selection and terminal statuses still commit synchronously; each structural commit materializes all pending session tails. Background write failures keep the last committed snapshot and pending text, log the failure and retry. New deltas join the pending writer instead of bypassing its retry. Structural write errors still propagate to their caller.

The root UI collects `messageRecords`, which omits unused active partials and changes for request metadata or terminal recovery. The live answer still uses the repository's 8 ms stream. A failed/interrupted request publishes its current recovery tail immediately after the commit. Activity recreation does not own or cancel either the journal writer or its application scope. Replaced/removed requests invalidate pending writes; publication releases the old job before notifying observers so a reentrant delta can schedule its own checkpoint.

The existing `chat-outbox-v1.json` fields and serialized `com.dsh.ModelChoice` type are unchanged. `ModelChoice` moved from the sender into its own file. No permission, network-policy or visual-design change is included.

Local evidence uses the actual journal/buffer/model sources, the project's Kotlin 2.1.10 serialization compiler plugin, JDK21 and cached project dependencies. It is a focused JVM run, not a full Android build. All 16 tests passed: 5 existing queue/recovery cases, 8 checkpoint/transaction cases and 3 Unicode/bounds cases. The initial old implementation failed 2 of 7 tests: a paused trailing fragment never reached disk within two seconds, and observable records acknowledged text absent from disk. An intermediate retry path also failed when more data arrived during a storage error; that case passes after retaining one writer. Injected storage failures in these tests are expected diagnostics.

The 10,000-delta case observes no incremental update of the root message projection while the request is active, then verifies the complete tail and error status. This measures notification behavior, not frame rate. Recovery tests cover empty replay baselines, pending tails across structural changes, queued order, cancellation/removal, reentrant publication and storage recovery.

Native QA gates its two-part provider response: Android must observe a committed prefix before the provider sends a trailing fragment and pauses. The driver reads the trailing text directly from the journal before force-stop, then checks replay, visible interruption and Retry. The local HTTP fixture contract passed.

Signed build [43613951136](https://github.com/MikeSPL187/DSH/actions/runs/43613951136) passed full debug/release unit tests and release assembly (10m52s / 4m30s). APK: 14,404,593 bytes, SHA-256 `978bbcb57e0dd753957d6408968d78e095579f346d057275515adc4d30634b49`. SDK checks verified source `898eee42`, public signing-certificate continuity, app.dsh.mobile 1.1.4/114, unchanged published rootfs 2.0.3 pins and absence of the benchmark fixture. Its manifest dump matches the preceding ordinary APK.

Full native run [43614106003](https://github.com/MikeSPL187/DSH/actions/runs/43614106003) passed on the same source `898eee42384dd7c73544156443f0b98bba1acf4f`: 35 checks per API30/33/35, 105 PASS total, three empty crash logs, no failure artifacts. Evidence artifact `14123716766` confirms the paused-tail checkpoint, preserved text before/after replay and visible interruption/Retry on every API. All tracked Android inputs match the production revision; the debug APK also embeds its complete source SHA. Test-APK artifact `14121811655` retains this provenance. API35 interruption/Retry and font1.3/IME screenshots were reviewed. The earlier `86eb7de5` runs were superseded; historical state-ownership results are not substituted for this run. These are functional emulator checks; physical performance remains unverified.

### Exploratory JVM allocation probe

[Common input](journal-probe/Probe.kt) and [all raw samples/context](journal-probe/result.json) are retained for review. This is a HotSpot JDK21 journal probe, not Android, UI frame rate, retained heap/RSS or N19 acceptance. Both revisions process 10,000 reused 16-character deltas, force a terminal failure commit and cancel/join their scope. Every trial verifies an identical 131,072-character tail on disk after the measured region. The original journal is taken from `af05873a`; the new one from `898eee42`. The serialized model type and cached project model/protocol dependencies are shared. Sources use the same Kotlin 2.1.10 serialization compiler, a 256 MiB heap and SerialGC, with three warmups and five measured trials in a separate JVM per revision. Values below are medians.

| Metric per trial | Before | After |
|---|---:|---:|
| Calling-thread allocated bytes | 1,013,581,432 | 1,279,216 |
| Total JVM allocated bytes | 1,013,581,432 | 1,279,592 |
| Elapsed time including terminal commit and scope shutdown | 79.182 ms | 6.188 ms |

This supports the reduction in repeated journal string/list allocation in this workload. It does not establish an application speedup factor or a physical-device performance result.

## Selected-session ownership

Raw-event delivery may suspend and its collectors may select another chat. Previously the repository captured the selected session before delivery, so a delayed old text/reasoning delta could enter the new chat. An old stop could also clear a concurrently published new answer. Selection checks now happen after delivery, under the same monitor as stream and projection changes. Both text buffers use that monitor, avoiding independent nested buffer locks. Pending publication is released before notifying snapshot observers.

Async changes-summary responses recheck the selected session and announcement sequence before publication. All-session event delivery, run-state tracking and Room message persistence retain their existing behavior. The application-owned run journal still receives raw events; this change does not throttle its durable checkpoints or move notification ownership.

A follow-up audit found that `watchSession` also refreshes feedback for recovering/background sessions. Its async RPC response previously wrote into the single viewed-chat ratings map without an ownership check. The response now commits only for the currently viewed session. Two tests first failed on the previous source: a selection changed before the RPC reply, and a background refresh replaced already loaded current-chat ratings. Both passed with the guard; all 17 focused data tests then passed for production source `d64f83591a115c29200a8ca668fda79419e8699c`. This follow-up changes only feedback publication and adds tests; the full native matrix below exercises the preceding `4515ebae` production source and must not be presented as a bit-identical run of the feedback follow-up.

Reproduction tests failed on the original implementation for text and reasoning (`stale answer` / `stale reasoning` reached the new view). The fixed production source `4515ebae8d47cd185b637cc0a416b13227a3a66f` passed 15 focused tests: 4 session-isolation, 3 buffer, 3 outcome and 5 stream/reconnect tests. Coverage includes switching during raw-event delivery, repeated selection, baseline replay, and a second stream publishing a new answer while an old stop is held. These tests use the public event-subscription path. The third buffer test was added after the full CI build: a snapshot observer appends another delta during publication. Temporarily restoring the previous publication order made that test time out; restoring the fix and running all 15 passed. Production source remained identical to the CI build.

Room subscriptions are retained only while their repository/host/session keys remain equal. Language changes still recreate Activity and restore navigation, so localized untitled-session fallbacks are remapped in the new subscription. No locale lifecycle behavior was changed.

CI retrieves the published APK-pinned rootfs through the official GitHub release-asset API and verifies its existing SHA-256 before replacing a local file. Missing unpublished releases retain the existing candidate fallback; failed lookups, missing assets and corrupt downloads fail. Five tests cover these cases and preservation of an existing valid file. UI-only runs still skip rootfs; integration runs still require the pinned bytes. No published image or APK pin was replaced.

Full debug/release unit tests and signed release build [42476865470](https://github.com/MikeSPL187/DSH/actions/runs/42476865470) passed for `4515ebae8d47cd185b637cc0a416b13227a3a66f` (unit stage 13m03s, release build 5m22s). APK: 14,403,553 bytes, SHA-256 `953f20621e147312e5db91e80ad5e15deffe7014ea1897e41188ac4c83ac3f18`. Local SDK inspection confirmed app.dsh.mobile 1.1.4/114, embedded source, release signature continuity, source-matching published rootfs 2.0.3 pins, and absence of the benchmark fixture in DEX/manifest. This APK supersedes the earlier 4406bc30 artifact for the current state-ownership changes.

The final feedback follow-up also passed full debug/release unit tests and signed release build [42479174257](https://github.com/MikeSPL187/DSH/actions/runs/42479174257), source `d64f83591a115c29200a8ca668fda79419e8699c` (unit stage 12m23s, release build 5m05s). Latest APK: 14,403,669 bytes, SHA-256 `a79e8ba4fea11db09c6f10dcde67babaead12abacf99dab70a8563e2e1ed7c74`. SDK verification repeated signature continuity, version, embedded source, unchanged rootfs pins and absence of the benchmark fixture. This is the current ordinary APK; the 4515ebae APK is retained as preceding evidence. Android build inputs are unchanged from d64f8359 through the subsequent CI-only revision 6205f34d.

CI-only revision `6205f34d` splits Android 11/13/15 into sequential workflow steps, preserving the global one-AVD limit. Its shared launcher rejects unsupported APIs/profiles, omits Linux only for UI-only runs and restricts focused Host profiles to API30. Five launcher argument tests, Bash syntax and workflow YAML checks passed locally. This changes progress visibility for future runs; the native run started before that refactor. There are now 30 focused Python checks across performance comparison, module directions and verified CI tooling.

Full native run [42476869369](https://github.com/MikeSPL187/DSH/actions/runs/42476869369) subsequently passed: 34 checks on each API30/33/35, 102 PASS total, all three crash logs empty, no failure artifacts. Actual debug APK/fixture source is PR merge `5e1812e207a2fa01803e197e762968080ad64047`; all tracked Android inputs were compared to production source `4515ebae8d47cd185b637cc0a416b13227a3a66f` and matched. Evidence artifact `13617203814` includes real scheduled fire, project reads, provider errors, offline/retry, read-only denial, MCP negotiation/reconnect, Host terminal/proxy/package checks, two locale cycles, themes/system bars and durable partial/visible interruption/Retry after force-stop. Fifteen cold starts retained navigation during Host warmup. These are functional emulator checks, not the release physical-device performance targets. API35 interruption/Retry and Russian Appearance screenshots were reviewed. The later feedback-only production guard is covered by its two reproduction tests and final full unit build 42479174257; this native run excludes that follow-up.
