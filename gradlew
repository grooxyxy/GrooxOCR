#!/bin/sh
# Delegating wrapper: uses preinstalled `gradle` (CI) or downloads Gradle 8.7 (local).
# Repo ini tidak menyertakan gradle-wrapper.jar agar ringan; CI memakai
# gradle/actions/setup-gradle sehingga file ini jarang dipakai.
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "gradle tidak ditemukan. Install Gradle 8.7+ lalu ulangi." >&2
exit 1
