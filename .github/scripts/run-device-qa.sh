#!/usr/bin/env bash
set -euo pipefail

api="${1:?Android API is required}"
profile="${QA_PROFILE:-full}"
case "$api" in
  30|33|35) ;;
  *) echo "Unsupported Android API: $api" >&2; exit 2 ;;
esac
flags=()
image_args=()
case "$profile" in
  full) ;;
  ui-layout) flags+=(--ui-only) ;;
  integration-only|host-probe)
    if [ "$api" != 30 ]; then
      echo "$profile supports API 30 only" >&2
      exit 2
    fi
    flags+=(--integration-only)
    if [ "$profile" = host-probe ]; then flags+=(--host-probe); fi
    ;;
  *) echo "Unsupported QA profile: $profile" >&2; exit 2 ;;
esac
if [ "$profile" != ui-layout ]; then
  image_args+=(--rootfs qa-rootfs/rootfs-x86_64.bin)
fi
python3 infra/qa/android-device.py --api "$api" "${image_args[@]}" \
  --output "device-evidence/api-$api" "${flags[@]}"
