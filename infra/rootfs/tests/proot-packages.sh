#!/bin/sh
# Only run against a disposable extracted image, never a user's installed rootfs.
set -eu
rootfs=$(realpath "$1")
proot_bin=${DSH_QA_PROOT:-proot}
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
cat "$rootfs/etc/passwd" > "$scratch/passwd"
cat "$rootfs/etc/group" > "$scratch/group"
printf 'dsh:x:%s:%s:dsh:/root:/bin/sh\n' "$(id -u)" "$(id -g)" >> "$scratch/passwd"
printf 'dsh:x:%s:\n' "$(id -g)" >> "$scratch/group"
"$proot_bin" -0 -r "$rootfs" -b /proc -b /dev -b /sys \
  -b "$scratch/passwd:/etc/passwd" -b "$scratch/group:/etc/group" -w /root \
  /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  /bin/sh -eu -c '
    umask 022
    test "$(id -u)" = 0
    test -z "$(dpkg --audit)"
    getent passwd _apt
    getent group nogroup
    curl --version
    work=$(mktemp -d /tmp/dsh-qa-dpkg.XXXXXX)
    trap '\''rm -rf "$work"'\'' EXIT HUP INT TERM
    mkdir -p "$work/package/DEBIAN" "$work/package/usr/share/dsh-qa-dpkg-probe"
    printf "Package: dsh-qa-dpkg-probe\nVersion: 1.0\nArchitecture: all\nMaintainer: DSH QA <qa@example.invalid>\nDescription: Disposable rootfs package-manager integration probe\n" > "$work/package/DEBIAN/control"
    printf "#!/bin/sh\nset -eu\ngetent passwd _apt >/dev/null\ngetent group nogroup >/dev/null\n" > "$work/package/DEBIAN/postinst"
    chmod 755 "$work/package/DEBIAN/postinst"
    printf "dsh-qa-payload\n" > "$work/package/usr/share/dsh-qa-dpkg-probe/payload"
    dpkg-deb -Zgzip --build "$work/package" "$work/probe.deb"
    dpkg --install "$work/probe.deb"
    test "$(cat /usr/share/dsh-qa-dpkg-probe/payload)" = dsh-qa-payload
    test "$(dpkg-query -W -f='\''${Status}'\'' dsh-qa-dpkg-probe)" = "install ok installed"
    dpkg --purge dsh-qa-dpkg-probe
    test ! -e /usr/share/dsh-qa-dpkg-probe/payload
    test -z "$(dpkg --audit)"
    echo "PRoot dpkg install/postinst/purge and curl: PASS"
  '
