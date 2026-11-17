from pathlib import Path
import copy
import gzip
import json
import unittest

from import_browser_catalog import build_catalog, has_draw_ties


class BrowserCatalogTest(unittest.TestCase):
    def setUp(self):
        self.inputs = dict(
            names=["a", "b"], order=["b", "a"],
            category_records=[{"key": "all", "icon": "a"}, {"key": "draw", "icon": "a"}, {"key": "nature", "icon": "b"}],
            category_map={"old.a": ["nature"]}, search={"old.a": ["leaf", "tree"]},
            enhanced={"a": ["tree", "forest"]},
            aliases={"old.a": "a", "older.a": "old.a", "A" * 32: "b"},
            labels={"en": {"sfsymbols-category-all": "All", "sfsymbols-category-draw": "Draw", "sfsymbols-category-nature": "Nature"}, "zh-Hans": {"sfsymbols-category-all": "全部", "sfsymbols-category-draw": "绘制", "sfsymbols-category-nature": "自然"}},
            lookup={"a": {"pua": "1", "compositePua": "3"}, "b": {"pua": "2"}},
            layer_sets={"1": {}, "2": {}, "3": {"layerTree": [{"drawRailRoadTies": {"1": {"ties": [{"start": [0, 0]}]}}}]}},
        )

    def test_native_order_aliases_and_original_terms(self):
        result = build_catalog(**self.inputs)
        self.assertEqual([r["name"] for r in result["symbols"]], ["b", "a"])
        self.assertEqual([r["key"] for r in result["categories"]], ["all", "draw", "nature"])
        a = result["symbols"][1]
        self.assertEqual(a["categories"], ["all", "draw", "nature"])
        self.assertEqual(a["keywords"], ["forest", "leaf", "tree"])
        self.assertEqual(a["aliases"], ["old.a", "older.a"])
        self.assertEqual(result["coverage"]["opaqueAliasesExcluded"], 1)
        self.assertEqual(result["symbols"][0]["categories"], ["all"])

    def test_uninstalled_icons_and_incomplete_order_are_rejected(self):
        for mutate in [lambda v: v["category_records"][0].update(icon="missing"), lambda v: v.update(order=["a"])]:
            inputs = copy.deepcopy(self.inputs)
            mutate(inputs)
            with self.assertRaises(ValueError):
                build_catalog(**inputs)

    def test_draw_requires_original_nonempty_ties(self):
        self.assertFalse(has_draw_ties({"enableVariableDraw": True}))
        self.assertFalse(has_draw_ties({"drawRailRoadTies": {"1": {"ties": []}}}))
        self.assertTrue(has_draw_ties(self.inputs["layer_sets"]["3"]))

    def test_composite_only_identifier_uses_original_composition_key(self):
        self.inputs["lookup"]["a"] = {"pua": "U+1|badge", "compositePua": "COMPOSITE_ONLY"}
        self.inputs["layer_sets"]["U+1|badge"] = self.inputs["layer_sets"].pop("3")
        result = build_catalog(**self.inputs)
        self.assertIn("draw", result["symbols"][1]["categories"])

    def test_production_catalog_exactly_covers_installed_names(self):
        assets = Path(__file__).resolve().parents[1] / "app/src/main/assets/symbols"
        browser = json.loads(gzip.decompress((assets / "browser-catalog.json.sfgz").read_bytes()))
        installed = json.loads((assets / "index.json").read_text(encoding="utf-8"))
        self.assertEqual([r["name"] for r in browser["symbols"]], installed["symbols"])
        names = set(installed["symbols"])
        keys = {r["key"] for r in browser["categories"]}
        self.assertEqual(len(keys), len(browser["categories"]))
        for category in browser["categories"]:
            self.assertIn(category["icon"], names)
            self.assertEqual(set(category["labels"]), {"en", "zh-Hans"})
        aliases = []
        for symbol in browser["symbols"]:
            self.assertIn("all", symbol["categories"])
            self.assertLessEqual(set(symbol["categories"]), keys)
            self.assertFalse(set(symbol["aliases"]) & names)
            aliases.extend(symbol["aliases"])
        self.assertEqual(len(aliases), len(set(aliases)))
        self.assertEqual(browser["source"]["keywordLanguages"], ["en"])


if __name__ == "__main__":
    unittest.main()
