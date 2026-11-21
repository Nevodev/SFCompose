# SFCompose for Android

With the artifact repository configured, depend on
`io.github.nevodev:sfcompose-catalog` at the desired release version.
It brings in the runtime automatically. In this repository, use
`implementation(project(":sfcompose-catalog"))`. Import `SFSymbol`, `SFSymbols`,
`SymbolScale`, and `SymbolWeight` from `com.nevoit.sfcompose.symbols`.

Apply the `io.github.nevodev.sfcompose` Gradle plugin to the consuming Android app.
It adds the matching catalog dependency, resolves the full catalog as a build-only
ZIP, and generates assets after Kotlin/Java compilation for both debug and release.
The full ZIP is not a runtime dependency and never enters the APK.

```kotlin
plugins {
    id("io.github.nevodev.sfcompose") // Version comes from the consumer's plugin catalog.
}
```

If the app already declares the catalog dependency, set
`sfcompose { automaticDependency.set(false) }`. For a project dependency in this
repository, use `sfcompose { catalog.set(":sfcompose-catalog") }`.
See the [standalone examples](../examples/standalone) for both forms.

The [private-symbol add-on](../sfcompose-catalog-private) is an optional
catalog with its own `PrivateSFSymbols` entries.

```kotlin
SFSymbol(SFSymbols.Wifi, contentDescription = "Wi-Fi")
SFSymbol(SFSymbols.Wifi.Bold, contentDescription = "Wi-Fi",
    pointSize = 20.dp, scale = SymbolScale.Small)
SFSymbol(SFSymbols.Person.Fill, contentDescription = "Person")
SFSymbol(SFSymbols.Wifi.DynamicWeight, weight = currentWeight,
    contentDescription = "Wi-Fi")
```

A bare entry selects Regular. Scale defaults to Medium and can be changed at
runtime independently of `pointSize`. Symbol loading follows the current Compose
locale and layout direction. Fill, named weights and the nine-weight dynamic
entry have separate asset closures. A fixed entry retains only its weight, all
three scales, and its actual locale, appearance and direction closure.
`DynamicWeight` explicitly retains all nine named weights. Android release code
shrinking also removes unused generated API classes and renderer code.

The default `displayMode` is `SymbolDisplayMode.SquareOverflow`. The slot is
square, and wide symbols can extend beyond its sides. Set `padding` to inset
the drawing area on all four sides while keeping the layout size. For example,
`pointSize = 32.dp` and `padding = 4.dp` give a 32 × 32 dp slot with a
24 × 24 dp drawing area. Choose `SquareFitWidth` to fit wide symbols within
that drawing area.

The default public catalog generates every name in the imported
`symbols/index.json`. Its generated Kotlin namespaces support IDE completion for
`SFSymbols`, including named weights and animation entries. Producers can set
`-PsfcomposeCatalog` to build a deliberately smaller catalog.

For example, `SFSymbols.Heart.Fill.Bold` selects only that entry. Navigating
through `Heart` and `Fill` does not retain either parent's Regular payload.

The public AAR contains lightweight namespaces and accessors. Full compressed
data is published separately with the `build-data` ZIP classifier. It stores one
deduplicated representation of all weights/scales and compiled Draw data; the
plugin slices it at build time, without reconstructing Draw at runtime or encoding
payloads as JVM/Base64 strings. Consumers of the published catalog require no Python.

Namespaces are independent Kotlin objects exposed through properties, so
`SFSymbols.Heart.Fill.Bold` keeps the same value syntax without making IDE
completion or navigation expand the whole catalog. Runtime, public catalog and
private catalog publications include source JARs. Updating from the previous
nested-class catalog requires recompiling consumers; its JVM entry layout has changed.

The plugin scans app and dependency bytecode before asset merging. It follows
generated references through locals, fields, helper methods, interfaces, branches
and common containers. This is conservative: both branches and references in
unused compiled methods can retain data, even if R8 later removes that code.
Reflection and arbitrary runtime name construction cannot be inferred. Declare
such uses explicitly rather than relying on a silent full-catalog fallback:

```kotlin
sfcompose {
    retain.add("wifi:DynamicWeight:Animated")
}
```

Generated reports list retained weights, scales, variants and deduplicated payload
bytes under `build/reports/sfcompose/<variant>-selection.json`. Assembly also checks
the finished APK and writes `<variant>.json`; it rejects missing or orphan payloads.

`SFSymbol` loads the static resource and starts no animation clock.
`AnimatedSFSymbol` takes the generated `.Animated` entry, which includes Draw
keyframes and animation anchors where available. Build-time selection excludes
animation payloads when only the static entry is used. Both entry
types retain the three scales plus the symbol's locale and direction variants.

Pass `animation` to play an effect after the symbol loads, and change
`replayKey` to restart it. Callers can also drive `variableValue` and
`variableDrawValue`. Draw actions require a symbol with Draw data.

```kotlin
AnimatedSFSymbol(SFSymbols.Wifi.Animated, "Wi-Fi", animation = SymbolAnimation(effect = SymbolEffect.Bounce),
    replayKey = tapCount)
AnimatedSFSymbol(SFSymbols.Airplay.Audio.Animated, "AirPlay",
    animation = SymbolAnimation(drawEffect = SymbolDrawEffect.On, startDelayMillis = 500))
```

`startDelayMillis` defaults to zero. After loading, the animation holds its
time-zero frame for this delay, then plays normally. Changing `replayKey` repeats
the delay; loop iterations do not. Waiting suspends the animation coroutine
without running a frame clock. Animation time is read during drawing, so the
delay and subsequent frame updates do not themselves trigger recomposition.

Set `autoPlay = false` to show the resting symbol initially and play only on an
explicit trigger. Unlike a delayed Draw On, this keeps the full resting symbol
visible until triggered, with no playback clock running:

```kotlin
val animationState = rememberSymbolAnimationState()
AnimatedSFSymbol(
    SFSymbols.Shareplay.Medium.Animated,
    contentDescription = null,
    state = animationState,
    autoPlay = false,
    animation = SymbolAnimation(effect = SymbolEffect.Bounce),
    modifier = Modifier.clickable { animationState.replay() },
)
```

`state.replay()` or changing `replayKey` starts playback, including any configured
start delay. The flag defaults to `true` and controls the initial trigger only:
setting it to `false` does not stop a previously triggered animation. Setting it
to `true` enables playback if it has not started. Without an effect configured,
the default empty `SymbolAnimation()` remains stationary either way.

To preserve playback when a lazy item leaves composition, create its state in
the screen above the list and pass it into the icon:

```kotlin
val headerAnimation = rememberSymbolAnimationState() // Outside LazyColumn/item.
LazyColumn {
    item(key = "header") {
        AnimatedSFSymbol(
            SFSymbols.Shareplay.Medium.Animated,
            contentDescription = null,
            state = headerAnimation,
            animation = SymbolAnimation(effect = SymbolEffect.Bounce),
        )
    }
}
```

Leaving composition pauses playback and the start delay; reentry resumes at the
retained position. Completed playback stays at its final frame. The state also
retains the last loaded symbol for the same resource request, so reentry can draw
immediately without an empty loading frame. `headerAnimation.replay()` explicitly
restarts playback, including the delay; it also works while the icon is absent.
After playback is triggered, changing the symbol, weight, scale, locale/direction, animation settings, rendering
mode, effective point size or `replayKey` starts a new playback.

One state belongs to one icon at a time. Without an explicit state, the icon
creates a local one and keeps the previous replay-on-reentry behavior. The
remembered state lasts while its owner remains in composition; it does not restore
playback after process death. Frame updates are still observed only by drawing.

Pass the icon for the current state to `SFSymbolReplace`. The first icon appears
immediately after loading. Changes to the target animate in either direction.

```kotlin
SFSymbolReplace(
    symbol = if (selected) SFSymbols.Person.Fill.Animated else SFSymbols.Person.Animated,
    contentDescription = "Person",
    modifier = Modifier.clickable { selected = !selected },
)
```

`rememberSFSymbol` returns `null` while loading. Use an `.Animated` entry with
it to obtain a `SymbolInstance` for caller-controlled clocks and interruptible
replacements. For runtime weight selection, use `.DynamicWeight.Animated`.

Static, animated, and Replace entries all accept a palette and gradients.
Palette colors follow the symbol's layer assignments. To set the layer colors
without a list, pass `tint`, `secondary`, and `tertiary` with Palette mode.

```kotlin
SFSymbol(
    SFSymbols.Airtag.Radiowaves.Forward.Fill,
    contentDescription = "AirTag",
    mode = SymbolRenderingMode.Palette,
    palette = listOf(Color.Blue, Color.Red),
    gradients = true,
)
```

Use `LocalSFSymbolColor` to set the default tint for a subtree. It defaults to
black. An explicit `tint` takes priority over the local value.

```kotlin
CompositionLocalProvider(LocalSFSymbolColor provides Color.Blue) {
    SFSymbol(SFSymbols.Wifi, contentDescription = "Wi-Fi")
}
```
