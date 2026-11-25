"""Cold-start the installed APK on one explicit, unlocked ADB test device."""
import argparse
import subprocess
import time


PACKAGE = "com.hdz.wearcontrol"
COMPONENT = PACKAGE + "/.MainActivity"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", help="device serial from adb devices")
    parser.add_argument("--adb", default="adb", help="path to adb")
    args = parser.parse_args()

    def shell(*command):
        result = subprocess.run([args.adb, "-s", args.serial, "shell", *command],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace", timeout=30)
        return result.returncode, result.stdout.strip()

    try:
        code, status = shell("am", "start", "-W", "-S", "-n", COMPONENT)
    except subprocess.TimeoutExpired:
        raise SystemExit(f"Launch did not complete. Check adb -s {args.serial} logcat -b crash -d") from None
    if code or "Status: ok" not in status:
        raise SystemExit("Launch failed: " + status)
    # A successful activity-manager launch can still be followed by an inflation or first-draw crash.
    for _ in range(2):
        time.sleep(3)
        code, pid = shell("pidof", PACKAGE)
        if code or not pid:
            raise SystemExit(f"{PACKAGE} exited after launch. Check adb -s {args.serial} logcat -b crash -d")
    code, activities = shell("dumpsys", "activity", "activities")
    if code or not any("resumedactivity" in line.lower() and COMPONENT in line
                       for line in activities.splitlines()):
        raise SystemExit("MainActivity did not stay in the foreground. Check the screen and crash log.")
    print(f"PASS {args.serial}: cold-started {COMPONENT} and stayed in the foreground")


if __name__ == "__main__":
    main()
