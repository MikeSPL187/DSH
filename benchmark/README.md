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

## Repeated comparison

Keep a device manifest beside each revision's reports, for example:

```json
{
  "deviceId": "reference-phone", "osBuild": "actual build fingerprint",
  "refreshHz": 120, "fontScale": 1.0, "powerSave": false,
  "compilationMode": "None", "appState": "fresh-onboarding; synthetic-fixtures-v1",
  "ramGb": 8, "isPhysical": true,
  "sourceRevision": "actual source SHA"
}
```

These values must describe the actual run, not defaults inferred from the phone
model. Keep the benchmark app unprovisioned (no key or Linux installation) for
these synthetic workloads; record that state in `appState` and reject comparisons
against a provisioned installation. Also retain temperature, charging state and test conditions in the run
notes. The comparison checks declared settings and the device/OS identity inside
the benchmark reports; it cannot independently attest manually entered settings.

Run three independent candidate suites, then compare:

```sh
python3 infra/qa/performance_compare.py --baseline before.json \
  --candidate after-1.json after-2.json after-3.json \
  --baseline-device before-device.json --candidate-device after-device.json \
  --output comparison.json
```

Exit 0 means no repeated relative timing regression, 1 means every candidate
exceeded the 5% limit for at least one metric, and 2 means invalid/incomparable
evidence. The comparator checks startup medians and frame p95, handles negative
frame overruns, rejects unknown metrics and excludes frame counts from speed
claims. A passing comparison alone does not close N19 or establish the absolute
startup and missed-frame targets.
