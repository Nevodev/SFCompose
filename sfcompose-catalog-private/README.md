# SFCompose private symbols

The separate catalog uses the vector renditions in the system
`CoreGlyphsPrivate.bundle`. It exposes the same statically referenced entries
as the public catalog under `PrivateSFSymbols`. It also uses the same runtime.

Apply `com.nevoit.sfcompose` to the consuming Android application and add the
private Maven coordinate to `sfcompose.additionalCatalogs`. The plugin adds the
lightweight AAR dependency and resolves its `build-data` ZIP separately for build
tasks. The full ZIP never enters the runtime classpath or APK.

```kotlin
sfcompose {
    additionalCatalogs.add("com.nevoit.sfcompose:sfcompose-catalog-private:${libs.versions.sfcompose.get()}")
}
```

Public and private entries use the same renderer, reference scanner and build-time
asset generator. The application needs no private-symbol allowlist. With this
configuration, unused private payloads are omitted in both debug and release.
For a private-only app, set `sfcompose.catalog` to the private coordinate instead.

```kotlin
SFSymbol(
    PrivateSFSymbols.Apps.Ipad.Badge.Icloud.Bold,
    contentDescription = "iPad apps in iCloud",
)
```

A fixed entry retains only its named weight, all three optical scales and its
actual locale/appearance/RTL closure. `DynamicWeight` explicitly retains all nine
weights. Static entries omit animation data; `.Animated` retains compiled Draw
keyframes and anchors. No Draw reconstruction was moved into the Android loader.
The selection and final APK reports cover both catalogs, including each record's
`catalog` namespace, weight, scale, variant and byte count.

Selection happens before R8, so references in compiled unused methods may
conservatively retain data. Reflection requires explicit `sfcompose.retain`
entries. Catalogs with duplicate symbol names are rejected during the build;
the imported public and private catalogs have no overlapping names.

## Build from the local system

Read the file at
`/System/Library/PrivateFrameworks/SFSymbols.framework/Versions/A/Resources/CoreGlyphsPrivate.bundle/Contents/Resources/Assets.car`
using the system image reader. Pass the extracted CAR to the importer along
with the system interpolation oracle and clip interpolator used for the public
catalog.

```text
python tools/import_private_catalog.py --car <Assets.car> --samples <system-interpolation-oracle.json> --native-clip-module <sfcompose_design_system_clips_fast.py> --output build/private-assets
gradlew :sfcompose-catalog-private:publishReleasePublicationToSfcomposeLocalRepository
```

Use `-PsfcomposePrivateAssets` to point the build at another imported asset
root. The default producer generates every imported private name with the same
`generate_build_catalog.py` and `ThinCatalogGenerator` used by the public catalog,
using the `PrivateSFSymbols` root. It publishes one lightweight AAR plus the
`build-data` ZIP classifier. No internal resource AARs are required. A producer
can provide a comma-separated `-PsfcomposePrivateCatalog` for a smaller catalog.

Run the independent consumer with
`gradlew -p examples/standalone -PprivateDemo :private-addon:assembleRelease`.
The `private-unused` example adds the same private dependency while referencing
only public entries, to verify that no private payload is packaged.
The `private-only` example replaces the primary catalog coordinate and checks
private fixed and animated dynamic-weight entries without a public catalog.

## Verified migration

The complete 1,755-symbol add-on produces a 2,895,446-byte AAR and a
21,931,646-byte build-data ZIP. Its POM no longer has resource-part dependencies,
and the AAR contains no raw symbol assets. Both catalogs share the existing
renderer; their build-data ZIPs stay in the build dependency cache.

Normal standalone Maven consumer builds with configuration cache enabled passed:

| Scenario | APK bytes | Retained entries | Unique payload bytes |
| --- | ---: | ---: | ---: |
| Public + private debug | 39,121,512 | 3 | 29,055 |
| Public + private release | 1,344,696 | 3 | 29,055 |
| Private dependency, only public references, release | 1,485,014 | 8 public, 0 private | 46,962 |
| Private-only release | 1,345,043 | 2 | 30,952 |

The mixed consumer retains only `wifi:Bold`,
`apps.ipad.badge.icloud:Bold` (including its RTL closure), and
`apple.math.notes:Regular:Animated`. The private-only consumer retains
`apps.ipad.badge.icloud:Bold` and `apple.math.notes:DynamicWeight:Animated`;
the latter preserves nine weights across all three scales. The independent APK
verifier checks every payload hash, closure path, weight and scale, and rejects
orphan data and packaged build-data archives.

Four Python producer tests, nine reference-scanner tests, three multi-catalog
asset tests, and three Robolectric loading tests passed. The asset tests cover
stale private data removal and explicit duplicate-name rejection. Loading tests
check all three optical scales, rejection of an unretained weight and private
RTL selection. No phone installation or physical-device verification was done.
