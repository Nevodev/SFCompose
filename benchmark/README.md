# Static symbol scrolling benchmark

`SymbolScrollBenchmark` compares Compose `painterResource` with `SFSymbol`
in the same full-screen `LazyVerticalGrid`. Both draw the Regular, Medium
monochrome `xmark` at 24 dp with the same tint. The VectorDrawable uses the
same glyph contours, metrics and placement as the renderer.

Each renderer warms up for 40 scroll frames and returns to the top. Each
measured iteration scrolls 8 dp per frame for 300 frames. Both groups use
full compilation and five iterations. The measurement includes lazy grid
layout, display list recording and rendering, with initial asset loading
outside the measurement.

Build both APKs without a connected device:

```text
gradlew :app:assembleBenchmark :benchmark:assembleBenchmark
```

Run on a connected Android test device when ready:

```text
gradlew :benchmark:connectedBenchmarkAndroidTest
```

The benchmark target is the separate profileable package
`com.nevoit.sfcompose.benchmark`. Read the JSON and Perfetto traces in
`benchmark/build/outputs/connected_android_test_additional_output`.
`FrameTimingMetric` reports CPU frame duration at P50/P90/P95/P99 and frame
overrun on API 31 and later.
