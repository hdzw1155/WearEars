"""Exercise the GATT adapter, persisted consent gate and standard Bluetooth client with framework fixtures."""
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from build import ANDROID, BUILD, JDK, PACKAGE, javac, run


def main():
    BUILD.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="platform-", dir=BUILD) as folder:
        temp = Path(folder)
        production, fixtures = temp / "production", temp / "fixtures"
        production.mkdir()
        fixtures.mkdir()
        package_path = Path(*PACKAGE.split("."))
        sources = [ROOT / module / "src" / package_path / (name + ".java")
                   for module, names in (("app", ("GattLink", "TileLedger", "AppPrefs", "StandardClient",
                                                  "TwsClient", "AudioConnections")),
                                         ("core", ("GattPump", "Consent", "TileCommand", "StyleChoice",
                                                   "Controls", "SoundPreset")))
                   for name in names]
        # Compile real code against SDK 34 before substituting the Android callbacks at runtime.
        javac(temp, production, sources, [ANDROID])
        javac(temp, fixtures, (ROOT / "tests/platform").rglob("*.java"), [production, ANDROID])
        cp = os.pathsep.join(map(str, (fixtures, production, ANDROID)))
        run(JDK / "java", "-ea", "-cp", cp, PACKAGE + ".PlatformChecks")
        run(JDK / "java", "-ea", "-cp", cp, PACKAGE + ".StandardRefreshChecks")


if __name__ == "__main__":
    main()
