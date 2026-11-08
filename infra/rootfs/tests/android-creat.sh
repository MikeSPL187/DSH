#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
cc -Wall -Wextra -Werror -shared -fPIC "$root/android-compat.c" -o "$work/compat.so"
cc -Wall -Wextra -Werror "$root/tests/android-creat.c" -o "$work/test"
if [ "$(uname -m)" = x86_64 ]; then
    if "$work/test"; then
        echo 'Expected legacy creat to fail under the simulated Android filter' >&2
        exit 1
    fi
fi
LD_PRELOAD="$work/compat.so" "$work/test"
