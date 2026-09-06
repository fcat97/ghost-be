#!/bin/sh
# JitPack's install command.
#
# The Kotlin Toolchain's Android Gradle Plugin build checks license
# acceptance for whatever SDK packages it needs (compileSdk platform,
# build-tools, ...), but it only downloads those packages -- and reveals
# which license(s) they need -- partway through the same build that
# performs the check. Pre-accepting every license we can discover ahead
# of time (jitpack-accept-android-licenses.sh, in before_install) isn't
# enough on its own: we've seen a build fail on a license id that never
# showed up in a from-scratch `sdkmanager --licenses` review beforehand.
#
# So: try the real publish; if it fails, re-run the license acceptance
# script (now operating on an $ANDROID_HOME that includes whatever
# packages this first attempt just downloaded, so their license shows
# up this time) and try once more. The second attempt only needs to
# accept a license and re-run already-cached downloads, so it's cheap.
set -u

script_dir=$(dirname "$0")

attempt() {
  ./kotlin publish mavenLocal -m client
}

dump_diagnostics() {
  status=$1
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
}

attempt
status=$?

if [ "$status" -ne 0 ]; then
  echo "First publish attempt failed (exit=$status) -- re-accepting licenses against whatever got downloaded, then retrying once."
  "$script_dir/jitpack-accept-android-licenses.sh" || true
  attempt
  status=$?
fi

if [ "$status" -ne 0 ]; then
  dump_diagnostics "$status"
fi

exit "$status"
