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

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  tmp_dir=$(mktemp -d)
  curl -sSL "$CMDLINE_TOOLS_URL" -o "$tmp_dir/cmdline-tools.zip"
  unzip -q "$tmp_dir/cmdline-tools.zip" -d "$tmp_dir"

  mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp_dir/cmdline-tools/"* "$ANDROID_HOME/cmdline-tools/latest/"
  rm -rf "$tmp_dir"
fi

# Force a refresh of the remote package/license index before reviewing
# licenses -- otherwise sdkmanager only knows about whatever packages
# were baked into the cmdline-tools zip at the time it was built, and
# won't prompt for (or accept) a license introduced since then. This
# also picks up licenses for any packages installed under $ANDROID_HOME
# since the last time this ran (e.g. by an earlier, failed build
# attempt), since sdkmanager reviews the whole SDK root, not just what
# it installed itself.
"$SDKMANAGER" --list > /dev/null
yes | "$SDKMANAGER" --licenses

# The Kotlin Toolchain's own Android Gradle Plugin build downloads its OWN
# cmdline-tools under $ANDROID_HOME/cmdline-tools/<its-version> (distinct
# from the "latest" one we just planted above) and uses THAT binary's
# bundled repository manifest to decide which licenses need accepting --
# not ours. Different cmdline-tools versions can know about different
# packages/licenses, so accepting everything our sdkmanager knows about
# doesn't guarantee theirs agrees nothing is outstanding. Find every other
# sdkmanager under $ANDROID_HOME and run --licenses with it too, so
# whichever binary the real build ends up consulting has already accepted
# whatever it thinks needs accepting.
#
# JitPack's image also carries a legacy tools/bin/sdkmanager (pre-dating
# the cmdline-tools rename) that crashes under a modern JDK (missing
# javax.xml.bind, removed since JDK 11) -- harmless to us since we don't
# need that one specifically, but with `set -e` its crash would otherwise
# abort this script before we ever reach the sdkmanager we actually care
# about. Tolerate a failure on any one binary and keep going to the rest.
find "$ANDROID_HOME" -path "$SDKMANAGER" -prune -o -type f -name sdkmanager -print 2>/dev/null |
while IFS= read -r other_sdkmanager; do
  echo "Also accepting licenses via $other_sdkmanager" >&2
  if ! "$other_sdkmanager" --list > /dev/null 2>&1; then
    echo "  ...failed to list packages via $other_sdkmanager, skipping it" >&2
    continue
  fi
  yes | "$other_sdkmanager" --licenses || true
done
