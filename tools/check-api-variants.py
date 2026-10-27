#!/usr/bin/env python3
"""Audit Debug/Release/Large APK identities and native-provider packaging, not live accounts."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import struct
import subprocess
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "in.anoopbhatia.mail"
REQUIRED_CLASSES = {
    "ApiCredentials", "ApiCredentials$Holder", "ApiCredentials$Entry", "EmailProvider",
    "EntityAccount", "FragmentOAuth", "ServiceAuthenticator", "ServiceSynchronize", "ServiceSend",
    "GraphMailApi", "GraphMailReceiver", "GmailApi", "GmailReceiver", "ZohoApi", "ZohoReceiver",
    "NativeApiSyncRequest", "GraphOAuth", "GmailState", "ZohoState", "NativeApiFolderDefaults",
    "NativeFolderState", "NativeReaderState", "Core", "Core$State", "TupleAccountState",
    "TupleAccountNetworkState", "ApiNetworkHibernation", "DB", "DB_Impl",
}


def dex_classes(data):
    assert data.startswith(b"dex\n"), "Unexpected DEX format"
    strings_size, strings_offset = struct.unpack_from("<II", data, 56)
    types_size, types_offset = struct.unpack_from("<II", data, 64)
    classes_size, classes_offset = struct.unpack_from("<II", data, 96)
    strings = []
    for i in range(strings_size):
        offset = struct.unpack_from("<I", data, strings_offset + i * 4)[0]
        while data[offset] & 0x80:  # Skip modified-UTF8 string's UTF-16 length.
            offset += 1
        offset += 1
        end = data.index(b"\0", offset)
        strings.append(data[offset:end].decode("utf-8", errors="replace"))
    types = [strings[struct.unpack_from("<I", data, types_offset + i * 4)[0]] for i in range(types_size)]
    return {types[struct.unpack_from("<I", data, classes_offset + i * 32)[0]] for i in range(classes_size)}


def accepts_callback(manifest, uri):
    parsed = urllib.parse.urlparse(uri)
    for activity in manifest.findall(".//activity"):
        if activity.get(ANDROID + "name") != "net.openid.appauth.RedirectUriReceiverActivity":
            continue
        for intent in activity.findall("intent-filter"):
            categories = {x.get(ANDROID + "name") for x in intent.findall("category")}
            actions = {x.get(ANDROID + "name") for x in intent.findall("action")}
            if "android.intent.category.BROWSABLE" not in categories or "android.intent.action.VIEW" not in actions:
                continue
            values = intent.findall("data")
            schemes = {x.get(ANDROID + "scheme") for x in values if x.get(ANDROID + "scheme")}
            hosts = {x.get(ANDROID + "host") for x in values if x.get(ANDROID + "host")}
            paths = {x.get(ANDROID + "path") for x in values if x.get(ANDROID + "path")}
            if parsed.scheme in schemes and (not hosts or parsed.netloc in hosts) and (
                    not paths or urllib.parse.unquote(parsed.path) in paths):
                return True
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Android/Sdk"))))
    args = parser.parse_args()
    analyzer = args.sdk / "cmdline-tools/latest/bin/apkanalyzer"
    expected_xml = (ROOT / "app/src/main/res/xml/providers.xml").read_bytes()
    all_resource_hashes = set()
    all_override_hashes = set()
    clients = {}
    for variant in ("debug", "release", "large"):
        apks = sorted((ROOT / "app/build/outputs/apk" / variant).glob("*.apk"))
        assert len(apks) == 1, f"Expected exactly one {variant} APK"
        apk = apks[0]
        xml = subprocess.check_output([str(analyzer), "manifest", "print", str(apk)], text=True)
        manifest = ET.fromstring(xml)
        expected_package = PACKAGE + (".debug" if variant == "debug" else "")
        assert manifest.get("package") == expected_package, f"Wrong {variant} application ID"
        sdk = manifest.find("uses-sdk")
        assert sdk is not None and sdk.get(ANDROID + "minSdkVersion") == "28", f"Wrong {variant} minimum SDK: network-specific roaming requires API28"
        app = manifest.find("application")
        assert (app.get(ANDROID + "debuggable", "false") == "true") == (variant == "debug"), f"Wrong {variant} debuggable flag"
        assert (app.get(ANDROID + "largeHeap", "false") == "true") == (variant == "large"), f"Wrong {variant} heap identity"
        with zipfile.ZipFile(apk) as archive:
            resource = archive.read("xml/providers.xml")
            assert resource == expected_xml, f"{variant}: runtime credentials resource differs from source"
            assert "api-credentials-overrides.properties" in archive.namelist(), f"{variant}: missing overrides resource"
            all_override_hashes.add(hashlib.sha256(archive.read("api-credentials-overrides.properties")).hexdigest())
            all_resource_hashes.add(hashlib.sha256(resource).hexdigest())
            credentials = ET.fromstring(resource).find("apiCredentials")
            assert credentials is not None
            for entry in credentials.findall("api"):
                key = entry.get("key")
                redirect = (entry.get("debugRedirectUri", entry.get("redirectUri"))
                            if variant == "debug" else entry.get("redirectUri"))
                client = (entry.get("debugClientId", entry.get("clientId"))
                          if variant == "debug" else entry.get("clientId"))
                assert client and redirect and accepts_callback(manifest, redirect), f"{variant}/{key}: OAuth callback is not handled: {redirect}"
                clients[variant, key] = client
                print(f"{variant}/{key}: provider={entry.get('providerId')}, redirect={redirect}: manifest PASS")
            classes = set()
            for name in archive.namelist():
                if re.fullmatch(r"classes\d*\.dex", name):
                    classes.update(dex_classes(archive.read(name)))
            for name in REQUIRED_CLASSES:
                descriptor = "L" + PACKAGE.replace(".", "/") + "/" + name + ";"
                assert descriptor in classes, f"{variant}: provider class stripped: {name}"
            assert "Lin/anoopbhatia/mail/CloudSync;" not in classes, f"{variant}: stale cloud implementation"
            assert "Lin/anoopbhatia/mail/WorkerSync;" not in classes, f"{variant}: stale cloud worker"
        if variant != "debug":
            directory = ROOT / "app/build/outputs/mapping" / variant
            mapping = (directory / "mapping.txt").read_text()
            config = (directory / "configuration.txt").read_text()
            assert "-keep class in.anoopbhatia.mail.**" in config, f"{variant}: missing app keep rule"
            for name in REQUIRED_CLASSES:
                qualified = PACKAGE + "." + name
                assert re.search(r"^" + re.escape(qualified) + r" -> " + re.escape(qualified) + r":$", mapping, re.M), f"{variant}: missing/renamed mapping for {name}"
            print(f"{variant}: R8 mapping/configuration preserves {len(REQUIRED_CLASSES)} provider/startup classes: PASS")
        print(f"{variant}: minSdk28, application ID, debuggable/heap flags, runtime resources and {len(REQUIRED_CLASSES)} provider classes in DEX: PASS")
    assert len(all_resource_hashes) == 1, "Variant credentials resources diverge"
    assert len(all_override_hashes) == 1, "Variant provider override resources diverge"
    for key in ("graph", "gmail", "zoho"):
        assert clients["large", key] == clients["release", key], f"Large {key} client differs from Release"
    print("All provider XML/override resources match; Large uses Release client identities. No secrets printed.")
    print("This validates source/packaged wiring, not external OAuth registration, signing-certificate ownership, or live-account first sync.")


if __name__ == "__main__":
    main()
