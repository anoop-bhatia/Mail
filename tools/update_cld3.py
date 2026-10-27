#!/usr/bin/env python3
"""Restore pinned native dependencies and regenerate CLD3 messages (Python 3, Linux x86_64)."""
import hashlib
import pathlib
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
JNI = ROOT / "app/src/main/jni"
PINS = {
    "protobuf": ("https://github.com/protocolbuffers/protobuf/releases/download/v36.2/protobuf-36.2.tar.gz", "3d9642a662d10e68ebae5e53f14dcce5105684212d5078f8e0d47d1ab3ae6b64"),
    "abseil": ("https://codeload.github.com/abseil/abseil-cpp/tar.gz/refs/tags/20250512.1", "9b7a064305e9fd94d124ffa6cc358592eb42b5da588fb4e07d09254aa40086db"),
    "protoc": ("https://github.com/protocolbuffers/protobuf/releases/download/v36.2/protoc-36.2-linux-x86_64.zip", "121f6c7afe1d4d0e3ea6aab9432038599250134cbf4474cb1167d2c7decd4278"),
}
CLD3_REV = "b48dc46512566f5a2d41118c8c1116c4f96dc661"
SCHEMAS = {
    "feature_extractor": "2717d4dbbb3149f9dad237669c93e2e78080e5f2133c46b7a001305d3b869b90",
    "sentence": "ca36dda15745825c00227bf19ccb3c58a4f958be83d09c7edf6403dd2a97bd80",
    "task_spec": "a18332e7394772f0c89eff64e5c03b6d4e420346f8b1f1a57fc1da0341f0d0b6",
}
for name, digest in SCHEMAS.items():
    PINS[name] = (f"https://raw.githubusercontent.com/google/cld3/{CLD3_REV}/src/{name}.proto", digest)


def download(name, dest):
    url, expected = PINS[name]
    data = urllib.request.urlopen(url).read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise RuntimeError(f"{name}: SHA-256 mismatch: {actual}")
    dest.write_bytes(data)
    return dest


def main():
    cld3 = JNI / "cld_3"
    manifest = cld3 / "UPSTREAM-SHA256SUMS"
    for line in manifest.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        expected, relative = line.split("  ", 1)
        if hashlib.sha256((cld3 / relative).read_bytes()).hexdigest() != expected:
            raise RuntimeError(f"CLD3 source/model differs from pinned upstream: {relative}")
    with tempfile.TemporaryDirectory(prefix="cld3-update-") as tmp:
        tmp = pathlib.Path(tmp)
        for name in ("protobuf", "abseil"):
            archive = download(name, tmp / f"{name}.tar.gz")
            with tarfile.open(archive) as tf:
                tf.extractall(tmp, filter="data")
        vendor = JNI / "third_party"
        vendor.mkdir(exist_ok=True)
        selections = {
            "protobuf": (tmp / "protobuf-36.2", ["CMakeLists.txt", "LICENSE", "src", "cmake", "third_party/utf8_range"]),
            "abseil": (tmp / "abseil-cpp-20250512.1", ["CMakeLists.txt", "LICENSE", "absl", "CMake"]),
        }
        for name, (source, paths) in selections.items():
            target = vendor / name
            if target.exists():
                shutil.rmtree(target)
            target.mkdir()
            for item in paths:
                src, dst = source / item, target / item
                dst.parent.mkdir(parents=True, exist_ok=True)
                if src.is_dir():
                    shutil.copytree(src, dst)
                else:
                    shutil.copy2(src, dst)
        with zipfile.ZipFile(download("protoc", tmp / "protoc.zip")) as zf:
            zf.extractall(tmp / "protoc")
        protoc = tmp / "protoc/bin/protoc"
        protoc.chmod(0o755)
        schemas = JNI / "cld_3/protos"
        for name in SCHEMAS:
            download(name, schemas / f"{name}.proto")
        subprocess.run([str(protoc), "--version"], check=True)
        subprocess.run([str(protoc), f"--proto_path={JNI}", f"--cpp_out={JNI}",
                        *[str(schemas / f"{name}.proto") for name in ("feature_extractor", "sentence", "task_spec")]], check=True)
    print("Restored Protobuf C++ 7.36.2 / protoc 36.2 and Abseil 20250512.1; regenerated CLD3 messages.")


if __name__ == "__main__":
    main()
