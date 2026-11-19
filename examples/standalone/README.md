# Standalone Android consumer

This example resolves SFCompose from a Maven repository. Both app modules use
the same source, apply the build-time selection plugin, and enable Android release
code and resource shrinking.

- `direct` declares the catalog dependency and disables the plugin's automatic dependency.
- `with-report` lets the plugin supply the matching dependency.
- With `-PprivateDemo`, `private-addon` uses public and private entries together,
  `private-unused` depends on the add-on while referencing only public entries,
  and `private-only` uses the private catalog as its primary dependency.

Both generate selected assets in debug and release, and both write a final APK
inventory under `build/reports/sfcompose/<variant>.json`. The full build-data ZIP
is resolved only by the plugin and does not appear on the app runtime classpath.

From the repository root, publish the runtime, catalog, and plugin to
the local repository under `build/sfcompose-repository`.

```text
gradlew :sfcompose:publish :sfcompose-catalog:publish :sfcompose-gradle-plugin:publish
gradlew -p examples/standalone :direct:assembleRelease :with-report:assembleRelease
```

The standalone build uses the same dependency version catalog by default. To
copy the project elsewhere, supply the dependency catalog with `-PversionsFile`
and the Maven repository path with `-PsfcomposeRepository`. Pass
`-PdirectOnly` to load just the module that uses the catalog dependency.

The plugin defaults to its matching catalog version. If you publish your own
catalog, configure its Maven coordinate and publish the matching `build-data` ZIP
classifier alongside the lightweight AAR.

```kotlin
sfcompose {
    catalog.set("your.group:your-catalog:your-version")
}
```

The report scans the finished APK and accounts for each physical resource once
in the total. If several retained entries point to the same bytes, the file
lists all matching entries. Unmatched `.sfgz` paths are listed separately.

For the public catalog, the report verifies each selected asset against its hash
and manifest. `symbolBytes` counts unique compressed payload bytes. The selection
report also lists each weight, optical scale and locale/direction variant.
Generated entry navigation does not retain parent Regular data.

Selection runs before R8. Compiled references in unused methods and both sides of
runtime branches may retain data. Reflection requires explicit `sfcompose.retain`
entries. The plugin never guesses a runtime symbol name or silently retains the
full catalog.
