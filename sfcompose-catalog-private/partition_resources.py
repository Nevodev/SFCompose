"""Split Android resource classes into several namespaces without changing the symbol API."""

import argparse
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET


def partition(source, target, count):
    arrays = ET.parse(source / 'res/values/sf_symbols.xml').getroot()
    resources = [ET.Element('resources') for _ in range(count)]
    raw_names = [set() for _ in range(count)]
    routing = {}
    for array in arrays:
        name = array.attrib['name']
        key = name.removeprefix('sf_private_').split('_', 1)[0]
        shard = int(key, 16) % count
        routing[name] = shard
        resources[shard].append(array)
        raw_names[shard].update(item.text.removeprefix('@raw/') for item in array)
    files = {path.stem: path for path in (source / 'res/raw').iterdir()}
    for shard, xml in enumerate(resources):
        folder = target / f'part{shard}/res'
        raw = folder / 'raw'
        values = folder / 'values'
        raw.mkdir(parents=True, exist_ok=True)
        values.mkdir(parents=True, exist_ok=True)
        for previous in raw.iterdir():
            if previous.stem not in raw_names[shard]:
                previous.unlink()
        for name in sorted(raw_names[shard]):
            file = files[name]
            shutil.copyfile(file, raw / file.name)
        ET.indent(xml)
        ET.ElementTree(xml).write(values / 'sf_symbols.xml', encoding='utf-8', xml_declaration=True)

    relative = Path('kotlin/com/nevoit/sfcompose/symbols/PrivateSFSymbols.kt')
    kotlin = (source / relative).read_text(encoding='utf-8')
    imports = '\n'.join(f'import com.nevoit.sfcompose.catalog.privateglyphs.part{i}.R.array as ResourceArray_{i}'
                        for i in range(count))
    kotlin = kotlin.replace('import com.nevoit.sfcompose.catalog.privateglyphs.R', imports)
    kotlin = re.sub(r'\bR\.array\.(sf_private_[a-z0-9_]+)',
                    lambda match: f'ResourceArray_{routing[match[1]]}.{match[1]}', kotlin)
    destination = target / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(kotlin, encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('target', type=Path)
    parser.add_argument('count', type=int)
    args = parser.parse_args()
    partition(args.source, args.target, args.count)
