# Android module ownership

`app` composes navigation and application-lifetime work. Feature modules own
screens and presentation. `core:model` owns shared value types; protocol and data
modules own transport and persistence. Runtime modules own Android/Linux process
lifecycle; plugin modules own plugin surfaces and loading.

Production project dependencies follow these enforced directions:

- Core modules cannot depend on features, runtime, plugins or app.
- Runtime and plugin modules cannot depend on features or app.
- Feature modules cannot depend on app.

Run `python3 scripts/check-module-boundaries.py` and
`python3 scripts/tests/module-boundaries-test.py`. Android APK CI runs both.
The check covers literal `implementation`, `api`, `compileOnly` and `runtimeOnly`
project declarations in the 16 production modules; it is not a Kotlin import
analyzer or a substitute for reviewing dynamic dependencies. Test-only project
dependencies are allowed. The opt-in benchmark module targets app deliberately.

Keep request execution and durable journals application-owned so Activity
recreation does not cancel active work. Move screen subscriptions to lifecycle
ownership only after completion notifications and recovery have independent
owners and regression coverage. A blanket replacement of collectAsState would
otherwise change background behavior.

Native plugin SDK and parent-loaded AndroidX/Kotlin ABI remain preserved by R8
rules. Benchmark fixtures exist in app/src/benchmark only; their Activity and
synthetic data must never be added to the release manifest or main source set.
