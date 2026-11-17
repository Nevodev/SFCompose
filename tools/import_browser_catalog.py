"""Import Apple's browser metadata without copying the symbol outlines."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import plistlib
import re
from pathlib import Path


def has_draw_ties(value: object) -> bool:
    if isinstance(value, dict):
        stores = value.get("drawRailRoadTies", {})
        if isinstance(stores, dict) and any(
            isinstance(store, dict) and store.get("ties") for store in stores.values()
        ):
            return True
        return any(has_draw_ties(child) for child in value.values())
    return isinstance(value, list) and any(has_draw_ties(child) for child in value)


def build_catalog(
    names: list[str],
    order: list[str],
    category_records: list[dict],
    category_map: dict[str, list[str]],
    search: dict[str, list[str]],
    enhanced: dict[str, list[str]],
    aliases: dict[str, str],
    labels: dict[str, dict[str, str]],
    lookup: dict[str, dict],
    layer_sets: dict[str, dict],
) -> dict:
    canonical = set(names)
    if len(names) != len(canonical) or len(order) != len(set(order)):
        raise ValueError("Duplicate canonical name or native order entry")
    if set(order) != canonical:
        raise ValueError("Native symbol order must exactly cover the installed catalog")
    if set(lookup) != canonical:
        raise ValueError("Decoded application lookup must exactly cover the installed catalog")
    keys = [record["key"] for record in category_records]
    if len(keys) != len(set(keys)) or "all" not in keys:
        raise ValueError("Invalid category list")
    categories = []
    for record in category_records:
        key, icon = record["key"], record["icon"]
        if icon not in canonical:
            raise ValueError(f"Category icon has no installed symbol: {icon}")
        localized = {
            language: strings[f"sfsymbols-category-{key}"]
            for language, strings in labels.items()
        }
        categories.append({"key": key, "icon": icon, "labels": localized})

    def resolve(name: str) -> str | None:
        seen = set()
        while name not in canonical and name in aliases:
            if name in seen:
                raise ValueError(f"Alias cycle at {name}")
            seen.add(name)
            name = aliases[name]
        return name if name in canonical else None

    memberships = {name: {"all"} for name in order}
    terms = {name: set() for name in order}
    alias_lists = {name: [] for name in order}
    for name, memberships_source in category_map.items():
        target = resolve(name)
        if target is not None:
            unknown = set(memberships_source) - set(keys)
            if unknown:
                raise ValueError(f"Unknown categories: {unknown}")
            memberships[target].update(memberships_source)
    for source in (search, enhanced):
        for name, keywords in source.items():
            target = resolve(name)
            if target is not None:
                if not isinstance(keywords, list) or any(not isinstance(t, str) for t in keywords):
                    raise ValueError(f"Invalid keywords for {name}")
                terms[target].update(t.strip() for t in keywords if t.strip())
    opaque_aliases = 0
    for alias in aliases:
        target = resolve(alias)
        if target is None:
            raise ValueError(f"Alias has no installed target: {alias}")
        # Apple's alias table also contains opaque internal resource identifiers.
        if re.fullmatch(r"[0-9A-Fa-f]{32}", alias):
            opaque_aliases += 1
        elif alias not in canonical:
            alias_lists[target].append(alias)
    draw_count = 0
    for name in order:
        entry = lookup[name]
        identifier = entry.get("compositePua") or entry["pua"]
        if identifier == "COMPOSITE_ONLY":
            identifier = entry["pua"]
        if identifier not in layer_sets:
            raise ValueError(f"Missing original layer set for {name}")
        if has_draw_ties(layer_sets[identifier]):
            memberships[name].add("draw")
            draw_count += 1
    records = [
        {
            "name": name,
            "categories": [key for key in keys if key in memberships[name]],
            "aliases": sorted(alias_lists[name]),
            "keywords": sorted(terms[name]),
        }
        for name in order
    ]
    return {
        "formatVersion": 1,
        "categories": categories,
        "symbols": records,
        "coverage": {
            "symbols": len(records),
            "categories": {key: sum(key in record["categories"] for record in records) for key in keys},
            "withoutOriginalCategory": sum(memberships[name] <= {"all", "draw"} for name in order),
            "withKeywords": sum(bool(record["keywords"]) for record in records),
            "aliases": sum(len(record["aliases"]) for record in records),
            "opaqueAliasesExcluded": opaque_aliases,
            "drawFromOriginalTies": draw_count,
        },
    }


def import_catalog(system_metadata: Path, application: Path, decoded: Path, index: Path) -> dict:
    sources = {}

    def read(path: Path, kind: str):
        data = path.read_bytes()
        sources[kind] = {"file": path.name, "sha256": hashlib.sha256(data).hexdigest()}
        return json.loads(data) if path.suffix == ".json" else plistlib.loads(data)

    shared = application / "Contents/Frameworks/SFSymbolsShared.framework/Versions/A/Resources"
    catalog = build_catalog(
        names=read(index, "installedIndex")["symbols"],
        order=read(system_metadata / "symbol_order.plist", "nativeOrder"),
        category_records=read(system_metadata / "categories.plist", "categories"),
        category_map=read(system_metadata / "symbol_categories.plist", "categoryMembership"),
        search=read(system_metadata / "symbol_search.plist", "systemKeywords"),
        enhanced=read(application / "Contents/Resources/symbols_with_enhanced_keywords.plist", "enhancedKeywords"),
        aliases=read(system_metadata / "name_aliases.strings", "aliases"),
        labels={
            "en": read(shared / "en.lproj/CategoryTitles.strings", "categoryLabelsEnglish"),
            "zh-Hans": read(shared / "zh_CN.lproj/CategoryTitles.strings", "categoryLabelsSimplifiedChinese"),
        },
        lookup=read(decoded.parent / "canonical-symbol-lookup.json", "applicationLookup"),
        layer_sets=read(decoded / "syls.json", "authenticatedApplicationLayerSets"),
    )
    catalog["source"] = {
        "kind": "Apple-system-CoreGlyphs-and-SF-Symbols-application",
        "files": sources,
        "order": "system symbol_order.plist, unchanged",
        "keywordLanguages": ["en"],
        "categoryLabelLanguages": ["en", "zh-Hans"],
        "drawMembership": "decoded original selected layer set contains nonempty drawRailRoadTies",
        "missingCategoryPolicy": "retain in all; do not guess categories from names",
        "aliasPolicy": "resolve original aliases to installed names; exclude opaque 32-hex identifiers",
    }
    return catalog


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--system-metadata", type=Path, required=True)
    parser.add_argument("--application", type=Path, required=True)
    parser.add_argument("--decoded-tables", type=Path, required=True)
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    catalog = import_catalog(args.system_metadata, args.application, args.decoded_tables, args.index)
    data = (json.dumps(catalog, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
    if args.output.suffix == ".sfgz":
        data = gzip.compress(data, mtime=0)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(data)
    print(json.dumps({"output": str(args.output), "bytes": len(data), "coverage": catalog["coverage"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
