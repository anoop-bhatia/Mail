# Mail native toolchain

The Android build uses JDK 25, Android NDK r30 (`30.0.16248370`) and CMake `4.4.4`. These are pinned in `app/build.gradle.kts`; CMake also enforces its minimum version in `app/CMakeLists.txt`. The Android namespace/application ID remains `in.anoopbhatia.mail`.

Official release information: [Android NDK](https://developer.android.com/ndk/downloads) and [CMake](https://cmake.org/download/). CMake 4.5 release candidates are not used.

## Install

Install JDK 25, the Android SDK command-line tools and Ninja first. On Linux x86_64:

```sh
export JAVA_HOME=/path/to/jdk-25
export ANDROID_HOME=/path/to/Android/Sdk
bash tools/install-native-toolchain.sh
```

The script installs the exact NDK through the SDK manager and the official CMake release archive under `$HOME/.local/share/mail-tools`, checking its pinned SHA-256. Android's CMake catalog can lag behind Kitware's stable releases. Add the path printed by the script as `cmake.dir` in your untracked `local.properties`; preserve your existing `sdk.dir` and other local settings. CI performs the same setup. Other platforms can install the matching official CMake binary and set `cmake.dir` themselves.

## Pinned dependencies and warning controls

Protobuf 36.2 (C++ runtime 7.36.2), protoc 36.2 and Abseil 20250512.1 are vendored source subsets,
hash-pinned in `tools/update_cld3.py` and listed with their archive SHA-256s in
`app/src/main/jni/third_party/README.md`; CLD3 stays at the archived upstream commit recorded
there. The regenerated CLD3 messages enforce the runtime with
`#if PROTOBUF_VERSION != 7036002 … #error`, and `PROTOBUF_VERSION` is never redefined locally.

Nothing in the native build pins a version or adds a flag to silence diagnostics:
`app/CMakeLists.txt` and `app/cmake/cld3.cmake` contain no `-Wno-*`, `-w`, `-Werror` or
`-fpermissive`; the only flags are `-ffile-prefix-map`, `-DHAVE_MEMRCHR -DHAVE_PTHREAD`,
`-Wl,--build-id=none` and `-Wl,-z,max-page-size=16384`. `python3
tools/audit-warning-suppressions.py --root app/src/main/jni` reports 1,395 controls: 1,379 in the
vendored Protobuf/Abseil trees and 16 first-party/build, all upstream-authored (the three
`#pragma GCC diagnostic push/ignored "-Wstrict-aliasing"/pop` triplets protoc emits into the
regenerated `.pb.h` files, and seven upstream `NOLINT`/MSVC pragma lines in `cld_3`).

There is no `-Werror` for native code, so warnings remain visible but never fail the build; the
auditor is an inventory, not a warning-free gate. The obsolete `app/src/main/jni/google/` 3.6.1
directory has been removed.

## Validate

```sh
./gradlew :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleLarge \
  :app:lintDebug :app:lintRelease :app:lintLarge \
  --no-configuration-cache --console=plain --max-workers=2
python3 tools/check-native-packaging.py
python3 tools/check-api-variants.py
CMAKE=/path/to/cmake-4.4.4/bin/cmake tools/cld3/run-host.sh
```

Debug is the app's available unit-test variant. Release and Large APKs are unsigned unless signing properties are supplied. All APK variants include `libmail.so` for `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`. Java loaders and the CMake target use the same name. JNI symbols retain the existing `in.anoopbhatia.mail` namespace.

The packaging check verifies the complete ABI set, ELF machine type, every packaged shared library's 16-KiB-compatible load segments, exact JNI exports declared by `mail.cc`, absence of the previous library name, and `zipalign -c -P 16 4`. JNI libraries currently retain compressed/extracted packaging; ELF alignment matters after extraction even when ZIP mmap alignment is not applicable. These checks do not replace on-device runtime testing on a 16-KiB Android device.

The provider audit requires SDK `apkanalyzer`. It checks the actual APK application IDs, debug/heap flags, identical runtime credential XML, exact selected OAuth callbacks, provider/startup classes in DEX, and unchanged class names in Release/Large R8 mappings. Large must use Release client identities. It does not contact provider accounts or verify external OAuth registrations or signing-certificate ownership. The Graph startup tests cover JVM routing and source contracts; Android service execution and live-account first sync still need device testing.
