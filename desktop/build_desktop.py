"""Offline Windows build & verification for WearEars Desktop.
Usage:
    py -X utf8 desktop/build_desktop.py build
    py -X utf8 desktop/build_desktop.py test
    py -X utf8 desktop/build_desktop.py verify
    py -X utf8 desktop/build_desktop.py run
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

DESKTOP_DIR = Path(__file__).resolve().parent
ROOT = DESKTOP_DIR.parent
OUT_DIR = ROOT / "build" / "desktop"
JDK = Path(os.environ.get("WEAREARS_JDK", Path.home() / "android-tools" / "jdk-ms")) / "bin"
WINDIR = Path(os.environ.get("SystemRoot") or os.environ.get("WINDIR") or "")
CSC_CANDIDATES = [
    WINDIR / "Microsoft.NET" / "Framework64" / "v4.0.30319" / "csc.exe",
    WINDIR / "Microsoft.NET" / "Framework" / "v4.0.30319" / "csc.exe",
]
MAIN_CLASS = "com.hdz.wearcontrol.desktop.WearEarsDesktopApp"


def find_csc():
    env_csc = os.environ.get("WEAREARS_CSC")
    if env_csc and Path(env_csc).is_file():
        return Path(env_csc)
    for c in CSC_CANDIDATES:
        if c.is_file():
            return c
    which = shutil.which("csc.exe") or shutil.which("csc")
    if which:
        return Path(which)
    raise FileNotFoundError("Could not find Windows csc.exe (.NET Framework 4.x compiler)")


def run_cmd(args, cwd=ROOT, check=True):
    local = [
        arg.relative_to(cwd) if isinstance(arg, Path) and arg.is_relative_to(cwd) else arg
        for arg in args
    ]
    return subprocess.run([str(a) for a in local], cwd=cwd, check=check)


def collect_java_sources():
    files = []
    files.extend((ROOT / "core" / "src").rglob("*.java"))
    for drv in sorted((ROOT / "drivers").iterdir()):
        src = drv / "src"
        if src.is_dir():
            files.extend(src.rglob("*.java"))
    files.extend((DESKTOP_DIR / "src").rglob("*.java"))
    return sorted(files)


def build_bridge():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    csc = find_csc()
    cs_src = DESKTOP_DIR / "bt-bridge" / "WearEarsBtBridge.cs"
    exe_out = OUT_DIR / "WearEarsBtBridge.exe"
    print(f"[desktop] Compiling native Win32 Bluetooth bridge -> {exe_out.relative_to(ROOT)}")
    run_cmd([
        csc,
        "/nologo",
        "/platform:x64",
        "/optimize+",
        "/target:exe",
        f"/out:{exe_out.relative_to(ROOT)}",
        cs_src.relative_to(ROOT),
    ])
    return exe_out


def build_jar(bridge_exe=None):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    jar_out = OUT_DIR / "WearEars-Desktop.jar"
    java_files = collect_java_sources()
    print(f"[desktop] Compiling {len(java_files)} Java sources (core + 9 drivers + desktop) -> {jar_out.relative_to(ROOT)}")

    with tempfile.TemporaryDirectory(prefix="wearears-desktop-") as tmp_str:
        tmp = Path(tmp_str)
        classes = tmp / "classes"
        classes.mkdir()
        sources_list = tmp / "sources.txt"
        sources_list.write_text(
            "".join('"' + p.as_posix() + '"\n' for p in java_files),
            encoding="utf-8",
        )
        run_cmd([
            JDK / "javac.exe",
            "-J-Dfile.encoding=UTF-8",
            "-encoding", "UTF-8",
            "--release", "11",
            "-d", classes,
            "@" + str(sources_list),
        ])
        if bridge_exe is not None and Path(bridge_exe).is_file():
            native_dir = classes / "native"
            native_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(bridge_exe, native_dir / "WearEarsBtBridge.exe")
        manifest = tmp / "MANIFEST.MF"
        manifest.write_text(
            f"Manifest-Version: 1.0\nMain-Class: {MAIN_CLASS}\n",
            encoding="utf-8",
        )
        run_cmd([
            JDK / "jar.exe",
            "cfm",
            jar_out,
            manifest,
            "-C", classes,
            ".",
        ])
    return jar_out


def generate_swiss_icon(ico_path):
    """Generates a crisp 64x64 32-bit BGRA Windows ICO (Swiss Klein Blue #002FA7 + white geometric mark)."""
    import struct
    size = 64
    pixels = bytearray(size * size * 4)
    # BMP in ICO is bottom-up BGRA
    for y in range(size):
        fy = (size - 1 - y)  # top-down coordinate
        for x in range(size):
            # Default: Swiss Klein Blue #002FA7 (B=0xA7, G=0x2F, R=0x00, A=0xFF)
            b, g, r, a = 0xA7, 0x2F, 0x00, 0xFF
            # Outer crisp 2px obsidian border #0A0B0E
            if x < 2 or x >= size - 2 or fy < 2 or fy >= size - 2:
                b, g, r = 0x0E, 0x0B, 0x0A
            else:
                # Geometric headphone arc centered at (32, 34)
                dx = x - 31.5
                dy = fy - 34.0
                dist = (dx * dx + dy * dy) ** 0.5
                in_arc = fy <= 36 and 16.0 <= dist <= 22.0
                # Left & right sharp Swiss rectangular earcups
                in_left_cup = 10 <= x <= 19 and 31 <= fy <= 50
                in_right_cup = 44 <= x <= 53 and 31 <= fy <= 50
                # Small Swiss accent square bottom-center
                in_dot = 29 <= x <= 34 and 46 <= fy <= 51
                if in_arc or in_left_cup or in_right_cup or in_dot:
                    b, g, r = 0xFF, 0xFF, 0xFF
            idx = (y * size + x) * 4
            pixels[idx:idx + 4] = bytes((b, g, r, a))
    and_mask = bytes((size // 8) * size)
    bmp_header = struct.pack(
        "<IIIHHIIIIII",
        40, size, size * 2, 1, 32, 0, len(pixels) + len(and_mask), 0, 0, 0, 0
    )
    img_data = bmp_header + bytes(pixels) + and_mask
    ico_header = struct.pack("<HHH", 0, 1, 1)
    entry = struct.pack("<BBBBHHII", size, size, 0, 0, 1, 32, len(img_data), 6 + 16)
    ico_path.write_bytes(ico_header + entry + img_data)
    return ico_path


def build_standalone_single_exe(jar_path):
    """Builds ONE single self-contained native Win32 WearEars-Windows.exe (bundles minimal JRE + JAR + BT bridge).
    Zero Java required on target Windows 10/11 PCs; caches extracted runtime in %LOCALAPPDATA% for instant re-launches.
    """
    jpackage = JDK / "jpackage.exe"
    if not jpackage.is_file():
        raise FileNotFoundError(f"jpackage.exe not found at {jpackage}")
    iexpress = WINDIR / "System32" / "iexpress.exe"
    if not iexpress.is_file():
        raise FileNotFoundError("iexpress.exe not found")

    out_exe = OUT_DIR / "WearEars-Windows.exe"
    # Clean any old scattered WearEars\ folder so build/desktop stays clean
    old_scattered = OUT_DIR / "WearEars"
    if old_scattered.exists():
        shutil.rmtree(old_scattered, ignore_errors=True)
    old_ico = OUT_DIR / "WearEars.ico"
    if old_ico.exists():
        old_ico.unlink(missing_ok=True)

    with tempfile.TemporaryDirectory(prefix="wearears-oneexe-") as tmp_str:
        tmp = Path(tmp_str)
        ico_path = generate_swiss_icon(tmp / "WearEars.ico")
        inp = tmp / "input"
        inp.mkdir()
        shutil.copy2(jar_path, inp / "WearEars-Desktop.jar")
        out_stage = tmp / "jpkg-out"
        out_stage.mkdir()

        print("[desktop] Stage 1/3: Building minimal JRE + native launcher via jpackage...")
        run_cmd([
            jpackage,
            "--type", "app-image",
            "--name", "WearEars",
            "--app-version", "0.5.0",
            "--vendor", "WearEars",
            "--description", "WearEars Multi-Brand TWS Controller for Windows",
            "--input", inp,
            "--main-jar", "WearEars-Desktop.jar",
            "--main-class", MAIN_CLASS,
            "--icon", ico_path,
            "--add-modules", "java.base,java.desktop,java.prefs,java.logging",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--dest", out_stage,
        ])

        app_dir = out_stage / "WearEars"
        bundle_zip = tmp / "bundle.zip"
        print("[desktop] Stage 2/3: Compressing self-contained runtime bundle...")
        with zipfile.ZipFile(bundle_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
            for file_path in sorted(app_dir.rglob("*")):
                if file_path.is_file():
                    arcname = file_path.relative_to(out_stage).as_posix()
                    zf.write(file_path, arcname)

        bundle_hash = hashlib.sha256(bundle_zip.read_bytes()).hexdigest()[:12]
        zip_mb = bundle_zip.stat().st_size / (1024 * 1024)
        print(f"[desktop] Bundle compressed: {zip_mb:.1f} MB (hash={bundle_hash})")

        # Pure ASCII VBScript launcher:
        # Extracts once into %LOCALAPPDATA%\WearEars-Desktop\v-<hash>\ (Medium Integrity, pure ASCII)
        # using fast built-in Windows tar.exe (fallback PowerShell), then launches WearEars.exe silently.
        vbs_code = (
            'Option Explicit\r\n'
            'Dim fso, sh, srcDir, zipPath, baseDir, verDir, exePath, rc, tarExe, cmd\r\n'
            'Set fso = CreateObject("Scripting.FileSystemObject")\r\n'
            'Set sh = CreateObject("WScript.Shell")\r\n'
            'srcDir = fso.GetParentFolderName(WScript.ScriptFullName)\r\n'
            'zipPath = srcDir & "\\bundle.zip"\r\n'
            'baseDir = sh.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\\WearEars-Desktop"\r\n'
            'If Not fso.FolderExists(baseDir) Then fso.CreateFolder(baseDir)\r\n'
            f'verDir = baseDir & "\\v-{bundle_hash}"\r\n'
            'exePath = verDir & "\\WearEars\\WearEars.exe"\r\n'
            'If Not fso.FileExists(exePath) Then\r\n'
            '    If Not fso.FolderExists(verDir) Then fso.CreateFolder(verDir)\r\n'
            '    tarExe = sh.ExpandEnvironmentStrings("%SystemRoot%") & "\\System32\\tar.exe"\r\n'
            '    If fso.FileExists(tarExe) Then\r\n'
            '        cmd = """" & tarExe & """ -xf """ & zipPath & """ -C """ & verDir & """"\r\n'
            '        rc = sh.Run(cmd, 0, True)\r\n'
            '    Else\r\n'
            '        rc = 1\r\n'
            '    End If\r\n'
            '    If rc <> 0 Or Not fso.FileExists(exePath) Then\r\n'
            '        cmd = "powershell.exe -NoProfile -WindowStyle Hidden -Command ""Expand-Archive -LiteralPath \'" & zipPath & "\' -DestinationPath \'" & verDir & "\' -Force"""\r\n'
            '        rc = sh.Run(cmd, 0, True)\r\n'
            '    End If\r\n'
            'End If\r\n'
            'If fso.FileExists(exePath) Then\r\n'
            '    sh.Run """" & exePath & """", 1, False\r\n'
            'Else\r\n'
            '    MsgBox "WearEars failed to unpack runtime bundle into " & verDir, 16, "WearEars"\r\n'
            '    WScript.Quit 1\r\n'
            'End If\r\n'
        )
        (tmp / "launch.vbs").write_bytes(vbs_code.encode("ascii"))

        staged_exe = tmp / "WearEars-Windows.exe"
        sed_content = (
            "[Version]\r\n"
            "Class=IEXPRESS\r\n"
            "SEDVersion=3\r\n"
            "[Options]\r\n"
            "PackagePurpose=InstallApp\r\n"
            "ShowInstallProgramWindow=1\r\n"
            "HideExtractAnimation=1\r\n"
            "UseLongFileName=1\r\n"
            "InsideCompressed=0\r\n"
            "CAB_FixedSize=0\r\n"
            "CAB_ResvCodeSigning=0\r\n"
            "RebootMode=N\r\n"
            "InstallPrompt=%InstallPrompt%\r\n"
            "DisplayLicense=%DisplayLicense%\r\n"
            "FinishMessage=%FinishMessage%\r\n"
            f"TargetName={staged_exe}\r\n"
            "FriendlyName=%FriendlyName%\r\n"
            "AppLaunched=%AppLaunched%\r\n"
            "PostInstallCmd=%PostInstallCmd%\r\n"
            "AdminQuietInstCmd=%AdminQuietInstCmd%\r\n"
            "UserQuietInstCmd=%UserQuietInstCmd%\r\n"
            "SourceFiles=SourceFiles\r\n"
            "[Strings]\r\n"
            "InstallPrompt=\r\n"
            "DisplayLicense=\r\n"
            "FinishMessage=\r\n"
            "FriendlyName=WearEars Desktop\r\n"
            "AppLaunched=wscript.exe //B launch.vbs\r\n"
            "PostInstallCmd=<None>\r\n"
            "AdminQuietInstCmd=\r\n"
            "UserQuietInstCmd=\r\n"
            'FILE0="bundle.zip"\r\n'
            'FILE1="launch.vbs"\r\n'
            "[SourceFiles]\r\n"
            f"SourceFiles0={tmp}\r\n"
            "[SourceFiles0]\r\n"
            "%FILE0%=\r\n"
            "%FILE1%=\r\n"
        )
        sed_file = tmp / "wearears.sed"
        sed_file.write_bytes(sed_content.encode("ascii"))
        print("[desktop] Stage 3/3: Packaging ONE standalone self-contained WearEars-Windows.exe via iexpress...")
        res = subprocess.run([str(iexpress), "/N", "/Q", str(sed_file)], cwd=tmp, capture_output=True)
        if res.returncode != 0 or not staged_exe.is_file():
            raise RuntimeError(f"iexpress failed (exit={res.returncode}): {res.stderr.decode('gbk', 'replace')}")
        shutil.copy2(staged_exe, out_exe)
    exe_mb = out_exe.stat().st_size / (1024 * 1024)
    print(f"[desktop] Standalone single-file EXE ready -> {out_exe.relative_to(ROOT)} ({exe_mb:.1f} MB)")
    return out_exe


def run_tests(jar_path):
    test_files = sorted((DESKTOP_DIR / "tests").rglob("*.java"))
    print(f"[desktop] Compiling and running {len(test_files)} desktop test suite(s)...")
    with tempfile.TemporaryDirectory(prefix="wearears-desktop-test-") as tmp_str:
        tmp = Path(tmp_str)
        test_classes = tmp / "test-classes"
        test_classes.mkdir()
        sources_list = tmp / "test-sources.txt"
        sources_list.write_text(
            "".join('"' + p.as_posix() + '"\n' for p in test_files),
            encoding="utf-8",
        )
        run_cmd([
            JDK / "javac.exe",
            "-J-Dfile.encoding=UTF-8",
            "-encoding", "UTF-8",
            "--release", "11",
            "-cp", jar_path,
            "-d", test_classes,
            "@" + str(sources_list),
        ])
        cp = os.pathsep.join([str(test_classes), str(jar_path)])
        run_cmd([
            JDK / "java.exe",
            "-ea",
            "-Dfile.encoding=UTF-8",
            "-cp", cp,
            "com.hdz.wearcontrol.desktop.DesktopChecks",
        ])


def ensure_ascii_bridge(exe_path):
    s = str(exe_path.resolve())
    if all(ord(ch) <= 127 for ch in s):
        return exe_path
    stage_dir = Path(tempfile.gettempdir()) / "WearEars-BtBridge"
    stage_dir.mkdir(parents=True, exist_ok=True)
    st = exe_path.stat()
    staged = stage_dir / f"WearEarsBtBridge-{st.st_size}-{int(st.st_mtime_ns)}.exe"
    if not staged.is_file() or staged.stat().st_size != st.st_size:
        shutil.copy2(exe_path, staged)
    return staged


def verify_bridge_smoke(exe_path):
    runnable = ensure_ascii_bridge(exe_path)
    print(f"[desktop] Smoke-testing {exe_path.relative_to(ROOT)} check & list...")
    chk = subprocess.run([str(runnable), "check"], cwd=ROOT, capture_output=True)
    if chk.returncode != 0:
        raise RuntimeError(f"WearEarsBtBridge check failed (exit={chk.returncode}): {chk.stderr.decode('utf-8', 'replace')}")
    print("[desktop] Bridge check:", chk.stdout.decode("utf-8", "replace").strip())

    lst = subprocess.run([str(runnable), "list"], cwd=ROOT, capture_output=True)
    if lst.returncode != 0:
        raise RuntimeError(f"WearEarsBtBridge list failed (exit={lst.returncode}): {lst.stderr.decode('utf-8', 'replace')}")
    lines = [ln for ln in lst.stdout.decode("utf-8", "replace").splitlines() if ln.strip()]
    print(f"[desktop] Bridge list OK ({len(lines)} paired/connected Bluetooth device(s) found)")
    for ln in lines:
        print(f"[desktop]   BT -> {ln}")


def main():
    parser = argparse.ArgumentParser(description="Build and verify WearEars Desktop for Windows.")
    parser.add_argument("action", nargs="?", default="verify", choices=("build", "test", "verify", "exe", "run"))
    args = parser.parse_args()

    if args.action == "build":
        exe_path = build_bridge()
        build_jar(exe_path)
    elif args.action == "test":
        jar_path = OUT_DIR / "WearEars-Desktop.jar"
        if not jar_path.is_file():
            exe_path = build_bridge()
            jar_path = build_jar(exe_path)
        run_tests(jar_path)
    elif args.action == "exe":
        exe_path = build_bridge()
        jar_path = build_jar(exe_path)
        build_standalone_single_exe(jar_path)
    elif args.action == "verify":
        exe_path = build_bridge()
        jar_path = build_jar(exe_path)
        verify_bridge_smoke(exe_path)
        run_tests(jar_path)
        single_exe = build_standalone_single_exe(jar_path)
        print(f"[desktop] VERIFY OK: {single_exe.relative_to(ROOT)}")
    elif args.action == "run":
        exe_path = OUT_DIR / "WearEarsBtBridge.exe"
        jar_path = OUT_DIR / "WearEars-Desktop.jar"
        if not exe_path.is_file():
            exe_path = build_bridge()
        if not jar_path.is_file():
            jar_path = build_jar(exe_path)
        run_cmd([JDK / "java.exe", "-Dfile.encoding=UTF-8", "-jar", jar_path])


if __name__ == "__main__":
    main()
