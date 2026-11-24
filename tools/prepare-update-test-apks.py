"""Build two tiny installer fixtures for the locally assembled debug APK."""
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME") or ROOT / ".sdk")
BUILD_TOOLS = SDK / "build-tools/36.0.0"
METADATA = ROOT / "app/build/outputs/apk/debug/output-metadata.json"
OUTPUT = ROOT / "app/build/outputs/update-test"


def main():
    metadata = json.loads(METADATA.read_text(encoding="utf-8"))
    build = metadata["elements"][0]
    code = build["versionCode"] + 1
    original = ROOT / ".android/debug.keystore"
    if not original.is_file():
        original = Path.home() / ".android/debug.keystore"
    java_bin = Path(os.environ["JAVA_HOME"]) / "bin"
    signer = [java_bin / "java", "-jar", BUILD_TOOLS / "lib/apksigner.jar"]

    def certificate(apk):
        result = subprocess.check_output([*signer, "verify", "--print-certs", apk], text=True)
        return next(line for line in result.splitlines() if "certificate SHA-256 digest:" in line)

    target_cert = certificate(METADATA.parent / build["outputFile"])
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=OUTPUT) as tmp:
        work = Path(tmp)
        manifest = work / "AndroidManifest.xml"
        manifest.write_text(
            f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{metadata["applicationId"]}">'
            '<application android:label="AutoTurn QA" android:hasCode="false" /></manifest>', encoding="utf-8")
        unsigned = work / "fixture.apk"
        subprocess.run([BUILD_TOOLS / "aapt2", "link", "--manifest", manifest, "-o", unsigned,
                        "-I", SDK / "platforms/android-37.0/android.jar", "--min-sdk-version", "31",
                        "--target-sdk-version", "36", "--version-code", str(code), "--version-name", f"99.0.{code}"],
                       check=True)
        alternate = work / "alternate.jks"
        subprocess.run([java_bin / "keytool", "-genkeypair", "-keystore", alternate, "-alias", "androiddebugkey",
                        "-storepass", "android", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "3", "-dname", "CN=AutoTurn QA"], check=True)
        for filename, key in (("autoturn-update-candidate.apk", original), ("autoturn-wrong-signature.apk", alternate)):
            apk = work / filename
            subprocess.run([*signer, "sign", "--ks", key, "--ks-key-alias", "androiddebugkey",
                            "--ks-pass", "pass:android", "--out", apk, unsigned], check=True)
            if (certificate(apk) == target_cert) != (key == original):
                raise RuntimeError("Unexpected signer for " + filename)
            apk.replace(OUTPUT / filename)
    print(f"Created update validation fixtures for versionCode {code} in {OUTPUT}")


if __name__ == "__main__":
    main()
