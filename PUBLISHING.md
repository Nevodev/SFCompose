# Publishing SFCompose

The Maven group is configured in `gradle/publishing.gradle`. Artifact and plugin
versions come from `gradle/libs.versions.toml`. The Android package names remain
`com.nevoit.sfcompose.*`; Maven consumers use the `io.github.nevodev` namespace.

| Component | Maven artifact / plugin ID |
| --- | --- |
| Runtime | `io.github.nevodev:sfcompose` |
| Public catalog | `io.github.nevodev:sfcompose-catalog` |
| Optional private catalog | `io.github.nevodev:sfcompose-catalog-private` |
| Gradle plugin implementation | `io.github.nevodev:sfcompose-gradle-plugin` |
| Gradle plugin ID | `io.github.nevodev.sfcompose` |

Apply the plugin with a version from the consumer's plugin catalog. Add
`mavenCentral()` to both `pluginManagement.repositories` and dependency
repositories. The plugin adds the matching public catalog dependency by default.

## Credentials and signing

Generate a user token in Central Portal and keep its downloaded `settings.xml`
outside the repository. The token name is only a Portal label; it need not match
the server ID inside the XML file. The publishing helper reads the XML and passes
credentials to Gradle through its child-process environment without modifying it.

Use a PGP signing key with a public key discoverable by Central's supported public
key servers. Keep the private key and passphrase outside the repository. The
helper accepts the ASCII-armored private key and an optional UTF-8 passphrase file.
It disables Gradle's configuration cache for signing runs so signing material is
not serialized into a reusable configuration entry. Never run credentialed builds
with Gradle debug logging or share their process environment.

## Local validation

Run the helper with Python, replacing the paths with your local private files:

```text
python tools/publish_central.py --settings <settings.xml> --signing-key <private-key.asc> --signing-password-file <passphrase.txt> --local
```

This publishes the runtime, Gradle plugin and its marker, public catalog, and
private catalog only to `build/sfcompose-repository`. Catalog generation can take
longer than runtime compilation and requires the imported source data. Private
source data defaults to `build/private-assets`; an alternate source can be selected
with the existing `sfcomposePrivateAssets` Gradle property.

Use `--modules sfcompose sfcompose-gradle-plugin` for a quick runtime/plugin build,
or `--modules sfcompose-catalog-private` to publish only the private add-on. Without
`--modules`, all four modules are included.

Publications include source archives, a documentation archive, POM metadata and
PGP signatures. The documentation archive contains the usage guide and notices;
API KDoc is supplied in the runtime source archive. It is not generated API HTML.
The catalog also publishes its separate `build-data` ZIP classifier.

## Upload and release

After local validation and public-key distribution, omit `--local` to upload a
deployment to Central Portal. Review the original resource terms before including
either catalog in a distribution. All selected modules use the same version.
Use `--modules` when adding a missing module so already validated deployments are
not uploaded again.

The upload uses manual release. Inspect the deployment validation results at
<https://central.sonatype.com/publishing/deployments> and click **Publish** when
ready. A released Maven version cannot be overwritten.

For direct Gradle use, supply `mavenCentralUsername`, `mavenCentralPassword`,
`signingInMemoryKey` and, if needed, `signingInMemoryKeyPassword` through private
Gradle properties or `ORG_GRADLE_PROJECT_` environment variables. Enable signing
with `-PsfcomposeSigning=true` and enable Central tasks with
`-PsfcomposeCentral=true`. Both flags are supplied by the helper when uploading.
Without the Central flag, `publish` keeps using the local repository. Local
unsigned publications still work without the signing flag; Central upload tasks
reject unsigned publication.

## License scope

Original project code is Apache-2.0. Imported symbol resources are excluded from
that license. Read `THIRD_PARTY_NOTICES.md` before distributing catalog data or
assets selected from it.
