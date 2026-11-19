package com.nevoit.sfcompose.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.*
import org.gradle.api.provider.SetProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*

import java.security.MessageDigest
import java.util.zip.*

@CacheableTask
abstract class BuildSymbolAssets extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.NONE)
    abstract ConfigurableFileCollection getBuildData()
    @InputFiles @PathSensitive(PathSensitivity.NONE)
    abstract ListProperty<RegularFile> getAllJars()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    abstract ListProperty<Directory> getAllDirectories()
    @Input abstract SetProperty<String> getRetain()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()
    @OutputFile abstract RegularFileProperty getSelectionReport()
    @Internal abstract DirectoryProperty getWorkspaceDirectory()

    private static final Set ANIMATION_KEYS = ['systemAnchors', 'systemDynamicAnchorSource', 'systemAttachmentSource',
        'clipStrokeKeyframes', 'clipStrokeKeyframeSets', 'data-clipstroke-keyframes', '-sfsymbols-clipstroke-attachments'] as Set

    @TaskAction
    void generate() {
        def opened = []
        try {
            def namespaces = [:]
            def owners = [:]
            buildData.files.sort { it.path }.each { archiveFile ->
                def archive = new ZipFile(archiveFile)
                opened.add(archive)
                def index = archive.getInputStream(archive.getEntry('index.json.gz')).withCloseable {
                    new GZIPInputStream(it).withCloseable { compressed -> new JsonSlurper().parse(compressed, 'UTF-8') }
                }
                if (index.format != 'sfcompose-build-data-v1') throw new GradleException("Unsupported SFCompose build data: ${index.format}")
                def root = index.namespaces.keySet().find { !it.contains('$') }
                if (root == null) throw new GradleException("SFCompose build data has no root namespace")
                String catalogName = root.tokenize('/').last()
                index.namespaces.each { type, symbol ->
                    if (namespaces.containsKey(type)) throw new GradleException("Duplicate SFCompose namespace: $type")
                    namespaces[type] = symbol
                }
                index.symbols.each { symbol, paths ->
                    // Runtime manifest paths are symbol-based. Reject collisions rather than mixing two source catalogs.
                    if (owners.containsKey(symbol)) throw new GradleException("Duplicate SFCompose symbol across catalogs: $symbol")
                    owners[symbol] = [archive: archive, index: index, catalog: catalogName]
                }
            }
            def used = new SymbolReferenceScanner(namespaces as Map<String, String>).scan(
                allJars.get().collect { it.asFile }, allDirectories.get().collect { it.asFile })
            used.addAll(retain.get())
            File output = outputDirectory.get().asFile
            // Only this task owns its generated asset directory; stale variants must not survive a rebuild.
            if (!output.canonicalFile.toPath().startsWith(workspaceDirectory.get().asFile.canonicalFile.toPath())) {
                throw new GradleException("Generated SFCompose assets must stay inside the consumer workspace")
            }
            if (output.exists()) java.nio.file.Files.walk(output.toPath()).withCloseable { paths ->
                paths.filter { java.nio.file.Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                    java.nio.file.Files.isSymbolicLink(it) }.forEach { java.nio.file.Files.delete(it) }
            }
            output.mkdirs()
            def payloads = [:]
            def entries = [:]
            used.sort().each { entry ->
                def parts = entry.split(':')
                if (parts.length < 2 || parts.length > 3 || (parts.length == 3 && parts[2] != 'Animated')) {
                    throw new GradleException("Invalid retained SFCompose entry: $entry")
                }
                String name = parts[0], weight = parts[1]
                boolean animated = parts.length == 3
                def owner = owners[name]
                if (!owner) throw new GradleException("No build data for SFCompose symbol $name")
                def index = owner.index
                ZipFile archive = owner.archive
                def paths = index.symbols[name]
                if (!paths) throw new GradleException("No build data for SFCompose symbol $name")
                def weights = weight == 'DynamicWeight' ? index.weights : [weight]
                if (!index.weights.containsAll(weights)) throw new GradleException("No build data for SFCompose weight $weight")
                def files = [:]
                def records = []
                paths.each { path, digest ->
                    def range = index.blobs[digest]
                    byte[] source = archive.getInputStream(archive.getEntry('payloads.bin')).withCloseable { stream ->
                        stream.skipNBytes((range[0] as Number).longValue())
                        stream.readNBytes((range[1] as Number).intValue())
                    }
                    if (BuildSymbolAssets.sha(source) != digest) throw new GradleException("Corrupt SFCompose source $digest")
                    def document = source.length >= 2 && source[0] == (byte) 0x1f && source[1] == (byte) 0x8b ?
                        new GZIPInputStream(new ByteArrayInputStream(source)).withCloseable { stream -> new JsonSlurper().parse(stream, 'UTF-8') } : null
                    if (path == 'symbols/index.json') {
                        document.packedFor = entry
                        if (weight != 'DynamicWeight') document.packedWeight = weight
                        else document.remove('packedWeight')
                    }
                    if (document?.scales) {
                        document.remove('catalogEntry')
                        document.scales.each { scale, group ->
                            group.masters = group.masters.findAll { master ->
                                weights.any { label -> (master.nativeWeight as Number).intValue() == index.weights.indexOf(label) + 1 }
                            }
                            if (group.masters.size() != weights.size()) throw new GradleException("Missing $entry/$scale sample in $path")
                        }
                        if (!animated) document = BuildSymbolAssets.staticOnly(document)
                    }
                    byte[] bytes = document != null ? BuildSymbolAssets.gzip(JsonOutput.toJson(document).getBytes('UTF-8')) : source
                    String hash = BuildSymbolAssets.sha(bytes)
                    files[path] = hash
                    File target = new File(output, "sfcompose/data/${hash}.sfgz")
                    if (!target.exists()) { target.parentFile.mkdirs(); target.bytes = bytes }
                    payloads[hash] = bytes.length
                    def record = [asset: path, sha256: hash, bytes: bytes.length, symbol: name, catalog: owner.catalog]
                    if (document?.scales) {
                        def variant = document.systemVariants?.find { it.id == document.systemVariantId }
                        record += [weights: weights, scales: document.scales.keySet().toList(), animated: animated,
                            resourceName: document.name, variantId: document.systemVariantId, locale: variant?.localeTag,
                            direction: variant?.direction, appearance: variant?.appearance]
                    }
                    records.add(record)
                }
                String motion = animated ? 'animated' : 'static'
                File manifest = new File(output, "sfcompose/entries/$name/$weight/${motion}.json")
                manifest.parentFile.mkdirs()
                manifest.setText(JsonOutput.toJson([entry: entry, files: files, records: records]), 'UTF-8')
                entries[entry] = records
            }
            File report = selectionReport.get().asFile
            report.parentFile.mkdirs()
            report.setText(JsonOutput.prettyPrint(JsonOutput.toJson([format: 'sfcompose-selected-v1',
                entries: entries, symbolBytes: payloads.values().sum(0L)])) + '\n', 'UTF-8')
            logger.lifecycle("SFCompose: ${entries.size()} entries, ${payloads.size()} payloads, ${payloads.values().sum(0L)} bytes")
        } finally {
            opened.reverseEach { it.close() }
        }
    }

    private static Object staticOnly(Object value) {
        if (value instanceof Map) return value.findAll { key, item -> !ANIMATION_KEYS.contains(key) }
            .collectEntries { key, item -> [(key): BuildSymbolAssets.staticOnly(item)] }
        if (value instanceof List) return value.collect { BuildSymbolAssets.staticOnly(it) }
        value
    }

    private static byte[] gzip(byte[] bytes) {
        def output = new ByteArrayOutputStream()
        new GZIPOutputStream(output).withCloseable { it.write(bytes) }
        output.toByteArray()
    }

    private static String sha(byte[] bytes) { MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }
}
