"""Offline JVM tests and Android SDK 34 packaging. Run with py build.py verify."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
PACKAGE = "com.hdz.wearcontrol"
JDK = Path(os.environ.get("WEAREARS_JDK", Path.home() / "android-tools/jdk-ms")) / "bin"
SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or
           Path(os.environ.get("LOCALAPPDATA", ".")) / "Android/Sdk")
BT = SDK / "build-tools/34.0.0"
ANDROID = SDK / "platforms/android-34/android.jar"
MODULES = json.loads((ROOT / "drivers/modules.json").read_text(encoding="utf-8"))


def run(*args, **kwargs):
    # SDK 34's Windows native tools cannot reliably open absolute paths containing CJK characters.
    local = [arg.relative_to(ROOT) if isinstance(arg, Path) and arg.is_relative_to(ROOT) else arg for arg in args]
    subprocess.run([str(arg) for arg in local], check=True, cwd=ROOT, **kwargs)


def javac(tmp, classes, files, libraries=()):
    sources = tmp / "sources.txt"
    sources.write_text("".join('"' + p.as_posix() + '"\n' for p in sorted(files)), encoding="utf-8")
    cp = os.pathsep.join(map(str, (classes, *libraries)))
    run(JDK / "javac", "-J-Dfile.encoding=UTF-8", "-encoding", "UTF-8", "--release", "11",
        "-XDstringConcat=inline", "-cp", cp, "-d", classes, "@" + str(sources))


def catalog(tmp, names):
    file = tmp / "InstalledDrivers.java"
    factories = ",\n            ".join(f"new {PACKAGE}.{MODULES[name]}.Module()" for name in names)
    file.write_text(f"package {PACKAGE};\nfinal class InstalledDrivers {{\n"
                    f"    static final DriverModule[] ALL = {{\n            {factories}\n    }};\n}}\n",
                    encoding="utf-8")
    return file


def libraries():
    jars = []
    for line in (ROOT / "vendor/SHA256SUMS").read_text().splitlines():
        digest, name = line.split()
        jar = ROOT / "vendor" / name
        if hashlib.sha256(jar.read_bytes()).hexdigest() != digest:
            raise ValueError("Dependency checksum mismatch: " + name)
        jars.append(jar)
    return jars


def test(tmp, classes, files, mains):
    tests = tmp / "tests"
    tests.mkdir(exist_ok=True)
    javac(tmp, tests, files, [classes])
    for name in mains:
        run(JDK / "java", "-ea", "-cp", os.pathsep.join(map(str, (tests, classes))), PACKAGE + "." + name)


def signing_key():
    custom = os.environ.get("WEAREARS_KEYSTORE")
    if custom:
        if not os.environ.get("WEAREARS_STORE_PASS") or not os.environ.get("WEAREARS_KEY_ALIAS"):
            raise ValueError("Set WEAREARS_STORE_PASS and WEAREARS_KEY_ALIAS for a custom key")
        return Path(custom), os.environ["WEAREARS_KEY_ALIAS"], "env:WEAREARS_STORE_PASS"
    key = ROOT / ".local/debug.p12"
    key.parent.mkdir(exist_ok=True)
    if not key.exists():
        # Generate privately, then publish the complete key without replacing a concurrent build's key.
        with tempfile.TemporaryDirectory(dir=key.parent) as staging:
            fresh = Path(staging) / "debug.p12"
            run(JDK / "keytool", "-genkeypair", "-storetype", "PKCS12", "-keystore", fresh,
                "-storepass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "10000", "-dname", "CN=WearEars Local Debug")
            try:
                os.link(fresh, key)
            except FileExistsError:
                pass
    return key, "androiddebugkey", "pass:android"


def apk(tmp, classes, names):
    jars = libraries()
    unsigned = tmp / "unsigned.apk"
    aligned = tmp / "aligned.apk"
    res = tmp / "resources.zip"
    gen = tmp / "generated"
    run(BT / "aapt2", "compile", "--dir", ROOT / "app/res", "-o", res)
    run(BT / "aapt2", "link", "-o", unsigned, "-I", ANDROID, "--manifest", ROOT / "app/AndroidManifest.xml",
        "--java", gen, "--min-sdk-version", "30", "--target-sdk-version", "34",
        "--version-code", "19", "--version-name", "0.5.0", "--auto-add-overlay", res)
    javac(tmp, classes, [*(ROOT/"app/src").rglob("*.java"), *gen.rglob("*.java")], [ANDROID, *jars])
    code = tmp / "classes.jar"
    run(JDK / "jar", "cf", code, "-C", classes, ".")
    dex = tmp / "dex"
    dex.mkdir()
    run(JDK / "java", "-cp", BT / "lib/d8.jar", "com.android.tools.r8.D8", "--release",
        "--lib", ANDROID, "--min-api", "30", "--output", dex, code, *jars)
    with zipfile.ZipFile(unsigned, "a") as archive:
        for file in sorted(dex.glob("*.dex")):
            archive.write(file, file.name, compress_type=zipfile.ZIP_DEFLATED)
        for notice in (ROOT / "LICENSE", ROOT / "NOTICE", ROOT / "vendor/Apache-2.0.txt",
                       ROOT / "vendor/Protobuf-LICENSE.txt", ROOT / "vendor/NOTICE.md"):
            archive.write(notice, "assets/licenses/" + notice.name, compress_type=zipfile.ZIP_DEFLATED)
    run(BT / "zipalign", "-f", "4", unsigned, aligned)
    key, alias, password = signing_key()
    signer = [JDK / "java", "-jar", BT / "lib/apksigner.jar"]
    signed = tmp / "signed.apk"
    keypass = ["--key-pass", "env:WEAREARS_KEY_PASS"] if os.environ.get("WEAREARS_KEY_PASS") else []
    run(*signer, "sign", "--ks", key, "--ks-key-alias", alias, "--ks-pass", password,
        *keypass, "--v4-signing-enabled", "false", "--out", signed, aligned)
    run(*signer, "verify", "--verbose", signed)
    run(BT / "zipalign", "-c", "4", signed)
    with zipfile.ZipFile(signed) as archive:
        for member in ("AndroidManifest.xml", "resources.arsc", "classes.dex"):
            if not archive.getinfo(member).file_size:
                raise ValueError("Empty " + member)
    label = "" if list(names) == list(MODULES) else "-" + ("-".join(names) or "standard")
    output = BUILD / ("WearEars" + label + ".apk")
    os.replace(signed, output)
    print(output, hashlib.sha256(output.read_bytes()).hexdigest(), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("task", choices=["core", "test", "apk", "verify"], nargs="?", default="verify")
    parser.add_argument("--drivers", default="all", help="all, none, or comma-separated module IDs")
    args = parser.parse_args()
    names = list(MODULES) if args.drivers == "all" else ([] if args.drivers == "none" else args.drivers.split(","))
    if len(set(names)) != len(names) or not set(names) <= MODULES.keys():
        parser.error("Unknown or duplicate driver")
    if args.task in ("test", "verify") and list(names) != list(MODULES):
        parser.error("Protocol fixtures exercise all drivers. Use apk for a reduced build.")
    BUILD.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=BUILD, prefix="run-") as folder:
        tmp = Path(folder)
        classes = tmp / "classes"
        classes.mkdir()
        javac(tmp, classes, (ROOT / "core/src").rglob("*.java"))
        if args.task in ("core", "test", "verify"):
            test(tmp, classes, [ROOT / "tests/CoreChecks.java"], ["CoreChecks"])
        if args.task == "core":
            return
        sources = [p for name in names for p in (ROOT / "drivers" / name / "src").rglob("*.java")]
        javac(tmp, classes, [catalog(tmp, names), *sources])
        if args.task in ("test", "verify"):
            files = sorted((ROOT / "tests").glob("*.java"))
            cases = [p.stem for p in files if p.stem.endswith("Checks") and p.stem != "CoreChecks"]
            test(tmp, classes, files, cases)
            run(sys.executable, "-X", "utf8", ROOT / "tests/platform_checks.py")
        if args.task in ("apk", "verify"):
            apk(tmp, classes, names)


if __name__ == "__main__":
    main()
