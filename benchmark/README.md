# Physical Android performance workloads

Build with `./gradlew -PdshBenchmarks=true :app:assembleBenchmark :benchmark:assembleBenchmark`.
Run on a dedicated physical ARM64 phone (8+ GiB RAM) with
`./gradlew -PdshBenchmarks=true :benchmark:connectedBenchmarkAndroidTest`.

The target `app.dsh.mobile.benchmark` installs separately, uses debug signing,
release R8/resource shrinking and a profileable, non-debuggable process.
`PerformanceFixtureActivity` is packaged only in that APK. It generates 200 chat
turns, 100/1000/10,000 empty folders and a paced 2000-chunk stream without a key,
network model calls or Linux installation. Directory preparation is outside the
frame measurement; assertions require real workload content before scrolling.

`DshStartupBenchmark` measures initial display of the real launcher (10 cold
starts, CompilationMode.None). An unprovisioned installation shows onboarding;
this measurement is not interactive-UI or Host-readiness evidence. Rendering
cases use the production FilesScreen/ModernChatScreen from an isolated fixture
with five iterations each. They exclude RPC, the app outbox and Host lifecycle;
measure those separately with a provisioned app before closing N19.

Fix refresh rate (60 Hz then 120 Hz), font scale and power-saving state. Record
model, Android build, temperature, power state and source SHA with results.
Macrobenchmark writes raw JSON and Perfetto traces to the benchmark build output.
Do not suppress emulator, low-battery or profileability errors for acceptance.
No physical results or generated Baseline Profile are checked in yet.

CI `Android benchmark build` compiles the APK pair with strict dependency
verification by default. When adding a dependency, the explicit
`refresh_verification=true` input generates metadata for review; it is not a
strict-verification acceptance run. Review and commit the generated checksums,
then run the default strict build before accepting the change. Runtime and
normal debug/release variants do not include the optional benchmark module.
