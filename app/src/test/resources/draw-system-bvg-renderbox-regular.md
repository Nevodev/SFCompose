The JSON fixture starts from the macOS 27.2.1 (26B131) system CoreGlyphs.bundle
Assets.car, not from SF Symbols.app authoring metadata or Android prepared paths.
The Regular / Small renditions for airplay.audio.svg and cellularbars.svg were
selectively extracted from the locally supplied restore image.

CoreUI's original BVG path decoder (0x18f2e5484) and numeric-list decoder
(0x18f2e58cc) decoded each path and its data-clipstroke-keyframes attribute.
A pure Python implementation was compared with these original functions:
all 51 path/list payloads across Ultralight, Regular and Black matched exactly.
The input uses the Regular decoded path in font coordinates, with Y flipped
consistently for both the original renderer and Android Canvas. CoreUI applies
the outer SVG transform before creating its runtime path; this fixture does
not yet cover that scale-dependent curve-length tolerance.

The macOS RenderBox implementation executes its Transform constructor
(0x196b9c124), then consumes the original path command sequence, closes the
contour, and ends the path. It uses flags 0, RB LineCap 0 (round), and tolerance
0.5. Keyframes retain the compiled system endpoint order, flags and progress.
Unicorn executes the ARM64 code; shims implement allocation, copying and path
command collection, and bypass pointer-authentication bookkeeping.

There are 98 individual annotated-contour frames, forming 28 glyph frames:
two symbols, Draw On / Off, and progress 0.02, 0.1, 0.25, 0.5, 0.75, 0.9, 0.98.
The unannotated airplay triangle is outside this geometry comparison.
This fixture checks the filled-outline clipping path; device composition,
animation timing, color and glyph layout require separate verification.
