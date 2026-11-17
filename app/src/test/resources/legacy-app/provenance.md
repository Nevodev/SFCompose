The compressed assets preserve the SF Symbols.app authoring/font export.
`airplay.audio` and `cellularbars` are the inputs used to generate
draw-native-actual-plans-dense.json. `wind.circle.fill`, `kashida.arabic`,
`square.and.pencil`, and `square.and.arrow.up.fill` preserve authoring-rejection
and remove-unannotated-subpaths regressions. They are test-only inputs
for the original app kernel regression and are loaded through a separate
SymbolAssetStore. They do not participate in the production system catalog.

System-source validation uses the BVG / RenderBox fixtures and
SymbolSystemDrawGeometryTest instead. The app fixture verifies the older
conversion/kernel path and must not be presented as system renderer parity.
