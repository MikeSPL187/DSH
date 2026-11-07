# Android device QA

The workflow builds APKs before starting one KVM emulator at a time: API 30, then
33, with 3072 MiB and 3 virtual cores. A watchdog stops it if host MemAvailable
falls below 1 GiB. Software fallback is rejected. GitHub documents hosted-runner
KVM support in its [hardware acceleration announcement](https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/).

The IME instrumentation test uses a non-secret fixture, a real keyboard, physical
Save coordinates, exact saved text, and masked accessibility. Production QA skips
provider configuration: no account/API key is needed. It installs the pinned
image through the APK's normal installer, opens Host terminal, exercises Debian
package install/postinst/purge and curl, and tests the compiled Android spawn
provider against a child TCP server while preserving an unrelated listener.
Settings checks cover actual dark pixels/system bars, two RU/EN cycles retaining
Appearance, landscape foreground/navigation, and Back from Plugins.

Public downloads are preferred. Before publication, the successful candidate run
42131494409 supplies the image, which must still match the APK's hash. That fallback
artifact expires after 14 days; publish the prepared rootfs assets before relying
on it beyond that period.

An ANR fails the run; no DSH/SystemUI failure is dismissed. The expected Android
notification permission prompt is allowed explicitly. Evidence is uploaded even
on failure. The scripts require a disposable device, never an existing user install.
