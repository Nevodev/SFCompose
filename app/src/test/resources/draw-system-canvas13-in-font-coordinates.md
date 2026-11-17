These three fixtures use the macOS 27.2.1 system CoreGlyphs renditions and the
original RenderBox ClipStroke implementation, rather than Android prepared
plans. The BVG outer transform is applied to the input paths before executing
RenderBox. The inputs therefore use the native reference canvas at 13 points.

The fixtures cover Small and Medium Regular airplay.audio and cellularbars,
Draw On and Off, at seven progress values. Each fixture has 196 individual
annotated-contour frames, grouped into 56 symbol frames. The original native
constructor receives flags 0 and RB round cap 0.

RenderBox derives scene scale from requested point size / reference point size,
multiplied by fontMatchingScaleFactor. Its curve tolerance is 0.5 / sceneScale.
The fixtures use fontMatchingScaleFactor 1 and requested sizes 13, 32 and 160:

- draw-system-canvas13-in-font-coordinates.json: tolerance 0.5.
- draw-system-canvas13-point32-in-font-coordinates.json: tolerance 0.203125.
- draw-system-canvas13-point160-in-font-coordinates.json: tolerance 0.040625.

Only after native execution are commands transformed back into the font
coordinates used by Android. The tests pass the corresponding requested point
size to SymbolDraw, then rasterize original and ported paths with one Canvas
transform and paint. This checks source-to-clipped-path geometry at each size;
UI animation scheduling, layer transforms, attachments, timing and colors are
separate checks.
