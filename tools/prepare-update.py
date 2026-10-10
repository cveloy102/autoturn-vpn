"""Package an already-built APK and updater metadata for a GitHub Release.

Run after assembleDebug using the original signing key. Never uploads signing keys.
"""
import hashlib
import json
from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[1]
directory = root / "app/build/outputs/apk/debug"
listing = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
element, = listing["elements"]
version = element["versionName"]
code = element["versionCode"]
source = directory / element["outputFile"]
name = f"AutoTurn-{version}.apk"
output = root / "releases"
output.mkdir(exist_ok=True)
shutil.copy2(source, output / name)
digest = hashlib.sha256(source.read_bytes()).hexdigest()
manifest = {"versionCode": code, "versionName": version, "apkName": name, "sha256": digest}
(output / "update.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(json.dumps(manifest, indent=2))
print(f"Attach {name} and update.json to stable GitHub Release v{version}.")
