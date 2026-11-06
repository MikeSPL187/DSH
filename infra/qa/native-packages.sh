#!/bin/sh
# Runs from the actual Host terminal, inside a disposable QA rootfs.
set -eu
umask 022
result=/root/dsh-native-packages.result
exec > /root/dsh-native-packages.log 2>&1
rm -f "$result"
test "$(id -u)" = 0
getent passwd _apt
getent group nogroup
test -z "$(dpkg --audit)"
curl --version
# The final image, including the installed compatibility library, is immutable input.
test -r /opt/dsh/lib/libdsh-android-compat.so
grep -Fx /opt/dsh/lib/libdsh-android-compat.so /etc/ld.so.preload
sha256sum -c /opt/dsh/sbom/android-compat.sha256
node --require /root/.dsh/patches/fs-link-fallback.cjs /root/dsh-native-fileops.mjs
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir -p "$work/package/DEBIAN" "$work/package/usr/share/dsh-native-qa"
printf 'Package: dsh-native-qa\nVersion: 1.0\nArchitecture: all\nMaintainer: QA <qa@example.invalid>\nDescription: Native package integration probe\n' > "$work/package/DEBIAN/control"
printf '#!/bin/sh\nset -eu\ngetent passwd _apt >/dev/null\n' > "$work/package/DEBIAN/postinst"
chmod 755 "$work/package/DEBIAN/postinst"
echo payload > "$work/package/usr/share/dsh-native-qa/payload"
dpkg-deb -Zgzip --build "$work/package" "$work/probe.deb"
dpkg --install "$work/probe.deb"
test "$(cat /usr/share/dsh-native-qa/payload)" = payload
# Upgrade replaces an existing file and exercises dpkg's backup/rename path.
sed -i 's/Version: 1.0/Version: 1.1/' "$work/package/DEBIAN/control"
echo updated > "$work/package/usr/share/dsh-native-qa/payload"
dpkg-deb -Zgzip --build "$work/package" "$work/upgrade.deb"
dpkg --install "$work/upgrade.deb"
test "$(cat /usr/share/dsh-native-qa/payload)" = updated
dpkg --purge dsh-native-qa
test ! -e /usr/share/dsh-native-qa/payload
test -z "$(dpkg --audit)"
node /root/dsh-native-cancel.mjs
echo PASS > "$result"
