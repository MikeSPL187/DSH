# Android device QA

The full workflow runs one KVM emulator at a time: API 30, then 33, then 35,
with 3072 MiB and 3 virtual cores. A global concurrency group also serializes
runs from different branches. APKs are built before emulator startup, or reused
only when every tracked Android build input matches the cached build. Both the
original APK source and the current fixture revision are recorded. A watchdog stops the emulator if host MemAvailable
falls below 1 GiB. Software fallback is rejected. GitHub documents hosted-runner
KVM support in its [hardware acceleration announcement](https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/).

The IME instrumentation test uses a non-secret fixture, a real keyboard, physical
Save/Continue coordinates, exact validated/saved text, and masked accessibility.
No real account/API key is needed: the Host integration uses a controlled local
HTTP provider with non-secret credentials. It installs the pinned
image through the APK's normal installer, opens Host terminal, exercises Debian
package install/postinst/purge and curl, and tests the compiled Android spawn
provider against a child TCP server while preserving an unrelated listener.
Settings checks cover actual dark pixels/system bars, two RU/EN cycles retaining
Appearance, landscape foreground/navigation, and Back from Plugins. Backup tests
restore real ZIP files and open the EN/RU share URI from a separate test APK/UID.

The extended integration waits for a real JobScheduler fire without Run now or
changing the clock, reads a file from the selected Android project through Host,
checks inherited model/provider IDs and terminal error history, and exercises
offline/retry, read-only shell denial, font scale 1.3/IME and rotation. A separate
process-death test uses real force-stop, checks the durable partial before and
after Host replay, requires the partial/reason/Retry to be visible in native
Android accessibility, then clicks Retry and waits for Host completion. The AVD
is kept awake; screenshots and native trees are captured before teardown.

Each of five cold starts must make navigation usable within 20 seconds, then
keep Settings visible for eight seconds while Host/account/plugin hydration
continues. This catches a second loading gate after the first visible frame.

`full` runs the entire matrix. The manual `integration-only` and `host-probe`
profiles run focused scenarios on API 30; they do not replace full-matrix proof.

Rootfs 2.0.3 public downloads were published on 2026-11-08 and are preferred.
For an unpublished candidate, the successful candidate run
42257899221 supplies the immutable rootfs 2.0.3 image, which must still match the APK's hash. That fallback
artifact expires after 14 days. Published rootfs 2.0.3 assets provide durable
downloads of the same pinned bytes; do not rebuild and overwrite them.

Tests use an instrumented debug APK (`app.dsh.mobile.debug`). The signed release
APK is independently inspected for package/version/source/rootfs pins and signing
continuity. Functional SwiftShader runs are not a release/physical-device
performance benchmark. Current evidence, limitations and all QA IDs are tracked
in [the rounds 1–4 table](../../docs/qa/rounds-1-4.md).

An ANR fails the run; no DSH/SystemUI failure is dismissed. The expected Android
notification permission prompt is allowed explicitly. Evidence is uploaded even
on failure. The scripts require a disposable device, never an existing user install.
