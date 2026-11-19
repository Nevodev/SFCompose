package com.nevoit.sfcompose.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.rules.TemporaryFolder

import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static org.junit.Assert.*

class MultiCatalogAssetsTest {
    private static final List<String> WEIGHTS = ['Ultralight', 'Thin', 'Light', 'Regular', 'Medium',
        'Semibold', 'Bold', 'Heavy', 'Black']
    private static final String PUBLIC_SYMBOL = 'public'
    private static final String PRIVATE_SYMBOL = 'secret'
    private static final String PUBLIC_ENTRY = 'public:Regular'
    private static final String PRIVATE_ENTRY = 'secret:Bold:Animated'

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder()

    private File workspace
    private File publicArchive
    private File privateArchive

    @Before
    void createCatalogs() {
        workspace = temporary.newFolder('consumer')
        publicArchive = catalog(new File(temporary.newFolder('public'), 'build-data'), PUBLIC_SYMBOL, [
            'com/nevoit/sfcompose/symbols/SFSymbols'       : '',
            'com/nevoit/sfcompose/symbols/SFSymbols$Public' : PUBLIC_SYMBOL,
        ], 'public-source')
        privateArchive = catalog(new File(temporary.newFolder('private'), 'build-data'), PRIVATE_SYMBOL, [
            'com/nevoit/sfcompose/symbols/PrivateSFSymbols'        : '',
            'com/nevoit/sfcompose/symbols/PrivateSFSymbols$Secret' : PRIVATE_SYMBOL,
        ], 'private-source')
    }

    @Test
    void retainsPublicStaticAndPrivateAnimatedSideBySide() {
        runTask([publicArchive, privateArchive], [PUBLIC_ENTRY, PRIVATE_ENTRY])

        Map report = readReport()
        assertEquals('sfcompose-selected-v1', report.format)
        assertEquals([PUBLIC_ENTRY, PRIVATE_ENTRY] as Set, report.entries.keySet() as Set)

        Map publicGlyph = recordOf(report, PUBLIC_ENTRY, "symbols/${PUBLIC_SYMBOL}.json")
        Map privateGlyph = recordOf(report, PRIVATE_ENTRY, "symbols/${PRIVATE_SYMBOL}.json")
        assertGlyph(publicGlyph, PUBLIC_SYMBOL, 'Regular', false)
        assertGlyph(privateGlyph, PRIVATE_SYMBOL, 'Bold', true)
        assertPayloads(report.entries[PUBLIC_ENTRY])
        assertPayloads(report.entries[PRIVATE_ENTRY])

        Map publicPayload = payload(publicGlyph)
        Map privatePayload = payload(privateGlyph)
        // Both archives are named build-data.zip, so provenance is asserted through payload identity.
        assertEquals(PUBLIC_SYMBOL, publicPayload.name)
        assertEquals(PRIVATE_SYMBOL, privatePayload.name)
        assertNotEquals(publicGlyph.sha256, privateGlyph.sha256)

        assertSingleWeight(publicPayload, 'Regular')
        assertSingleWeight(privatePayload, 'Bold')

        assertFalse(publicPayload.containsKey('systemAnchors'))
        assertFalse(publicPayload.containsKey('clipStrokeKeyframes'))
        assertFalse(publicPayload.scales.small.masters[0].containsKey('clipStrokeKeyframes'))
        assertFalse(publicPayload.scales.small.masters[0].containsKey('clipStrokeKeyframeSets'))
        assertFalse(publicPayload.scales.small.masters[0].containsKey('-sfsymbols-clipstroke-attachments'))

        assertEquals(['top-level'], privatePayload.systemAnchors)
        assertEquals(['top-level'], privatePayload.clipStrokeKeyframes)
        assertEquals(['Bold'], privatePayload.scales.small.masters[0].clipStrokeKeyframes)
        assertTrue(privatePayload.scales.small.masters[0].containsKey('clipStrokeKeyframeSets'))

        assertEquals(PUBLIC_ENTRY, payload(recordOf(report, PUBLIC_ENTRY, 'symbols/index.json')).packedFor)
        assertEquals(PRIVATE_ENTRY, payload(recordOf(report, PRIVATE_ENTRY, 'symbols/index.json')).packedFor)
        assertTrue(new File(output(), 'sfcompose/entries/public/Regular/static.json').isFile())
        assertTrue(new File(output(), 'sfcompose/entries/secret/Bold/animated.json').isFile())
        assertFalse(new File(output(), 'sfcompose/entries/public/Regular/animated.json').exists())
        assertFalse(new File(output(), 'sfcompose/entries/secret/Bold/static.json').exists())
    }

    @Test
    void rebuildRemovesStalePrivateManifestsAndPayloads() {
        runTask([publicArchive, privateArchive], [PUBLIC_ENTRY, PRIVATE_ENTRY])
        Map first = readReport()
        Set<String> publicHashes = hashes(first.entries[PUBLIC_ENTRY])
        Set<String> privateHashes = hashes(first.entries[PRIVATE_ENTRY])
        assertFalse(publicHashes.isEmpty())
        assertTrue(publicHashes.intersect(privateHashes).isEmpty())

        File publicManifest = new File(output(), 'sfcompose/entries/public/Regular/static.json')
        File privateManifest = new File(output(), 'sfcompose/entries/secret/Bold/animated.json')
        assertTrue(publicManifest.isFile())
        assertTrue(privateManifest.isFile())
        byte[] publicBytes = publicManifest.bytes

        runTask([publicArchive, privateArchive], [PUBLIC_ENTRY])

        Map second = readReport()
        assertEquals([PUBLIC_ENTRY] as Set, second.entries.keySet() as Set)
        assertFalse(privateManifest.exists())
        File privateWeightDirectory = privateManifest.parentFile
        assertTrue(!privateWeightDirectory.exists() || privateWeightDirectory.listFiles().length == 0)
        assertArrayEquals(publicBytes, publicManifest.bytes)
        publicHashes.each { assertTrue(new File(output(), "sfcompose/data/${it}.sfgz").isFile()) }
        privateHashes.each { assertFalse(new File(output(), "sfcompose/data/${it}.sfgz").exists()) }
        assertPayloads(second.entries[PUBLIC_ENTRY])
    }

    @Test
    void duplicateSymbolAcrossCatalogsIsRejected() {
        File duplicate = catalog(new File(temporary.newFolder('duplicate'), 'build-data'), PUBLIC_SYMBOL, [
            'com/nevoit/sfcompose/symbols/OtherSFSymbols'        : '',
            'com/nevoit/sfcompose/symbols/OtherSFSymbols$Public' : PUBLIC_SYMBOL,
        ], 'other-source')

        GradleException failure = assertThrows(GradleException,
            { runTask([publicArchive, privateArchive, duplicate], [PUBLIC_ENTRY]) } as ThrowingRunnable)

        assertTrue(failure.message.contains('Duplicate SFCompose symbol'))
        assertTrue(failure.message.contains(PUBLIC_SYMBOL))
    }

    private void runTask(List<File> archives, List<String> retain) {
        def project = ProjectBuilder.builder().withProjectDir(workspace).withName('consumer').build()
        def task = project.tasks.create('buildSymbolAssets', BuildSymbolAssets)
        task.buildData.from(archives)
        task.allJars.set(Collections.emptyList())
        task.allDirectories.set(Collections.emptyList())
        task.workspaceDirectory.fileValue(workspace)
        task.outputDirectory.fileValue(output())
        task.selectionReport.fileValue(reportFile())
        task.retain.set(retain as Set)
        task.generate()
    }

    private File output() {
        new File(workspace, 'build/generated/sfcompose')
    }

    private File reportFile() {
        new File(workspace, 'build/reports/sfcompose/selected.json')
    }

    private Map readReport() {
        new JsonSlurper().parseText(reportFile().getText('UTF-8')) as Map
    }

    private static Map recordOf(Map report, String entry, String asset) {
        Map found = report.entries[entry].find { it.asset == asset } as Map
        assertNotNull("No $asset record in $entry", found)
        found
    }

    private Map payload(Map selected) {
        File file = new File(output(), "sfcompose/data/${selected.sha256}.sfgz")
        new GZIPInputStream(new ByteArrayInputStream(file.bytes)).withCloseable { stream ->
            new JsonSlurper().parse(stream, 'UTF-8') as Map
        }
    }

    private void assertPayloads(Collection records) {
        records.each { Map selected ->
            File file = new File(output(), "sfcompose/data/${selected.sha256}.sfgz")
            assertTrue("Missing payload ${selected.sha256}", file.isFile())
            assertEquals(selected.bytes as long, file.length())
            assertEquals(selected.sha256, sha(file.bytes))
        }
    }

    private static void assertGlyph(Map selected, String symbol, String weight, boolean animated) {
        assertEquals([weight], selected.weights)
        assertEquals(['small', 'medium', 'large'], selected.scales)
        assertEquals(animated, selected.animated)
        assertEquals(symbol, selected.resourceName)
        assertEquals('default', selected.variantId)
        assertEquals('en', selected.locale)
        assertEquals('leftToRight', selected.direction)
        assertEquals('light', selected.appearance)
    }

    private static void assertSingleWeight(Map glyph, String weight) {
        assertEquals(['small', 'medium', 'large'], glyph.scales.keySet().toList())
        glyph.scales.each { scale, group ->
            assertEquals("$scale masters", 1, group.masters.size())
            assertEquals(weight, group.masters[0].weight)
            assertEquals(WEIGHTS.indexOf(weight) + 1, group.masters[0].nativeWeight)
        }
        assertEquals([weight] as Set, glyph.scales.values().collectMany { it.masters*.weight } as Set)
    }

    private static Set<String> hashes(Collection records) {
        records.collect { it.sha256 } as Set
    }

    private static File catalog(File folder, String symbol, Map<String, String> namespaces, String flavor) {
        Map<String, byte[]> payloads = new TreeMap<>()
        payloads['symbols/index.json'] = gzipJson([
            formatVersion: 1,
            weights: WEIGHTS,
            symbols: [symbol],
            packedFor: symbol,
            resourceSelectionAsset: 'symbols/selection.json',
            flavor: flavor,
        ])
        payloads['symbols/selection.json'] = gzipJson([packedFor: symbol, rightToLeftNames: [:], flippableVariants: []])
        payloads["symbols/${symbol}.json"] = gzipJson(glyph(symbol))

        Map<String, byte[]> byDigest = new TreeMap<>()
        payloads.each { path, bytes -> byDigest[sha(bytes)] = bytes }

        ByteArrayOutputStream binary = new ByteArrayOutputStream()
        Map<String, List<Number>> blobs = new LinkedHashMap<>()
        long offset = 0
        byDigest.each { digest, bytes ->
            blobs[digest] = [offset, bytes.length]
            binary.write(bytes)
            offset += bytes.length
        }

        Map<String, String> paths = new TreeMap<>()
        payloads.each { path, bytes -> paths[path] = sha(bytes) }

        Map index = [
            format: 'sfcompose-build-data-v1',
            symbols: [(symbol): paths],
            blobs: blobs,
            namespaces: namespaces,
            weights: WEIGHTS,
        ]
        File archive = new File(folder, 'build-data.zip')
        writeZip(archive, [
            'index.json.gz': gzipBytes(JsonOutput.toJson(index).getBytes('UTF-8')),
            'payloads.bin': binary.toByteArray(),
        ])
        archive
    }

    private static Map glyph(String symbol) {
        [
            name: symbol,
            systemVariantId: 'default',
            systemVariants: [[id: 'default', localeTag: 'en', direction: 'leftToRight', appearance: 'light']],
            systemAnchors: ['top-level'],
            clipStrokeKeyframes: ['top-level'],
            scales: [
                small: [masters: samples()],
                medium: [masters: samples()],
                large: [masters: samples()],
            ],
        ]
    }

    private static List samples() {
        (0..<WEIGHTS.size()).collect { int index ->
            String weight = WEIGHTS[index]
            [weight: weight, nativeWeight: index + 1,
             contours: [[verbs: ['M'], points: [[weight]]]],
             clipStrokeKeyframes: [weight],
             clipStrokeKeyframeSets: [[weight]],
             systemAnchors: [weight],
             '-sfsymbols-clipstroke-attachments': [weight]]
        }
    }

    private static byte[] gzipJson(Map value) {
        gzipBytes(JsonOutput.toJson(value).getBytes('UTF-8'))
    }

    private static byte[] gzipBytes(byte[] bytes) {
        def output = new ByteArrayOutputStream()
        new GZIPOutputStream(output).withCloseable { it.write(bytes) }
        output.toByteArray()
    }

    private static void writeZip(File file, Map<String, byte[]> entries) {
        file.parentFile.mkdirs()
        new ZipOutputStream(new FileOutputStream(file)).withCloseable { zip ->
            entries.each { name, bytes ->
                def entry = new ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = bytes.length
                entry.compressedSize = bytes.length
                def crc = new CRC32()
                crc.update(bytes)
                entry.crc = crc.value
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private static String sha(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
    }
}
