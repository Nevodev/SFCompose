# SF Compose

Android Compose renderer using macOS 27.2.1 system CoreGlyphs vector resources
and behavior recovered from CoreUI, Symbols, and RenderBox. The renderer is
in `app/src/main/java/com/nevoit/sfcompose/symbols`; the application is an
interactive catalog and animation inspector.

The browser uses the original system symbol order and 32 category definitions,
with Apple's aliases and English search keywords. Chinese category labels are
also searchable. Android search prioritizes exact names and prefixes, then
matches all query terms; this is not a claim to reproduce Apple's search ranking.
The original category map leaves some names uncategorized; they remain in All.
Metadata provenance and rebuilding are documented in [tools/BROWSER_METADATA.md](tools/BROWSER_METADATA.md).

Tap a symbol to open its animation inspector. Choose “选择替换”, select source A
and target B, then tap Replace to preview them. The replacement page supports
swapping A/B, direction, Magic Replace, layer mode, rendering mode, weight,
optical size, replay, looping and timeline scrubbing. Returning to the browser
keeps the search, category, selection and grid position. Thumbnails load only
for visible grid cells; searching does not decode the rendering catalog.

```kotlin
val store = remember(context.assets) { SymbolAssetStore(context.assets) }
var symbol by remember { mutableStateOf<SymbolInstance?>(null) }
LaunchedEffect(store) {
    symbol = store.load("wifi", SymbolWeight.Regular, SymbolScale.Medium)
}
symbol?.let {
    SFSymbol(
        symbol = it,
        contentDescription = "Wi-Fi",
        mode = SymbolRenderingMode.Palette,
        palette = listOf(Color.Blue, Color.Cyan),
        variableValue = { signalStrength },
    )
}
```

The caller supplies animation time in seconds using `elapsedSeconds`, so a
frame clock, playback speed, seeking, interruption, and repeat policy can be
controlled without replacing the renderer's timing curves. `drawEffect`
selects timed Draw On/Off. `drawProgress` provides raw geometry progress;
`drawReversed` selects the original reverse geometry and ordering option.
`drawReversesMotionGroups` independently reverses the layer choreography.
`variableDrawValue` selects the source's separate Variable Draw behavior.
`variableAnimation` controls cumulative/iterative Variable Color, bouncing,
inactive appearance, and looping. `SFSymbolReplace` renders a replacement
between two loaded instances with semantic Magic Replace admission.

For continuous motion, `effectRepeatCount = 3` selects the original intro,
three loop cycles, and outro. `null` keeps infinite playback. Time remains
absolute; the caller does not need to switch phases or capture an intermediate
transform. The catalog preview exposes finite counts and lets the clock run
through the complete native duration, including the last layer's delay.

The catalog includes 8,647 canonical names, nine named weights, and the three
original optical scales. Its 10,467 resource variants preserve appearance,
localization and direction keys, with 282,609 named samples. Paths, metrics,
hierarchy, palette levels, semantic colors, erase operations, shape groups,
motion groups, compiled Draw keyframes, and attachments come from the system
catalog. Each mode uses the original named layer selectors, including paths
shared by several layers. `store.variants(name)` lists the retained keys;
`store.load(name, variantId = variant.id)` explicitly selects one.
No network request is made by the Android renderer.

Compose height in dp supplies the nominal font point size; display density
only affects raster dimensions and pixel alignment. Glyph coordinates scale by
the pixel height divided by source units-per-em. System metric centers determine
positioning. Width follows the glyph's advance when
unconstrained. A narrow canvas does not shrink the font to fit its outline.

`SFSymbol` and `SFSymbolReplace` accept a separate `displayMode` parameter:

| Value | Layout and drawing |
| --- | --- |
| `SquareOverflow` | Square slot, nominal line height, drawing may extend outside the slot |
| `IntrinsicWidth` (default) | Nominal line height, natural glyph advance determines layout width |
| `SquareFitWidth` | Square slot, keeps the nominal line height when the horizontal outline fits; otherwise shrinks uniformly around the center |

For example, `Modifier.height(32.dp)` with
`displayMode = SymbolDisplayMode.SquareFitWidth` reserves a 32 × 32 dp slot.
Narrow symbols keep their original font scale; wide symbols shrink only enough
to fit horizontally. Overflow mode does not add a clip, so an ancestor's
explicit clipping still applies. Replace uses the larger of the two symbols'
horizontal extents to keep one stable scale and slot throughout the transition.
Both animation preview pages expose the three modes.

The importer and reproducibility instructions are in [tools/README.md](tools/README.md).
The generated asset index records source hashes. Recovered native evidence is
kept separately under `D:/MACOS/analysis/system`.
Decompiled pseudocode is evidence for the port, not original source code.

Verification uses Python importer tests and Robolectric's native Android
graphics backend, including all catalog names and rendering modes, original
ARM64 numerical fixtures, Draw intermediate frames, and replacement endpoints.
Reports and frame images are generated under `app/build/reports/symbols`.
These checks establish the tested Android behavior. A side-by-side capture
against the macOS runtime and physical Android GPU rendering are separate
checks; neither is established by a successful compilation or Robolectric run.

System Draw fixtures execute original ARM64 functions at actual 13, 32, and
160 point sizes. Production painter comparisons include density conversion,
coordinate reflection, static sibling paths, compiled contour joining, and
timed attachments. Original Animation::time and Keyframes.eval fixtures also
cover motion and Variable Color dispatch. Legacy application fixtures remain
isolated from the system production catalog. Exact coverage and remaining
limits are recorded in [RENDERER_EVIDENCE.md](RENDERER_EVIDENCE.md); these checks
do not establish every symbol's visual parity on physical hardware.

Resources stay gzip-compressed under a `.json.sfgz` deployment suffix, avoiding
Android's automatic expansion of `.gz` assets. Loading decodes only the selected
symbol and retains a bounded instance cache. The importer preserves source
hashes and the installer verifies every file before publishing the index.

The application does not require extracting an entire macOS restore image.
Original Apple resources are supplied locally by the user; this project does
not download an operating-system image.
