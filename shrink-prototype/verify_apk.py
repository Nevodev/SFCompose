from pathlib import Path
import argparse
import gzip
import hashlib
import json
import re
import zipfile
import xml.etree.ElementTree as ET
from generate_combinations import SYMBOLS

ROOT = Path(__file__).resolve().parent.parent
PROTOTYPE = ROOT / "shrink-prototype"
RAW = PROTOTYPE / "catalog/src/main/res/raw"
ASSETS = ROOT / "app/src/main/assets/symbols"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path, help="Release APK built from the prototype consumer")
    parser.add_argument("scenario", choices=("ltr", "rtl", "both", "single", "full", "staticWeight"))
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    entries = json.loads((PROTOTYPE / "combinations.json").read_text(encoding="utf-8"))
    index = json.loads((ASSETS / "index.json").read_text(encoding="utf-8"))
    expected_matrix = {(name, weight, scale) for _, name, _, weights, scales in SYMBOLS
                       for weight in (weights or index['weights'].keys()) for scale in scales}
    if {(e['name'], e['weight'], e['scale']) for e in entries} != expected_matrix or len(entries) != len(expected_matrix):
        raise SystemExit('Generated combinations do not match the sparse fixture')
    source_text = (PROTOTYPE / 'catalog/src/main/java/com/nevoit/shrinkprototype/catalog/SymbolCombinations.kt').read_text(encoding='utf-8')
    accessors = set(re.findall(r'^    val (\w+) get\(\) = SymbolRef\(', source_text, re.MULTILINE))
    if accessors != {e['accessor'] for e in entries}:
        raise SystemExit('Generated accessors do not match the sparse fixture')
    combo_files = {p.stem for p in RAW.glob('combo_*.sfgz')}
    arrays = {node.attrib['name'] for node in ET.parse(PROTOTYPE / 'catalog/src/main/res/values/combinations.xml').getroot()}
    if combo_files != {e['resource'] for e in entries} or arrays != combo_files | {'full_wifi', 'family_wifi_regular'}:
        raise SystemExit('Generated resources do not match the sparse fixture')
    hashes = {digest(RAW / f'{entry["resource"]}.sfgz'): entry for entry in entries}
    if len(hashes) != len(entries):
        raise SystemExit("Distinct combinations must have distinct resource contents")
    closures = {entry['closure']: entry['name'] for entry in entries}
    closure_hashes = {digest(RAW / f'{closure}.sfgz'): (closure, name) for closure, name in closures.items()}
    found = []
    found_closures = []
    with zipfile.ZipFile(args.apk) as apk:
        for item in apk.infolist():
            if not item.filename.endswith(".sfgz"):
                continue
            content = apk.read(item)
            content_hash = hashlib.sha256(content).hexdigest()
            closure = closure_hashes.get(content_hash)
            if closure is not None:
                decoded = json.loads(gzip.decompress(content).decode('utf-8'))
                with gzip.open(ASSETS / f'{closure[1]}.json.sfgz', 'rt', encoding='utf-8') as stream:
                    source = json.load(stream)
                if decoded != {'kind': 'symbol-metadata', 'name': closure[1], 'layerTree': source['layerTree']}:
                    raise SystemExit(f'Incorrect closure: {item.filename}')
                found_closures.append((closure[0], item.filename, item.file_size, closure[1]))
                continue
            entry = hashes.get(content_hash)
            if entry is None:
                raise SystemExit(f"Unexpected symbol resource in APK: {item.filename}")
            decoded = json.loads(gzip.decompress(content).decode("utf-8"))
            if set(decoded) != {"name", "weight", "scale", "filled", "upm", "capHeight", "master"}:
                raise SystemExit(f"Resource contains unexpected masters: {item.filename}")
            for key in ("name", "weight", "scale", "filled"):
                if decoded[key] != entry[key]:
                    raise SystemExit(f"Combination mismatch in {item.filename}: {key}")
            with gzip.open(ASSETS / f'{entry["name"]}.json.sfgz', 'rt', encoding='utf-8') as stream:
                source = json.load(stream)
            masters = [master for master in source['scales'][entry['scale'].lower()]['masters']
                       if master['weight'] == index['weights'][entry['weight']]]
            if len(masters) != 1 or decoded['master'] != masters[0]:
                raise SystemExit(f"Incorrect master in {item.filename}")
            found.append((entry["accessor"], item.filename, item.file_size))
        assets = [item.filename for item in apk.infolist() if item.filename.startswith("assets/symbols/")]
    expected = {"WifiRegularSmall"}
    if args.scenario in ("ltr", "rtl", "both"):
        expected.add("PersonFillBoldLarge")
    if args.scenario == "full":
        expected = {entry['accessor'] for entry in entries if entry['name'] == 'wifi'}
    if args.scenario == "staticWeight":
        expected = {entry['accessor'] for entry in entries if entry['name'] == 'wifi' and entry['weight'] == 'Regular'}
    if args.scenario in ("ltr", "both"):
        expected.add("ZeroCircleRegularSmall")
    if args.scenario in ("rtl", "both"):
        expected.add("ZeroCircleArRegularSmall")
    expected_closures = {entry['closure'] for entry in entries if entry['accessor'] in expected}
    if ({entry[0] for entry in found} != expected or len(found) != len(expected) or assets or
            {entry[0] for entry in found_closures} != expected_closures or len(found_closures) != len(expected_closures)):
        raise SystemExit(f"Incorrect symbol resources in APK: {found}; closures: {found_closures}; assets/symbols: {assets}")
    print(f"Verified {args.apk}:")
    for accessor, path, size in sorted(found):
        print(f"  {path}: {size} bytes, {accessor}")
    for closure, path, size, name in sorted(found_closures):
        print(f"  {path}: {size} bytes, {name} shared layer metadata ({closure})")
    print(f"  Removed {len(entries) - len(found)} unreferenced combinations")
    print("  assets/symbols: empty")
    if args.report:
        selected = {e['accessor']: e for e in entries}
        symbols = {}
        for accessor, path, size in found:
            entry = selected[accessor]
            symbol = symbols.setdefault(entry['name'], {'weights': set(), 'scales': set(), 'variants': set(), 'files': [], 'bytes': 0})
            symbol['weights'].add(entry['weight'])
            symbol['scales'].add(entry['scale'])
            symbol['variants'].add('filled' if entry['filled'] else 'default')
            symbol['files'].append({'path': path, 'bytes': size, 'weight': entry['weight'], 'scale': entry['scale']})
            symbol['bytes'] += size
        for _, path, size, name in found_closures:
            symbols[name]['files'].append({'path': path, 'bytes': size, 'kind': 'layer metadata'})
            symbols[name]['bytes'] += size
        for symbol in symbols.values():
            for key in ('weights', 'scales', 'variants'):
                symbol[key] = sorted(symbol[key])
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({'apk': str(args.apk), 'symbols': symbols}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == "__main__":
    main()
