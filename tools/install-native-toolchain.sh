#!/usr/bin/env bash
# Official CMake is installed outside the SDK: Android's catalog may lag stable releases.
set -euo pipefail
SDK_ROOT=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Android/Sdk"}}
TOOLS_ROOT=${MAIL_TOOLS_ROOT:-"$HOME/.local/share/mail-tools"}
CMAKE_VERSION=4.4.4
CMAKE_SHA256=e5bb807f7728cb60cd8b27ebc97a2edb469b68655f21e844a600c3575b76f5bb
NDK_VERSION=30.0.16248370
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || {
  echo 'This installer supports Linux x86_64; install official CMake 4.4.4 on other hosts.' >&2
  exit 1
}
SDKMANAGER=${SDKMANAGER:-"$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"}
"$SDKMANAGER" "ndk;$NDK_VERSION"
DEST="$TOOLS_ROOT/cmake-$CMAKE_VERSION"
if [[ ! -x "$DEST/bin/cmake" ]] || ! "$DEST/bin/cmake" --version | head -1 | grep -qx "cmake version $CMAKE_VERSION"; then
  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT
  ARCHIVE="cmake-$CMAKE_VERSION-linux-x86_64.tar.gz"
  curl --fail --location --retry 3 -o "$TMP/$ARCHIVE" \
    "https://github.com/Kitware/CMake/releases/download/v$CMAKE_VERSION/$ARCHIVE"
  printf '%s  %s\n' "$CMAKE_SHA256" "$TMP/$ARCHIVE" | sha256sum --check
  mkdir -p "$DEST"
  tar -xzf "$TMP/$ARCHIVE" --strip-components=1 -C "$DEST"
fi
# AGP locates custom CMake using cmake.dir; Ninja must be available on PATH.
command -v ninja >/dev/null || { echo 'Install Ninja (ninja-build) first.' >&2; exit 1; }
"$DEST/bin/cmake" --version
printf 'Use cmake.dir=%s in local.properties.\n' "$DEST"
if [[ -n ${GITHUB_ENV:-} ]]; then
  printf 'MAIL_CMAKE_DIR=%s\n' "$DEST" >> "$GITHUB_ENV"
fi
