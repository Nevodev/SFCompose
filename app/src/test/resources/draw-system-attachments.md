# System Draw attachment oracle

`draw-system-attachments.json.gz` retains 160 full glyph frames for the actual
macOS 27.2.1 (26B131) CoreGlyphs `arrow.right`, `arrow.clockwise`, and
`arrow.up.and.down`, and `homepod.mini.arrow.forward` medium regular
renditions. Input paths, compiled keyframes, dynamic anchors and attachment CSS
come from the system BVG resources, rather than SF Symbols.app authoring ties.

The reference resolver is RenderBox `196b9e504` / `196b9e56c`; reference attachment
transforms execute the original layer evaluator `196a9e058`. The underlying glyph
clip executes `196b9c124` and its original path callbacks. The only imported
geometry helpers are affine translation and integer sorting. The oracle runs in
font coordinates, with a tolerance derived from the actual BVG transform and
32-point scene scale. This is the inverse of the uniform system source transform.
The two scalar progress values and 0.8-second layer duration are explicit inputs;
these frames do not validate the animator's scheduling of those inputs.

The first two symbols test both clip directions and attachment flags 0 through 3,
retaining the original timed bit 4. Those flag variants are deliberate evaluator probes.
The third tests two attachments on one bisecting stem, with their original
nonzero appearance thresholds, in both clip directions.
The fourth retains two separate source paths with the same `.monochrome-2`
selector. The original CoreUI `_assumeConcatenatedPath` (`18f23db28`) collects
these paths in document order. The concatenator (`18f2ad4ac`) adds paths and
offsets the stem's compiled keyframes by the preceding head's subpath count.
The native oracle receives that merged layer; the Android test retains two
independent path nodes and must resolve the attachment through their shared
mode layer and correct subpath offsets.
The native output includes hidden-subpath decisions, cap-following matrices and
the resulting combined glyph path. Source and output are retained at
`D:/MACOS/analysis/system/dyld/RenderBox/system-attachment-transform-oracle.json`;
the producing helpers are retained alongside those references as
`sfcompose_attachment_native.py` and `sfcompose_multi_attachment_native.py`.
