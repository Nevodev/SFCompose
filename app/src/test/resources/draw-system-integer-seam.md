# Rounded keyframe subpath boundary

`draw-system-integer-seam.json.gz` contains the system
`distribute.horizontal.fill` Small Light layer 1 and 42 reference clip frames
(13, 32 and 160 points; both directions). The compiled `first` value in its fifth
keyframe rounds to `2.0`, although the layer contains only two contours.

CoreUI `18f263bdc` independently reproduces all six compiled Float32 keyframes;
the native and importer arrays are exactly equal in
`system-clip-integer-seam-oracle.json`. This is not a corrupt importer value.

The reference executes original RenderBox `196b9c124` and path callbacks.
An observation hook at `196b9c370` records how many raw keyframes each real
contour receives: `[3, 1]`, rather than `[3, 3]`. Collector `196b9bff0` consumes an
ordered prefix while `floor(first) <= currentSubpath`; the rounded `2.0` blocks
the final two records. Flushing after the second contour receives those two
records with an empty path and emits no extra geometry. The port must retain
the original prefix behavior, rather than move the rounded endpoint to a
different contour. Tolerance uses the actual source transform at each size.

Full native reference:
`D:/MACOS/analysis/system/dyld/RenderBox/system-integer-seam-geometry-oracle.json`.
