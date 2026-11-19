# Android resource shrinking prototype

The generator constructs a sparse test catalog using real masters from the
repository's symbol data. The sparsity is a fixture constraint, not a claim
that the source symbols themselves lack the excluded masters:

* `wifi`: nine weights, Small / Medium / Large (27 combinations).
* `person.fill`: Regular / Semibold / Bold, all three scales (9).
* `0.circle` and `0.circle.ar`: Regular / Bold, Small / Medium (4 each).

Every generated accessor references an array containing one combination's
vector master and that symbol variant's shared layer metadata. The generator
fails if a requested fixture combination has no matching source master.

The consumer has three independent release flavors: `ltr` references `0.circle`,
`rtl` references `0.circle.ar`, and `both` references both. Each also references
`Wifi` (Regular / Small) and `PersonFillBoldLarge`. This tests explicit variant
references, not automatic Android locale selection. Builds are unsigned.

Two additional flavors compare the `wifi` Regular / Small combination (`single`)
with a `full` family entry that directly references all 27 wifi masters and the
same shared metadata. Filled, localized and directional families remain separate.
The family entry is a proof of concept for the 27-combination policy; other
fixture symbols still use the sparse experiment and are not full-family entries.

The `staticWeight` flavor references a separate wifi Regular entry containing
the three optical sizes. It does not implement automatic locale/direction
closures or size selection. Its post-build verifier can also write an inventory
of the actual APK resources:

```text
gradlew :shrink-prototype:consumer:assembleStaticWeightRelease
python shrink-prototype/verify_apk.py shrink-prototype/consumer/build/outputs/apk/staticWeight/release/consumer-staticWeight-release-unsigned.apk staticWeight --report shrink-prototype/consumer/build/reports/symbols/staticWeightRelease.json
```

That early single-symbol report is produced by the verifier. The two closure
variants below generate reports automatically.

`expandedClosure` and `masterClosure` use the app renderer and its locale and
direction selector. The generated entry binds one named weight and directly
references an Android resource array. The array includes all three scales,
the locale renditions of that name, its actual RTL counterpart when present,
and the metadata and images required by that closure. A mirrorable symbol
can reuse its original outline. `.fill` remains a separate symbol name.

The renderer receives the scale when the instance is loaded and the requested
em height when it draws, for example `store.load(name, scale = SymbolScale.Medium)`
and `SFSymbol(instance, null, pointSize = 20.dp)`. Medium remains the default
scale. A static resource closure rejects other weights.

```text
gradlew :shrink-prototype:consumer:assembleExpandedClosureRelease :shrink-prototype:consumer:assembleMasterClosureRelease
gradlew :shrink-prototype:consumer:testExpandedClosureDebugUnitTest
```

Set `-PsfcomposePython=<python executable>` if `python` is unavailable on PATH.
Both assemble tasks verify the packaged data and write APK inventories under
`consumer/build/reports/symbols/`. The two encodings retain the same Draw
keyframes, attachment descriptors, anchor points, metrics, and variants.
The master encoding reconstructs only contours from three outline masters and
the sample's coefficients. `verify_master_reconstruction.py` separately checks
complete offline reconstruction against deployed original samples using the
local source BVG directory and CoreUI interpolation inputs.

`compiledDraw` and `liveDraw` use the same compact outline data, symbols,
weights, and resource closures. The compiled build reads the sampled Draw
keyframes and anchors. The live build computes them on the first load of each
scale and reuses the resulting cached `SymbolInstance`. Attachment layer
descriptors are retained as source metadata in both builds. The seed file
contains the original per-layer contours and keyframes for the three source
masters and can be recreated with `generate_draw_seeds.py` from the local BVGs.

```text
gradlew :shrink-prototype:consumer:assembleCompiledDrawRelease :shrink-prototype:consumer:assembleLiveDrawRelease
```

These two variants have separate application IDs and use the debug key for
local installation. Launching their `PackedActivity` with a `drawBenchmarkRun`
string extra runs the same eight symbols and three scales through `load()`,
using a fixed Arabic RTL configuration. It logs per-symbol and total load time
in nanoseconds under `SFDrawLoad`. It measures process-local cold symbol loads,
including resource decoding and render preparation. Activity startup, frame
rendering and operating system file cache eviction are outside that measurement.

`apiRegular`, `apiBold`, `apiFill` and `apiDynamic` consume the `:sfcompose`
library through its public generated entries. Their release reports enforce the
expected names, weights, scales and variant closures in the packaged APKs.

```text
python shrink-prototype/generate_combinations.py
gradlew :shrink-prototype:consumer:assembleRelease
python shrink-prototype/verify_apk.py shrink-prototype/consumer/build/outputs/apk/ltr/release/consumer-ltr-release-unsigned.apk ltr
python shrink-prototype/verify_apk.py shrink-prototype/consumer/build/outputs/apk/rtl/release/consumer-rtl-release-unsigned.apk rtl
python shrink-prototype/verify_apk.py shrink-prototype/consumer/build/outputs/apk/both/release/consumer-both-release-unsigned.apk both
python shrink-prototype/verify_apk.py shrink-prototype/consumer/build/outputs/apk/single/release/consumer-single-release-unsigned.apk single
python shrink-prototype/verify_apk.py shrink-prototype/consumer/build/outputs/apk/full/release/consumer-full-release-unsigned.apk full
```

The verifier checks the sparse manifest, all packaged `.sfgz` files, source
master and layer metadata, expected transitive resources, and the absence of
`assets/symbols/`. Resource names may be rewritten by the optimizer, so files
are matched by SHA-256. The production renderer and its full dependency closure
are outside this prototype.

The full family entry permits runtime weight and scale selection among its 27
retained combinations; the consumer currently only checks their payloads. For
combination-level shrinking, the production design needs statically referenced
accessors or generated code, and runtime selection must explicitly retain all
combinations it can access. The
`.sfgz` files in this experiment are gzip-compressed JSON vector data, not
density-specific bitmaps; optical scale is not Android screen density.
