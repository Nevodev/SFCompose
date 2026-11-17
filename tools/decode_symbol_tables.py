"""Decode tables from caller-supplied original SF Symbols 27 artifacts.

No key is embedded, printed, or persisted. The key is reconstructed in memory
from the source framework's Swift Character array and obfuscation constants.
Addresses are specific to the supplied SF Symbols 27.2 build150 arm64 slice.
Authentication of all three ChaCha20Poly1305 table payloads verifies alignment.
"""
from pathlib import Path
import argparse
import base64
import csv
import hashlib
import io
import json
import struct

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305


def arm64_slice(path: Path) -> bytes:
    source = path.read_bytes()
    if source[:4] == b'\xcf\xfa\xed\xfe':
        if struct.unpack_from('<I', source, 4)[0] != 0x100000C:
            raise ValueError('Expected arm64 Mach-O')
        return source
    if source[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Expected Mach-O universal binary')
    count = struct.unpack_from('>I', source, 4)[0]
    for index in range(count):
        cpu, subtype, offset, size, alignment = struct.unpack_from('>IIIII', source, 8 + index * 20)
        if cpu == 0x100000C:
            return source[offset:offset + size]
    raise ValueError('No arm64 slice')


def address_to_offset(binary: bytes, address: int) -> int:
    count = struct.unpack_from('<I', binary, 16)[0]
    cursor = 32
    for _ in range(count):
        command, size = struct.unpack_from('<II', binary, cursor)
        if command == 0x19:
            start, memory_size, file_offset, file_size = struct.unpack_from('<QQQQ', binary, cursor + 24)
            if start <= address < start + file_size:
                return file_offset + address - start
        cursor += size
    raise ValueError(f'Address not file-backed: {address:x}')


def decoder_for(binary: bytes):
    def get(address: int, size: int) -> bytes:
        offset = address_to_offset(binary, address)
        return binary[offset:offset + size]

    magic = get(0x152C18 + 32, 8)
    salt = get(0x152C40 + 32, 10)

    def deobfuscate(value: bytes) -> bytes:
        if len(value) <= 8:
            raise ValueError('Invalid obfuscated payload length')
        rotate = len(value) % 10
        header = bytes((value[index] - (salt[(rotate + index) % 10] ^ 0x80)) % 256 for index in range(8))
        if header != magic:
            raise ValueError('Obfuscation header mismatch; source build may differ')
        output = bytearray(len(value) - 8)
        output[0] = (value[8] - salt[rotate]) % 256
        for index in range(1, len(output)):
            output[index] = (value[index + 8] - salt[(output[index - 1] + index + rotate) % 10]) % 256
        return bytes(output)

    count = struct.unpack('<Q', get(0x152580 + 16, 8))[0]
    if count != 96:
        raise ValueError('Unexpected static Character array; source build may differ')
    encoded = []
    for index in range(count):
        low, high = struct.unpack('<QQ', get(0x152580 + 32 + index * 16, 16))
        if high != 0xE100000000000000 or low > 0x7F:
            raise ValueError('Unexpected Swift Character encoding')
        encoded.append(chr(low))
    key = bytes.fromhex(deobfuscate(base64.b64decode(''.join(encoded))).decode('ascii'))
    if len(key) != 32:
        raise ValueError('Unexpected derived key length')
    cipher = ChaCha20Poly1305(key)

    def decode(value: bytes) -> bytes:
        return deobfuscate(cipher.decrypt(value[:12], value[12:], None))

    return decode


def decode_tables(framework: Path, font: Path, output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    binary = arm64_slice(framework)
    decode = decoder_for(binary)
    font_data = font.read_bytes()
    inventory = {'framework_sha256': hashlib.sha256(framework.read_bytes()).hexdigest(), 'font_sha256': hashlib.sha256(font_data).hexdigest(), 'tables': []}
    count = struct.unpack_from('>H', font_data, 4)[0]
    for index in range(count):
        tag, checksum, offset, size = struct.unpack_from('>4sIII', font_data, 12 + index * 16)
        if tag not in [b'syls', b'symp', b'syot']:
            continue
        decoded = decode(font_data[offset:offset + size])
        name = tag.decode('ascii')
        record = {'tag': name, 'encrypted_size': size, 'decoded_size': len(decoded), 'decoded_sha256': hashlib.sha256(decoded).hexdigest(), 'authenticated': True}
        if name == 'symp':
            (output / 'symp.csv').write_bytes(decoded)
            rows = list(csv.DictReader(io.StringIO(decoded.decode('utf-8'))))
            (output / 'symp-rows.json').write_text(json.dumps(rows, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
            record['record_count'] = len(rows)
        else:
            value = json.loads(decoded.decode('utf-8'))
            (output / (name + '.json')).write_bytes(decoded)
            record['record_count'] = len(value['symbols']) if name == 'syot' else len(value)
        inventory['tables'].append(record)
    if len(inventory['tables']) != 3:
        raise ValueError('Expected all three private tables')
    synthetic_tables = []
    for scale, address in [(0.2, 0x150458), (0.5, 0x150550), (1.0, 0x150648), (1.276, 0x150740), (1.647, 0x150838), (2.0, 0x150930)]:
        offset = address_to_offset(binary, address)
        count = struct.unpack_from('<Q', binary, offset + 16)[0]
        if count != 9:
            raise ValueError('Unexpected native synthetic interpolation table')
        values = [struct.unpack_from('<ddd', binary, offset + 32 + index * 24) for index in range(count)]
        synthetic_tables.append({'scale': scale, 'values': values, 'sourceAddress': hex(address)})
    table_path = output / 'synthetic-scale-interpolation.json'
    table_path.write_text(json.dumps(synthetic_tables, separators=(',', ':')), encoding='utf-8')
    inventory['synthetic_scale_tables_sha256'] = hashlib.sha256(table_path.read_bytes()).hexdigest()
    (output / 'inventory.json').write_text(json.dumps(inventory, indent=2), encoding='utf-8')
    return inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--framework', type=Path, required=True)
    parser.add_argument('--font', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    inventory = decode_tables(args.framework, args.font, args.output)
    print(json.dumps(inventory, indent=2))


if __name__ == '__main__':
    main()
