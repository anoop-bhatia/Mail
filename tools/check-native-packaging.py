#!/usr/bin/env python3
"""Verify every configured ABI and JNI entry point in built Mail APKs."""
import argparse
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
MACHINES = {"armeabi-v7a": 40, "arm64-v8a": 183, "x86": 3, "x86_64": 62}


def check_elf(data, abi, name):
    assert data[:4] == b"\x7fELF" and data[5] == 1, f"Not little-endian ELF: {name}"
    assert struct.unpack_from("<H", data, 18)[0] == MACHINES[abi], f"Wrong machine: {name}"
    elf64 = data[4] == 2
    if elf64:
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        fmt = "<IIQQQQQQ"
    else:
        offset = struct.unpack_from("<I", data, 28)[0]
        size, count = struct.unpack_from("<HH", data, 42)
        fmt = "<IIIIIIII"
    loads = 0
    for i in range(count):
        ph = struct.unpack_from(fmt, data, offset + i * size)
        if ph[0] != 1:  # PT_LOAD
            continue
        loads += 1
        alignment = ph[7]
        file_offset, virtual_address = (ph[2], ph[3]) if elf64 else (ph[1], ph[2])
        assert alignment >= 16384 and alignment & (alignment - 1) == 0, (
            f"{name}: PT_LOAD alignment {alignment} is below 16 KiB or invalid")
        assert file_offset % 16384 == virtual_address % 16384, f"{name}: incongruent PT_LOAD"
    assert loads, f"{name}: missing PT_LOAD"
    return loads


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="*", type=Path)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Android/Sdk"))))
    args = parser.parse_args()
    apks = args.apks or sorted((ROOT / "app/build/outputs/apk").glob("*/*.apk"))
    assert apks, "No APKs found"
    nm = args.sdk / "ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
    zipalign = args.sdk / "build-tools/37.0.0/zipalign"
    expected = set(re.findall(r"\b(Java_in_anoopbhatia_mail_\w+)\s*\(", (ROOT / "app/src/main/jni/mail.cc").read_text()))
    assert expected, "No expected JNI exports found"
    for apk in apks:
        with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as tmp:
            libs = [n for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so")]
            assert not any("fairemail" in n.lower() for n in libs), f"Stale native library in {apk}"
            mail = [n for n in libs if n.endswith("/libmail.so")]
            assert {n.split("/")[1] for n in mail} == ABIS and len(mail) == 4, f"Incomplete ABI set in {apk}"
            for name in libs:
                abi = name.split("/")[1]
                data = archive.read(name)
                loads = check_elf(data, abi, name)
                if name in mail:
                    output = Path(tmp) / abi / "libmail.so"
                    output.parent.mkdir()
                    output.write_bytes(data)
                    symbols = subprocess.check_output([str(nm), "-D", "--defined-only", str(output)], text=True)
                    actual = {line.split()[-1] for line in symbols.splitlines() if line.split()[-1].startswith("Java_")}
                    assert actual == expected, f"JNI mismatch in {name}: missing={expected - actual}, extra={actual - expected}"
                    print(f"{apk.name}: {abi}: libmail.so, {len(actual)} JNI exports, {loads} aligned PT_LOAD segments: PASS")
            # For compressed JNI libraries this checks the ZIP layout; ELF alignment
            # above is still mandatory after Android extracts them at installation.
            subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(apk)], check=True)
        print(f"{apk.name}: all {len(libs)} packaged native libraries and ZIP alignment: PASS")


if __name__ == "__main__":
    main()
