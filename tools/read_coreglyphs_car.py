"""Read vector CSI records through the catalog's BOM tree without exporting bitmaps."""

import argparse
import hashlib
import json
import struct
from pathlib import Path


class CoreGlyphsCatalog:
    def __init__(self, path):
        self.path = Path(path)
        self.data = self.path.read_bytes()
        if len(self.data) < 32:
            raise ValueError('Truncated BOM header')
        magic, version, _, index_offset, index_size, variables_offset, variables_size = struct.unpack_from('>8s6I', self.data)
        if magic != b'BOMStore' or version != 1:
            raise ValueError('Unsupported BOM catalog')
        table = self.region(index_offset, index_size)
        count = struct.unpack_from('>I', table)[0]
        if 4 + count * 8 > len(table):
            raise ValueError('Truncated BOM block table')
        self.blocks = [struct.unpack_from('>II', table, 4 + index * 8) for index in range(count)]
        variables = self.region(variables_offset, variables_size)
        if len(variables) < 4:
            raise ValueError('Truncated BOM variable table')
        cursor = 4
        self.variables = {}
        for _ in range(struct.unpack_from('>I', variables)[0]):
            if cursor + 5 > len(variables):
                raise ValueError('Truncated BOM variable entry')
            block, length = struct.unpack_from('>IB', variables, cursor)
            cursor += 5
            if cursor + length > len(variables):
                raise ValueError('Truncated BOM variable name')
            name = variables[cursor:cursor + length].decode('ascii')
            cursor += length
            self.variables[name] = block

    def region(self, offset, length):
        if offset < 0 or length < 0 or offset + length > len(self.data):
            raise ValueError('BOM region outside catalog')
        return self.data[offset:offset + length]

    def block(self, identifier):
        if not 0 <= identifier < len(self.blocks):
            raise ValueError('Invalid BOM block identifier')
        return self.region(*self.blocks[identifier])

    def tree_records(self, variable):
        header = self.block(self.variables[variable])
        if len(header) < 20 or header[:4] != b'tree':
            raise ValueError(f'Missing catalog B-tree: {variable}')
        root = struct.unpack_from('>I', header, 8)[0]
        expected = struct.unpack_from('>I', header, 16)[0]
        identifier = root
        visited = set()
        while True:
            if identifier in visited:
                raise ValueError('Cyclic rendition B-tree')
            visited.add(identifier)
            node = self.block(identifier)
            if len(node) < 12:
                raise ValueError('Truncated rendition node')
            leaf, entries, _, _ = struct.unpack_from('>HHII', node)
            if leaf:
                break
            if not entries or len(node) < 20:
                raise ValueError('Empty internal rendition node')
            identifier = struct.unpack_from('>I', node, 12)[0]
        visited.clear()
        previous = 0
        count = 0
        # The leaf chain includes the final split leaf even when the root's separator list omits it.
        while identifier:
            if identifier in visited:
                raise ValueError('Cyclic rendition leaf chain')
            visited.add(identifier)
            node = self.block(identifier)
            if len(node) < 12:
                raise ValueError('Truncated rendition leaf')
            leaf, entries, following, backward = struct.unpack_from('>HHII', node)
            if not leaf or backward != previous or 12 + entries * 8 > len(node):
                raise ValueError('Invalid rendition leaf chain')
            for index in range(entries):
                value, key = struct.unpack_from('>II', node, 12 + index * 8)
                count += 1
                yield self.block(key), value
            previous, identifier = identifier, following
        if count != expected:
            raise ValueError(f'Rendition count mismatch: {count} != {expected}')

    def records(self):
        for raw_key, value in self.tree_records('RENDITIONS'):
            if len(raw_key) % 2:
                raise ValueError('Invalid rendition key length')
            yield struct.unpack('<' + 'H' * (len(raw_key) // 2), raw_key), value

    def named_identifiers(self, variable):
        if variable not in self.variables:
            return {}
        result = {}
        for raw_key, value in self.tree_records(variable):
            name = raw_key.decode('utf-8')
            raw_value = self.block(value)
            if len(raw_value) != 2 or name in result:
                raise ValueError(f'Invalid named identifier in {variable}')
            result[name] = struct.unpack('<H', raw_value)[0]
        return result

    def metadata(self):
        return {'localizations': self.named_identifiers('LOCALIZATIONKEYS'),
                'appearances': self.named_identifiers('APPEARANCEKEYS')}

    def vectors(self, names=None):
        for key, identifier in self.records():
            raw = self.block(identifier)
            if len(raw) < 184 or raw[:8] != b'ISTC\x01\x00\x00\x00' or raw[24:28] != b' GVS':
                continue
            name = raw[40:168].split(b'\0', 1)[0].decode('utf-8')
            if names is not None and name.removesuffix('.svg') not in names:
                continue
            metadata_size, blocks, _, data_size = struct.unpack_from('<IIII', raw, 168)
            if blocks != 1 or 184 + metadata_size + data_size != len(raw):
                raise ValueError(f'Unsupported vector CSI layout: {name}')
            metadata = []
            cursor = 184
            while cursor < 184 + metadata_size:
                tag, size = struct.unpack_from('<II', raw, cursor)
                cursor += 8
                if cursor + size > 184 + metadata_size:
                    raise ValueError(f'Truncated CSI metadata: {name}')
                metadata.append({'id': tag, 'dataHex': raw[cursor:cursor + size].hex()})
                cursor += size
            encoded = raw[184 + metadata_size:]
            if len(encoded) < 12 or struct.unpack_from('<I', encoded, 8)[0] != len(encoded) - 12:
                raise ValueError(f'Invalid vector payload length: {name}')
            payload = encoded[12:]
            if payload[:4] != b'BVG ':
                raise ValueError(f'Unsupported vector payload: {name}')
            yield {
                'name': name.removesuffix('.svg'), 'key': list(key),
                'csiSha256': hashlib.sha256(raw).hexdigest(),
                'csiOffset': self.blocks[identifier][0], 'csiBytes': len(raw),
                'metadata': metadata, 'payload': payload,
            }


def extract(source, destination, names=None):
    if destination.exists():
        raise ValueError('Choose a new output directory')
    catalog = CoreGlyphsCatalog(source)
    destination.mkdir(parents=True)
    records = []
    for record in catalog.vectors(names):
        payload = record.pop('payload')
        key_hash = hashlib.sha256(struct.pack('<' + 'H' * len(record['key']), *record['key'])).hexdigest()[:16]
        filename = f"{record['name']}.{key_hash}.bvg"
        if '/' in filename or '\\' in filename:
            raise ValueError('Unsafe rendition name')
        (destination / filename).write_bytes(payload)
        record['file'] = filename
        record['sha256'] = hashlib.sha256(payload).hexdigest()
        records.append(record)
    result = {'sourceSha256': hashlib.sha256(catalog.data).hexdigest(), 'catalogMetadata': catalog.metadata(), 'records': records}
    (destination / 'index.json').write_text(json.dumps(result, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--names', nargs='*')
    args = parser.parse_args()
    index = extract(args.catalog, args.output, set(args.names) if args.names else None)
    print(f"Extracted {len(index['records'])} system vector records")
