#!/bin/sh
# Pre-accepts Android SDK licenses under $ANDROID_HOME before the Kotlin
# Toolchain's own Android Gradle Plugin build runs its license check.
#
# JitPack's build image sets ANDROID_HOME but doesn't ship a cmdline-tools
# install under it, so there's no sdkmanager to accept licenses with until
# something downloads one -- and the Kotlin Toolchain only does that as
# part of the actual build, by which point it's too late (the license
# check task fails before we'd get a chance to intervene). Instead, we
# download our own cmdline-tools here, in before_install, and use its
# sdkmanager to accept every license against the real Android repository
# -- the same repository the toolchain's own SDK component downloads
# consult -- so whatever license id they require is already accepted by
# the time the real build runs.
set -e

CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip"

if [ -z "$ANDROID_HOME" ]; then
  echo "ANDROID_HOME is not set, skipping license pre-acceptance" >&2
  exit 0
fi

tmp_dir=$(mktemp -d)
curl -sSL "$CMDLINE_TOOLS_URL" -o "$tmp_dir/cmdline-tools.zip"
unzip -q "$tmp_dir/cmdline-tools.zip" -d "$tmp_dir"

mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
mv "$tmp_dir/cmdline-tools/"* "$ANDROID_HOME/cmdline-tools/latest/"

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses

rm -rf "$tmp_dir"
