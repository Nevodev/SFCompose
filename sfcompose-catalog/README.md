# Public catalog packaging

The public catalog exposes every imported name through the existing
`SFSymbols.Heart.Fill.Bold` syntax. It publishes two artifacts:

- The AAR contains only namespaces and lightweight symbol references, and depends
  on the renderer. It contains no glyph payloads.
- The `build-data` ZIP classifier contains deduplicated gzip documents with all
  nine weights, three optical scales and compiled Draw samples, including each
  symbol's locale, appearance, RTL and auxiliary-image closure.

The consuming application must apply `io.github.nevodev.sfcompose`. The plugin resolves
the ZIP on a separate build-only configuration, scans compiled app/dependency
classes, selects entries and writes generated Android assets. It runs before
asset merging in debug and release. The ZIP never enters the runtime classpath.
Consumers of published artifacts do not need Python or the original system data.

Fixed entries retain one weight. `DynamicWeight` retains nine. Static entries
strip animation data; `.Animated` keeps compiled keyframes and anchors. Both keep
all three scales and automatic locale/direction selection. Navigation through
parent names does not retain their Regular glyphs.

Selection precedes R8, so it conservatively includes compiled references from
unused methods and both sides of runtime branches. Runtime name construction or
reflection needs explicit `sfcompose.retain` entries. This design does not claim
R8-level reachability pruning of assets.

See [consumer API and configuration](../sfcompose/README.md) and the
[independent Maven consumers](../examples/standalone/README.md).

## Producer

`generate_build_catalog.py` reads the imported assets directly and caches
per-symbol documents. It emits a deterministic `build-data.zip` with
`payloads.bin` and `index.json.gz`, plus `namespaces.tsv` for the lightweight JVM
entry generator. The optional `--inventory` argument migrates an existing JVM
catalog; ordinary builds do not require it.

`generateCatalogJar` emits the API JAR from the namespace table.
`publishReleasePublicationToSfcomposeLocalRepository` publishes the AAR and
build-data classifier together. Library version and dependencies come from the
repository version catalog.

## Verification

The full source-generated catalog contains 8,647 names. The measured AAR is
12,616,912 bytes, the separate build-data ZIP is 108,382,171 bytes, and the
renderer AAR is 591,607 bytes. These are artifact measurements, not size guarantees.

Independent Maven consumers were built with normal `assembleDebug` and
`assembleRelease`, configuration cache enabled, using the published artifacts.
Explicit dependency and automatic plugin dependency produced the same release
APK size: 1,484,878 bytes. The debug APK was 36,460,082 bytes, including unshrunk
API/framework code. Both variants retained exactly these eight entries and
28 unique payloads totaling 46,962 bytes:

| Entry | Closure payload bytes |
| --- | ---: |
| `0.circle.ar:Regular` | 8,459 |
| `airplay.audio:Regular:Animated` | 6,346 |
| `airtag.radiowaves.forward.fill:Regular` | 12,896 |
| `heart.fill:Bold` | 3,154 |
| `person.fill:Regular:Animated` | 3,174 |
| `person:Regular:Animated` | 3,551 |
| `sun.max:Regular` | 4,978 |
| `wifi:Bold` | 4,404 |

Separate release consumers also verified `wifi:Bold` (4,404 payload bytes),
`wifi:Bold:Animated` (5,063), and `wifi:DynamicWeight` (8,431).
`report_selected_catalog.py` independently validates hashes, exact selected
entries, named weights, all scales, source locale/direction closure paths,
static/animated data boundaries and absence of orphan payloads.

Nine JVM scanner tests, seven Robolectric rendering/loading tests, and three
Python producer tests passed. Robolectric covers Draw, replacement, weight,
scale, locale and direction loading. No physical device was installed or tested
for this packaging change.
