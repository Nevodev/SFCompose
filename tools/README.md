Production assets use the macOS system CoreGlyphs catalog. The older application
font importer below is retained for isolated legacy evidence and regression tests.

The system pipeline is:

1. `read_coreglyphs_car.py` reads the BOM rendition leaf chain and selectively
   exports BVG vectors plus CSI metrics, retaining complete rendition keys.
   Its named-key readers preserve the catalog's localization and appearance IDs.
2. `bvg.py` / `bvg_numbers.py` decode the vector document, styles and private
   attributes. `import_system_catalog.py` creates each native named selector as
   a separate mode layer, concatenates its paths, offsets compiled keyframes,
   and generates nine weights at three scales using the recovered CoreUI scalars.
3. `verify_system_catalog.py` checks every variant, sample, layer mapping,
   keyframe index and source identity, then writes `asset-install-manifest.json`.
4. `install_system_catalog.py` verifies that manifest, preserves gzip bytes under
   `.json.sfgz`, records the deployment mapping, and publishes the index last.
   It requires the previous assets to have been archived before installation.

Each tool has `--help` for its structured inputs. System sampling accepts the
original-machine-code interpolation helper or its separately verified exact port.
The input sample table and helper hashes are recorded in the generation inputs;
changing an input invalidates resume checkpoints. Source paths and raw styles are
preserved alongside native logical layers to keep the transformation auditable.

The current input is the user's local macOS 27.2.1 CoreGlyphs `Assets.car`;
SHA-256 is recorded in the generated index. System image extraction is selective
and does not require expanding the entire restore image. The Android renderer
never opens the restore image or invokes the offline analysis helpers.

The legacy importer reads the original SF Symbols application font and its annotations.
It does not download Apple software. Supply files extracted from your local
installation package; a complete macOS recovery image is unnecessary for these
bundled resources.

Use Python with `fontTools`. Decoding the original font's private tables also
requires `cryptography`:

```text
python tools/import_symbols.py --font <SFSymbolsFallback.otf> --framework <CoreGlyphsLib> --output <new-output-directory>
```

An existing decoded source directory can be supplied with `--decoded-dir`
instead of `--framework`. It must contain `syls.json`, `symp.csv` or
`symp-rows.json`, `syot.json`, `synthetic-scale-interpolation.json`, and the
decoder's `inventory.json`. Use `--names`
with explicit canonical names for a bounded import. The output directory must
be new; the tool refuses to reuse an existing index to prevent stale assets.
Copy the generated directory into `app/src/main/assets/symbols` when desired.

`decode_symbol_tables.py` reconstructs the source framework's key in memory,
authenticates each ChaCha20-Poly1305 payload, and reverses the source
obfuscation before parsing the tables. It embeds no key and writes no key file.
Its native addresses are specific to the analyzed framework build; a different
framework requires checking the native initializer before adapting addresses.
The source hashes and decoded table hashes are recorded in the generated index.

The generated catalog contains all 8,647 canonical source names. Each record
preserves the original layer tree, layer colors, erase operations, group
structure, Draw annotations, and other source fields. Outlines come from CFF2
glyphs at all three actual scales and the variation axis minimum, default, and
maximum. Named weight positions come from the font's own `fvar` instances.
Matching contour command topology is checked before any symbol is admitted.

For the 1,813 names with a numeric `Composite Symbol PUA`, the importer selects
that PUA's original glyph and matching `syls` layer tree together. These records
contain the `_badge`, `_slash`, base-symbol tags, Draw guides and constructed
shape groups that the older canonical-PUA drawings omit. The public `pua` field
retains the canonical name's PUA; `sourceGlyphPUA` records the selected native
composite drawing. Switching only the annotation tree would apply component
indices to the wrong outline, so source selection happens before extraction.
The native `SymbolFontReader.symbol(forSystemName:preferComposite:)` selector
at `0x2158a4` chooses this metadata key when requested and available; its worker
at `0x215c2c` uses the same key for the outlines and annotation dictionary.

The 83 synthetic composite names use their original auxiliary outlines. Native
`SymbolFontReader.outlinesCGLSymbol` at `0x2165cc` resolves their exact composite
identifier in `syot`, then calls `CoreGlyphsData` at `0x6b034`. That reader
constructs the source small scale from the Ultralight, Regular, and Black
outlines. `SimpleSymbol` at `0xb5308` uses the coefficient tables initialized at
`0xf3930` to interpolate the two outline deltas for each scale and named weight.
The importer extracts those tables from the supplied framework and records
their hash. It generates all nine named weight samples for these 83 symbols,
including the native uniform scale and cap-height-centered baseline offset.
The output therefore preserves the source's changes in apparent stroke weight
between scales instead of simply enlarging a single outline.

For some admitted names the source annotation references additional contour
indices that are absent from the font. Native `Glyph.contours(at:)` at arm64
`0x9ddc4` skips indices less than zero or greater than the available contour
range, then continues collecting valid contours. `LayerTree.Layer.resolvedPath`
at `0xd1b78` reaches that implementation through `0xf8344`. The importer keeps
the original tree unchanged and records these references in
`nativeSkippedComponents` and the index's `sourceDiagnostics`; the Android
reader must apply the same bounds filtering when collecting contour indices.

These counts describe data extraction, not Android rendering parity. Draw
guide points and attachment metadata still require the native path clipping,
timing, cap, and layer choreography behavior. Retaining the annotations does
not establish that these behaviors have been implemented or visually verified.

The native evidence establishing this extraction is `Crypton` key initialization
at arm64 address `0x10e350`, table decryption at `0x10e210`, ChaChaPoly opening
at `0x10e9a8`, and reversible obfuscation at `0x10ee6c`. Decompiler output is
recovered pseudocode, not Apple's original source. All three decoded tables
passed authenticated decryption; their exact identities are in the asset index.

Run the focused Python checks after import:

```text
python -m unittest discover -s tools -p test_import_symbols.py -v
```

The legacy catalog tests can read the preserved assets directly from a ZIP:
set `SF_SYMBOLS_LEGACY_ASSETS_ZIP` to an archive containing `index.json` and the
individual symbol JSON files at its root. Files are read on demand without
extracting the archive. If this variable is omitted and the production index
identifies the macOS system catalog, only `OriginalCatalogTest` is skipped with
an explicit source-mismatch reason; the independent `OutlineExtractionTest`
still runs. The legacy tests never compare system assets against App font data.
If a preserved snapshot already contains individual system renditions, those
records are explicitly reported as skipped subtests while the remaining legacy
records retain their original weight and outline-topology requirements.

For the complete tool suite, use a Python environment with `fontTools` (or add
its existing installation directory to `PYTHONPATH`) and configure these inputs:

- `SF_SYSTEM_BVG_DIR`: the retained `target-bvg` directory and native decode oracles.
- `SF_SYSTEM_CATALOG_DIR`: the extracted CoreGlyphs directory containing
  `Assets.car`, `system-vector-bvg/index.json`, and retained metadata oracles.
- `SF_SYMBOLS_LEGACY_ASSETS_ZIP`: the preserved legacy asset ZIP.
- `SF_SYMBOLS_FONT`: the original `SFSymbolsFallback.otf` matching that ZIP's index hash.

Then run `python -m unittest discover -s tools -p "test*.py" -v`. A missing
optional source causes a named skip; supplying the legacy ZIP and matching font
also runs the 108 named-weight/scale outline comparisons.

Set `SF_SYMBOLS_FONT` to the local original font path to additionally compare
Android's Float interpolation against fontTools' direct CFF2 evaluation at all
nine named weights and three scales for four representative drawings, including
the composite bell slash and folder badge. The check authenticates the source
font against the index hash and reports the largest coordinate/advance error.

The checks cover actual catalog name mapping and cutout annotations, synthetic
composite identities and native weight sampling, every
generated contour's command arity and finite coordinates, master topology,
component transforms, rejection of open font contours, and explicit unsupported
records. They do not substitute for comparison with Apple's runtime output.
