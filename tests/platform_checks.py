"""Exercise the GATT adapter, persisted consent gate and standard Bluetooth client with framework fixtures."""
import os
from pathlib import Path
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from build import ANDROID, BUILD, JDK, PACKAGE, javac, run


def _parse_strings_xml(path):
    if not path.is_file():
        raise AssertionError(f"Missing string resource file: {path}")
    root = ET.parse(path).getroot()
    strings = {}
    arrays = {}
    for elem in root:
        if elem.tag not in ("string", "string-array"):
            continue
        name = elem.attrib.get("name")
        if not name:
            raise AssertionError(f"Unnamed <{elem.tag}> entry in {path}")
        translatable = elem.attrib.get("translatable", "true").lower() != "false"
        if elem.tag == "string":
            if name in strings:
                raise AssertionError(f"Duplicate <string name=\"{name}\"> in {path}")
            value = "".join(elem.itertext())
            if translatable and not value.strip():
                raise AssertionError(f"Empty translatable <string name=\"{name}\"> in {path}")
            strings[name] = value
        else:
            if name in arrays:
                raise AssertionError(f"Duplicate <string-array name=\"{name}\"> in {path}")
            items = ["".join(item.itertext()) for item in elem.findall("item")]
            if translatable and (not items or any(not item.strip() for item in items)):
                raise AssertionError(f"Empty item in translatable <string-array name=\"{name}\"> in {path}")
            arrays[name] = items
    return strings, arrays


def _assert_key_parity(kind, zh_map, en_map):
    missing_in_en = sorted(set(zh_map) - set(en_map))
    extra_in_en = sorted(set(en_map) - set(zh_map))
    if missing_in_en or extra_in_en:
        parts = []
        if missing_in_en:
            parts.append("missing in values-en: " + ", ".join(missing_in_en))
        if extra_in_en:
            parts.append("extra in values-en: " + ", ".join(extra_in_en))
        raise AssertionError(f"ZH/EN <{kind}> parity mismatch ({'; '.join(parts)})")


def verify_string_parity():
    zh_path = ROOT / "app/res/values/strings.xml"
    en_path = ROOT / "app/res/values-en/strings.xml"
    zh_strings, zh_arrays = _parse_strings_xml(zh_path)
    en_strings, en_arrays = _parse_strings_xml(en_path)
    _assert_key_parity("string", zh_strings, en_strings)
    _assert_key_parity("string-array", zh_arrays, en_arrays)
    for name, zh_items in zh_arrays.items():
        if len(zh_items) != len(en_arrays[name]):
            raise AssertionError(
                f"ZH/EN <string-array name=\"{name}\"> item count mismatch: "
                f"zh={len(zh_items)} vs en={len(en_arrays[name])}"
            )
    total = len(zh_strings) + len(zh_arrays)
    if total == 0:
        raise AssertionError("No strings parsed from strings.xml")
    print(f"PASS {total} ZH/EN string parity checks", flush=True)


def main():
    verify_string_parity()
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
                                                   "Controls", "SoundPreset",
                                                   "FeatureId", "OptionItem", "FeatureDescriptor",
                                                   "FeatureTransactions")))
                   for name in names]
        # Compile real code against SDK 34 before substituting the Android callbacks at runtime.
        javac(temp, production, sources, [ANDROID])
        javac(temp, fixtures, (ROOT / "tests/platform").rglob("*.java"), [production, ANDROID])
        cp = os.pathsep.join(map(str, (fixtures, production, ANDROID)))
        run(JDK / "java", "-ea", "-cp", cp, PACKAGE + ".PlatformChecks")
        run(JDK / "java", "-ea", "-cp", cp, PACKAGE + ".StandardRefreshChecks")


if __name__ == "__main__":
    main()
