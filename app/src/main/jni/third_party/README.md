# Native CLD3 dependencies

The detector implementation, model weights, script tables, language names, and JNI input limits remain unchanged. `cld_3/src` matches archived [google/cld3](https://github.com/google/cld3/tree/b48dc46512566f5a2d41118c8c1116c4f96dc661) at `b48dc46512566f5a2d41118c8c1116c4f96dc661`. This is a dependency/build modernization, not a new CLD3 model or a claim that archived CLD3 is actively maintained.

## Pinned sources

| Component | Version/source | SHA-256 of downloaded archive |
| --- | --- | --- |
| Protobuf | [36.2 release](https://github.com/protocolbuffers/protobuf/releases/tag/v36.2), C++ runtime **7.36.2** | `3d9642a662d10e68ebae5e53f14dcce5105684212d5078f8e0d47d1ab3ae6b64` |
| `protoc` | Official 36.2 Linux x86-64 ZIP, used only for regeneration | `121f6c7afe1d4d0e3ea6aab9432038599250134cbf4474cb1167d2c7decd4278` |
| Abseil | [20250512.1](https://github.com/abseil/abseil-cpp/releases/tag/20250512.1), Protobuf 36.2's declared dependency | `9b7a064305e9fd94d124ffa6cc358592eb42b5da588fb4e07d09254aa40086db` |

The Protobuf source-only subset contains its root CMake file, license, `src`, `cmake`, and bundled `third_party/utf8_range`; Abseil contains its root CMake file, license, `absl`, and `CMake`. Files within these selections are unmodified upstream sources, including upstream tests that are **not** enabled in application builds. No precompiled library is bundled. The obsolete `jni/google/protobuf` 3.6.1 tree is removed.

Protobuf's license is in `protobuf/LICENSE`; Abseil's is in `abseil/LICENSE`; utf8_range's is in `protobuf/third_party/utf8_range/LICENSE`. The CLD3 Apache-2.0 license is in `../cld_3/LICENSE` and its source/schema headers. Keep these licenses with future source updates.

## Regeneration

From the repository root, on Linux x86-64 with Python 3.12+:

```sh
python3 tools/update_cld3.py
```

The script downloads and checks every archive and each authoritative schema against pinned SHA-256 hashes, restores the source-only vendor subsets, and regenerates all six CLD3 `.pb.cc`/`.pb.h` files using `protoc` 36.2. The compiler is temporary and is not required for normal Android builds. Schema source paths are now consistently `cld_3/protos/*.proto`; message packages, fields, defaults, and wire numbers are unchanged. Review and commit all regenerated files and pin changes together.

| Schema from the pinned CLD3 revision | SHA-256 |
| --- | --- |
| `feature_extractor.proto` | `2717d4dbbb3149f9dad237669c93e2e78080e5f2133c46b7a001305d3b869b90` |
| `sentence.proto` | `ca36dda15745825c00227bf19ccb3c58a4f958be83d09c7edf6403dd2a97bd80` |
| `task_spec.proto` | `a18332e7394772f0c89eff64e5c03b6d4e420346f8b1f1a57fc1da0341f0d0b6` |

[C++ generated code and runtime must match exactly](https://protobuf.dev/support/cross-version-runtime-guarantee/#cpp). Generated headers enforce `PROTOBUF_VERSION == 7036002`; do not bypass that check or update the runtime without regenerating every message.

## Build integration

`app/cmake/cld3.cmake` is shared by the Android JNI library and native regression tests. It requires C++17, uses the official Protobuf-lite/Abseil CMake targets, builds position-independent code, and disables full Protobuf, `protoc`, upb, installation, and dependency downloads. All native dependency sources are available offline in this repository. CLD3 uses an **object library** so registry-only translation units are always linked, as they were in the previous shared-library source list. The UTF-8 validity dependency is explicitly propagated because Protobuf 36.2's public `parse_context.h` includes its header while the upstream lite target links it privately.

Android minSdk 26, NDK 27.3.13750724, the four ABI filters, JNI entry points, and 16-KiB ELF page alignment are retained. Test commands and fixture provenance are in `tools/cld3/README.md`.

## Forks checked before migration

A bounded fork/package search found no verified 36.2 / C++ 7.36.2 migration to reuse. [ropensci/cld3's Protobuf 21.12 update](https://github.com/ropensci/cld3/commit/85d072a9ccecd025ba2c445062cb26afb2b66b57) is a Windows packaging change, not a current C++ source migration. [pycld3](https://github.com/bsolomon1124/pycld3) documents older system-Protobuf builds and is archived. [readdle/ccld3](https://github.com/readdle/ccld3) removes Protobuf through a different pure-C implementation/model representation, so it was not substituted for the existing detector. Upstream's authoritative schemas and regeneration approach were retained instead.
