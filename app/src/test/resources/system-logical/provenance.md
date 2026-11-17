`homepod.mini.arrow.forward.json.gz` is the system BVG import with native mode
layer assembly enabled, from `android-assets-system-catalog-modes`. It is loaded
through the real SymbolAssetStore in the merged-attachment regression.

For each rendering mode, source path 2 (head) and 3 (stem) share native layer 2.
The layer contains both contours and offset compiled keyframes, rather than
separate path nodes. Its 48 frame comparisons use the original RenderBox
reference retained by `draw-system-attachments.json.gz` (16 for each mode).
The test compares geometry in a fixed color; mode palette output is outside its
scope.
