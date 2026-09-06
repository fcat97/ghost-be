#!/bin/sh
# JitPack's install command. Wrapped in a script (rather than inlined in
# jitpack.yml) so we can print diagnostics after a failure without losing
# the real exit code -- useful while we're still chasing down exactly
# which Android SDK root the Kotlin Toolchain resolves to on JitPack's
# build image.
set -u

./kotlin publish mavenLocal -m client
status=$?

if [ "$status" -ne 0 ]; then
  echo "=== ghost-be JitPack diagnostics (publish exit=$status) ==="
  echo "ANDROID_HOME=${ANDROID_HOME:-<unset>}"
  echo "ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-<unset>}"
  echo "HOME=${HOME:-<unset>}"
  echo "-- cmdline-tools dirs on disk --"
  find / -xdev -maxdepth 6 -iname "cmdline-tools" 2>/dev/null
  echo "-- sdkmanager binaries on disk --"
  find / -xdev -maxdepth 8 -iname "sdkmanager" -type f 2>/dev/null
  echo "-- android-ish dirs under \$HOME/.cache --"
  find "${HOME:-/root}/.cache" -maxdepth 4 -iname "*android*" 2>/dev/null
fi

exit "$status"
