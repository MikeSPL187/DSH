#!/usr/bin/env bash
set -euo pipefail

# Download the pinned official distribution through the release asset API.
# The browser-download redirect intermittently returns HTTP 504 in CI.
version=9.1.0
asset=294652020
properties=gradle/wrapper/gradle-wrapper.properties
expected_url="https\\://services.gradle.org/distributions/gradle-${version}-bin.zip"
actual_url=$(sed -n 's/^distributionUrl=//p' "$properties")
checksum=$(sed -n 's/^distributionSha256Sum=//p' "$properties")
[[ "$actual_url" == "$expected_url" && "$checksum" =~ ^[a-f0-9]{64}$ ]] || {
  echo 'Gradle CI asset must match the wrapper version and pinned checksum' >&2
  exit 1
}
archive="$RUNNER_TEMP/gradle-${version}-bin.zip"
trap 'rm -f "$archive"' EXIT
for attempt in 1 2 3; do
  if gh api -H 'Accept: application/octet-stream' "repos/gradle/gradle-distributions/releases/assets/$asset" > "$archive"; then
    break
  fi
  if [[ "$attempt" == 3 ]]; then exit 1; fi
  sleep 5
done
printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c -
unzip -q "$archive" -d "$RUNNER_TEMP/dsh-gradle"
printf '%s\n' "$RUNNER_TEMP/dsh-gradle/gradle-$version/bin" >> "$GITHUB_PATH"
