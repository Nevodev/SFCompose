# SFCompose

[![Maven Central](https://img.shields.io/maven-central/v/io.github.nevodev/sfcompose-catalog?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.nevodev/sfcompose-catalog)
[![Private catalog](https://img.shields.io/maven-central/v/io.github.nevodev/sfcompose-catalog-private?label=Private%20catalog)](https://central.sonatype.com/artifact/io.github.nevodev/sfcompose-catalog-private)
[![Code license: Apache-2.0](https://img.shields.io/badge/Code%20license-Apache--2.0-blue.svg)](LICENSE)

SFCompose brings SF Symbols to Android Jetpack Compose.
Display static and animated symbols with configurable weights, optical scales, colors and replacement transitions.

## Installation

SFCompose is available on [Maven Central](https://central.sonatype.com/artifact/io.github.nevodev/sfcompose-catalog).
Use the version shown in the Maven Central badge above; no repository credentials are required.

Add Maven Central to both plugin and dependency repositories in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Add SFCompose to your version catalog, replacing `<release-version>` with the published version you want to use:

```toml
[versions]
sfcompose = "<release-version>"

[plugins]
sfcompose = { id = "io.github.nevodev.sfcompose", version.ref = "sfcompose" }
```

Apply the plugin in your Android app's `build.gradle.kts`, alongside your Android and Compose plugins:

```kotlin
plugins {
    alias(libs.plugins.sfcompose)
}
```

The plugin adds `io.github.nevodev:sfcompose-catalog` and its runtime dependency automatically.
You do not need an additional `implementation` declaration. It packages the data selected by your symbol references.
Consumers of the published artifacts need neither Python nor the original system resources.

The project's Android SDK and dependency versions are listed in the [version catalog](gradle/libs.versions.toml).
For an existing explicit catalog dependency, set `sfcompose { automaticDependency.set(false) }` to avoid adding it again.

Private symbol entries are available through the optional `sfcompose-catalog-private` add-on.
See its [setup and `PrivateSFSymbols` examples](sfcompose-catalog-private/README.md) to enable it.

## Static symbols

Import the APIs from `com.nevoit.sfcompose.symbols`. The examples below belong inside a `@Composable` function.
Use IDE completion on `SFSymbols` to find names and variants:

```kotlin
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SFSymbols
import com.nevoit.sfcompose.symbols.SymbolScale

SFSymbol(SFSymbols.Wifi, contentDescription = "Wi-Fi")

SFSymbol(
    symbol = SFSymbols.Heart.Fill.Bold,
    contentDescription = "Favorite",
    pointSize = 24.dp,
    scale = SymbolScale.Small,
)
```

A bare entry uses Regular weight. Select a weight with `.Bold`, `.Light` and other named accessors;
use `.Fill` and other name variants where the catalog exposes them.
Set `contentDescription = null` for a decorative icon.

For a weight that changes at runtime, select `.DynamicWeight` and pass `weight = currentWeight`.
Fixed-weight entries package less data than dynamic-weight entries.

### Size and layout

- `pointSize` controls the requested slot height. `scale` selects an optical design independently of that size.
- `padding` insets the drawing area within the slot, without increasing its requested size.
- `displayMode` defaults to `SquareOverflow`: a square slot that permits wide symbols to overflow.
  Use `SquareFitWidth` to fit wide symbols inside the square, or `IntrinsicWidth` for the glyph's natural advance.

## Colors

Pass a tint for a single icon, or provide `LocalSFSymbolColor` for a subtree.
The default tint is black; it does not automatically follow Material content colors.

```kotlin
CompositionLocalProvider(
    LocalSFSymbolColor provides MaterialTheme.colorScheme.onSurface,
) {
    SFSymbol(SFSymbols.Wifi, contentDescription = "Wi-Fi")
}
```

Use Palette mode to assign colors to the symbol's source layers:

```kotlin
SFSymbol(
    symbol = SFSymbols.Airtag.Radiowaves.Forward.Fill,
    contentDescription = "AirTag",
    mode = SymbolRenderingMode.Palette,
    palette = listOf(Color.Blue, Color.Red),
    gradients = true,
)
```

`Monochrome` uses one tint, `Hierarchical` applies hierarchy opacity, and `Multicolor` uses the symbol's source colors.
Palette colors follow source palette levels rather than individual paths.

## Animation

Use the `.Animated` entry with `AnimatedSFSymbol` and choose an effect:

```kotlin
AnimatedSFSymbol(
    symbol = SFSymbols.Wifi.Animated,
    contentDescription = "Wi-Fi",
    animation = SymbolAnimation(effect = SymbolEffect.Bounce),
)
```

For stroke drawing, use `SymbolAnimation(drawEffect = SymbolDrawEffect.On)` or `Off` on a symbol with Draw data.
The default empty `SymbolAnimation()` stays stationary.

To play on a tap, keep an animation state and turn off initial autoplay:

```kotlin
val animationState = rememberSymbolAnimationState()

AnimatedSFSymbol(
    symbol = SFSymbols.Wifi.Animated,
    contentDescription = "Wi-Fi",
    modifier = Modifier.clickable { animationState.replay() },
    state = animationState,
    autoPlay = false,
    animation = SymbolAnimation(effect = SymbolEffect.Bounce),
)
```

Call `replay()` or change `replayKey` to restart playback. Hoist the state above a lazy item to pause
when the icon leaves composition and resume on reentry. Use a separate state for each icon.

`startDelayMillis` delays playback while holding its time-zero frame. To keep the full resting symbol visible
until a trigger, use `autoPlay = false` instead. For repeatable motion, set `playback = SymbolEffectPlayback.Continuous`;
`repeatCount` can limit its cycles. Draw On/Off is not repeated by that setting.

## Replacing symbols

Pass the icon for the current UI state. The first icon appears after loading; subsequent changes animate automatically:

```kotlin
var selected by remember { mutableStateOf(false) }

SFSymbolReplace(
    symbol = if (selected) SFSymbols.Person.Fill.Animated else SFSymbols.Person.Animated,
    contentDescription = "Person",
    modifier = Modifier.clickable { selected = !selected },
)
```

Both entries must use `.Animated`. Changes during playback can reverse or interrupt the active replacement.
Use `direction`, `preferMagic` and `layered` to customize the transition.

## More documentation

- [API guide and advanced loading](sfcompose/README.md)
- [Optional private-symbol catalog](sfcompose-catalog-private/README.md)
- [Building and publishing](PUBLISHING.md)
- [Renderer evidence and verification](RENDERER_EVIDENCE.md)

## License

Original SFCompose code is licensed under [Apache-2.0](LICENSE).
Imported Apple symbol resources are excluded from that license; see [third-party notices](THIRD_PARTY_NOTICES.md).
