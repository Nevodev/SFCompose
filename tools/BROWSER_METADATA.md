# Browser metadata

`import_browser_catalog.py` creates `symbols/browser-catalog.json.sfgz` from local
Apple artifacts. It reads only metadata, not the macOS image or font outlines.
The `.sfgz` suffix preserves the gzip stream through Android asset packaging.

The JSON schema is version 1:

- `categories`: ordered `{key, icon, labels: {en, zh-Hans}}` records.
- `symbols`: ordered `{name, categories, aliases, keywords}` records. Each list
  follows the unchanged system `symbol_order.plist`; names exactly cover the
  installed rendering catalog. Category memberships follow category order.
- `coverage`: symbol, membership, alias and keyword counts.
- `source`: input SHA-256 hashes and the import policies.

Categories, memberships, search terms, aliases and symbol order come from the
system CoreGlyphs bundle. The SF Symbols application supplies its original
English enhanced keywords and English / Simplified Chinese category labels.
Per-symbol keyword data is English; no Chinese per-symbol keyword file was
found in the retained application package inventory. A consumer can additionally
match localized category labels. Search ranking and tokenization belong to the
Android consumer; this importer does not claim to recover Apple's ranking.

`all` contains every installed name. Ordinary memberships and `whatsnew` are
retained exactly from Apple's category map. Names absent from that map remain
in `all`; categories are not inferred from name fragments. `draw` is a dynamic
category absent from that map. Its membership is derived from the authenticated
application `syls` layer data: the selected canonical/composite layer set has
nonempty `drawRailRoadTies` in at least one master. This is original data
availability, not a claim to execute the complete macOS sidebar filter.

Old names resolve through the original alias table to installed canonical names.
Their categories and keywords attach to that canonical record; aliases do not
create duplicate grid entries. Opaque 32-hex internal aliases are excluded from
user search. No category icon is accepted unless its renderer asset exists.

To reproduce, run the following argument vector through a temporary Python
script using `subprocess.run(..., shell=False, check=True)`:

```text
python tools/import_browser_catalog.py
  --system-metadata D:/MACOS/analysis/system/resources/CoreGlyphs/browser-metadata
  --application "D:/MACOS/analysis/symbols-package/selected/Applications/SF Symbols.app"
  --decoded-tables D:/MACOS/analysis/symbols-package/decoded-tables
  --index app/src/main/assets/symbols/index.json
  --output app/src/main/assets/symbols/browser-catalog.json.sfgz
```

The system metadata directory contains only `categories.plist`,
`symbol_categories.plist`, `symbol_search.plist`, `symbol_order.plist` and
`name_aliases.strings`, selectively read from the local authenticated image.
The decoded directory and its sibling `canonical-symbol-lookup.json` are the
existing authenticated SF Symbols application metadata extraction.

Focused validation: `python -m unittest discover -s tools -p test_import_browser_catalog.py`.
