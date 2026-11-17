# Original Clipper frame fixture

Source: selectively extracted SF Symbols 27.2 build 150 application bundle, `CoreGlyphsLib.arm64`, SHA-256 `e655d49272198fb43bde37a6449cc6c81271bc3aa3c87c0a125f87b0eeaddb8a`.

The fixture contains 924 part outputs, making 264 annotated-layer frames for `cellularbars` and `airplay.audio`, Regular weight, all three optical scales, Draw On and Draw Off. Progress samples are 0.05 through 0.95 in steps of 0.05, plus 0.02, 0.98 and 0.999.

Commands were recorded while executing the original ARM64 `Transform::subpath_keyframes` at 0x9940, including its natural-cap construction and original emission and clipping functions, in Unicorn 2.1.4. Local shims supply allocation, copying, stack probing and affine translation. The original geometry kernels are unchanged.

Inputs are Android's prepared plans. This fixture independently checks clipping and natural-cap playback for those inputs; it does not independently validate the preceding authoring-to-plan conversion or a different macOS system renderer. The Canvas comparison allows total alpha error of 1.5 percent of reference coverage plus four opaque pixels for edge rasterization differences.

Analysis generator: `D:/MACOS/analysis/native/oracles/sfcompose_draw_actual_plan_oracle.py --dense`, with its adjacent Mach-O loader and import shim helper. Inputs are retained in its `inputs` directory and under `app/build/reports/symbols/native-plan-<name>-<scale>.json`; the generated commands are also retained under `D:/MACOS/analysis/native/draw-native-actual-plans-dense.json`. The investigation is documented in `D:/MACOS/analysis/native/draw-numerics-and-caps-findings.md`.

The original macOS 27.2.1 build 26B131 system CoreUI kernel was independently executed on all 924 part inputs. Its emitted commands match this application-bundle fixture exactly, including every floating-point coordinate. The system `__TEXT` SHA-256 is `106cf9861cfe5778cd6eac6f3fc3a2a2e97a7536fe7c757dd3f8d7fc9a4b0c07`; all segment hashes are recorded in `D:/MACOS/analysis/system/dyld/CoreUI/draw-kernel-provenance.json`. The comparison is retained in `draw-system-app-comparison.json` in that directory.

System entrypoints are natural-cap builder `0x18F25CAF8`, Clipper run `0x18F25D878`, cap emission `0x18F25E410`, and Clipper construction `0x18F25E98C`. The execution harness shims local allocation/copying/affine translation and treats pointer-authentication instructions as address authentication without a signature; it does not replace the geometry kernels. This rules out a clipping-kernel difference between these two versions for the tested prepared inputs. It does not validate system authoring export, timing, or every symbol and weight.
