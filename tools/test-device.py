"""Run release checks on one prepared phone. Private logs and snapshots stay in artifacts/."""
import argparse
from datetime import datetime
import hashlib
import io
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tarfile
import tempfile


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "app.autoturn.vpn"
RUNNER = PACKAGE + ".instrumentation/android.test.InstrumentationTestRunner"
SOURCES = ROOT / "app/src/androidTest/java/app/autoturn/vpn"
SPECIAL = {
    "ProtectedModeDeviceTest#testSavedWifiNetworkTransitions": "requires two named, reachable, saved Wi-Fi networks",
}


def cases(selectors):
    result = []
    for path in sorted(SOURCES.glob("*Test.java")):
        for method in re.findall(r"\bpublic\s+void\s+(test\w+)\s*\(", path.read_text(encoding="utf-8")):
            case = path.stem + "#" + method
            if not selectors or case in selectors or path.stem in selectors:
                result.append(case)
    for selector in selectors:
        if not any(case == selector or case.split("#")[0] == selector for case in result):
            raise ValueError("Unknown test: " + selector)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="serial from adb devices")
    parser.add_argument("--adb", default=shutil.which("adb") or str(ROOT / ".sdk/platform-tools/adb"))
    parser.add_argument("--arg", nargs=2, action="append", default=[], metavar=("NAME", "VALUE"))
    parser.add_argument("--before-update", type=Path, help="before.tar captured on this phone before installation")
    parser.add_argument("selectors", nargs="*", help="optional ClassName or ClassName#testMethod")
    args = parser.parse_args()
    selected = cases(args.selectors)
    options = dict(protectedModeAudit="true", protectedRealProfiles="true", protectedBrowserAudit="true",
                   islandDemo="1", holdMs="500", compareSavedSettings="true", legacyApkAudit="true")
    options.update(args.arg)
    adb = [args.adb, "-s", args.serial]

    def call(*command, data=None):
        return subprocess.run([*adb, *command], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              check=True, timeout=90).stdout

    def shell(*command):
        return call("shell", shlex.join(command)).decode().strip()

    if RUNNER not in shell("pm", "list", "instrumentation", PACKAGE):
        raise RuntimeError("Install the matching debug APK and androidTest APK first")
    parent = ROOT / "artifacts/device-tests"
    parent.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix=datetime.now().strftime("%Y%m%d-%H%M%S-"), dir=parent))
    archive = call("exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "files/settings.enc", "shared_prefs")
    with tarfile.open(fileobj=io.BytesIO(archive)) as backup:
        settings = backup.extractfile("files/settings.enc").read()
        saved_preferences = {member.name for member in backup if member.isfile() and member.name.startswith("shared_prefs/")}
    comparison = settings
    if args.before_update:
        with tarfile.open(args.before_update) as before:
            comparison = before.extractfile("files/settings.enc").read()
    fingerprint = hashlib.sha256(settings).hexdigest()

    def verify_settings():
        if shell("run-as", PACKAGE, "sha256sum", "files/settings.enc").split()[0] != fingerprint:
            raise RuntimeError("Encrypted settings differ from the snapshot in " + str(output))

    verify_settings()
    (output / "before.tar").write_bytes(archive)
    device_baseline = "cache/" + output.name + ".enc"
    data_dir = shell("run-as", PACKAGE, "pwd")
    options["baselinePath"] = data_dir + "/" + device_baseline
    extras = [arg for key, value in options.items() for arg in ("-e", key, value)]
    print("Device report:", output, flush=True)
    radios = {name: shell("settings", "get", "global", key) for name, key in
              (("wifi", "wifi_on"), ("data", "mobile_data"))}
    stay_on = shell("settings", "get", "global", "stay_on_while_plugged_in")

    def restore():
        # Instrumentation runs inside AutoTurn. Stop its processes before restoring any bytes.
        shell("am", "force-stop", PACKAGE)
        existing = shell("run-as", PACKAGE, "find", "shared_prefs", "-maxdepth", "1", "-type", "f").splitlines()
        for name in set(existing) - saved_preferences:
            shell("run-as", PACKAGE, "rm", "-f", name)
        call("shell", "-T", "run-as", PACKAGE, "tar", "-xf", "-", data=archive)
        verify_settings()
        for name, expected in radios.items():
            key = "wifi_on" if name == "wifi" else "mobile_data"
            if expected in {"0", "1"} and shell("settings", "get", "global", key) != expected:
                shell("svc", name, "enable" if expected == "1" else "disable")

    results = {}
    try:
        call("shell", "-T", "run-as", PACKAGE, "tee", device_baseline, data=comparison)
        shell("svc", "power", "stayon", "usb")
        for case in selected:
            reason = SPECIAL.get(case)
            if case.endswith("#testSavedWifiNetworkTransitions") and {"plainWifiSsid", "homeWifiSsid"} <= options.keys():
                reason = None
            if reason and case not in args.selectors:
                results[case] = "SKIP: " + reason
                print("SKIP", case, reason, flush=True)
                continue
            shell("input", "keyevent", "KEYCODE_WAKEUP")
            shell("wm", "dismiss-keyguard")
            print("RUN ", case, flush=True)
            try:
                command = ["am", "instrument", "-w", "-r", *extras, "-e", "class", PACKAGE + "." + case, RUNNER]
                process = subprocess.run([*adb, "shell", shlex.join(command)], capture_output=True, timeout=1200)
                log = process.stdout.decode(errors="replace") + process.stderr.decode(errors="replace")
                (output / (case + ".log")).write_text(log, encoding="utf-8")
                passed = process.returncode == 0 and re.search(r"^OK \(1 test\)\s*$", log, re.M)
                skipped = re.search(r"\bSKIP(?:PED)?[ :]+([^\r\n]+)", log)
                results[case] = ("SKIP: " + skipped[1] if passed and skipped else "PASS" if passed else "FAIL")
            except subprocess.TimeoutExpired:
                results[case] = "FAIL: timed out"
            finally:
                restore()
            print(results[case], case, flush=True)
            (output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    finally:
        restore()
        shell("run-as", PACKAGE, "rm", "-f", device_baseline)
        if stay_on == "null":
            shell("settings", "delete", "global", "stay_on_while_plugged_in")
        else:
            shell("settings", "put", "global", "stay_on_while_plugged_in", stay_on)
        shell("am", "start", "-n", PACKAGE + "/.MainActivity")
        (output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    return int(any(value.startswith("FAIL") for value in results.values()))


if __name__ == "__main__":
    raise SystemExit(main())
