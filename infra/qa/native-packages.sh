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
dpkg --purge dsh-native-qa
test ! -e /usr/share/dsh-native-qa/payload
test -z "$(dpkg --audit)"
node /root/dsh-native-cancel.mjs
echo PASS > "$result"
