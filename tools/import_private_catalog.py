"""Convert the system CoreGlyphsPrivate Assets.car into a separate SFCompose asset root."""

import argparse
import json
from pathlib import Path
import subprocess
import sys

from read_coreglyphs_car import extract


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--car', type=Path, required=True)
    parser.add_argument('--samples', type=Path, required=True)
    parser.add_argument('--native-clip-module', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--base-index', type=Path,
                        default=Path(__file__).resolve().parents[1] / 'app/src/main/assets/symbols/index.json')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error('Choose a new output directory')
    tools = Path(__file__).resolve().parent
    vectors = output / 'source-vectors'
    symbols = output / 'symbols'
    manifest = extract(args.car, vectors)
    names = {row['name'] for row in manifest['records']}
    public = set(json.loads(args.base_index.read_text(encoding='utf-8'))['symbols'])
    if not names or names & public:
        raise ValueError('The input must be the private CAR paired with this public index')
    subprocess.run([
        sys.executable, '-B', str(tools / 'import_system_catalog.py'), '--manifest', str(vectors / 'index.json'),
        '--base-index', str(args.base_index), '--samples', str(args.samples),
        '--native-clip-module', str(args.native_clip_module), '--output', str(symbols),
    ], shell=False, check=True)
    imported = json.loads((symbols / 'index.json').read_text(encoding='utf-8'))
    if set(imported['symbols']) != names:
        raise ValueError(f'Incomplete private import. See {symbols / "system-import-report.json"}')
    for file in symbols.rglob('*.json.gz'):
        file.rename(file.with_suffix('.sfgz'))
    subprocess.run([
        sys.executable, '-B', str(tools / 'import_resource_selection.py'), '--assets', str(symbols),
        '--car', str(args.car), '--audit', str(output / 'selection-audit.json'),
    ], shell=False, check=True)


if __name__ == '__main__':
    main()
