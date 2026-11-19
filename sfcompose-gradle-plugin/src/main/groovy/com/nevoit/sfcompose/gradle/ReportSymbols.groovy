package com.nevoit.sfcompose.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.security.MessageDigest
import java.util.zip.ZipFile

@CacheableTask
abstract class ReportSymbols extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.NONE)
    abstract ConfigurableFileCollection getInventoryFiles()

    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getApkDirectory()

    @OutputFile
    abstract RegularFileProperty getReportFile()

    @TaskAction
    void report() {
        def encoded = inventoryFiles.files.findAll { inventory ->
            inventory.withReader('UTF-8') { it.readLine().contains('jvm-symbols-v1') }
        }
        def records = (inventoryFiles.files - encoded).sort { it.name }.collectMany { inventory ->
            def roots = new JsonSlurper().parse(inventory, 'UTF-8') as Map
            roots.collectMany { entry, files -> files.collect { [entry: entry] + it } }
        }
        def byHash = records.groupBy { it.sha256 }
        def extensions = records.collect { ReportSymbols.extensionOf(it.file as String) }.toSet()
        def apks = apkDirectory.get().asFile.listFiles().findAll { it.name.endsWith('.apk') }.sort { it.name }
        if (apks.isEmpty()) throw new GradleException('No APKs to report for this variant')

        def result = [apks: apks.collect { apk ->
            def raw = ReportSymbols.inspect(apk, byHash, extensions)
            new ZipFile(apk).withCloseable { archive ->
                def strings = encoded.isEmpty() ? [:] : EncodedCatalogReport.strings(archive)
                encoded.each { inventory ->
                    def code = EncodedCatalogReport.inspect(inventory, strings)
                    raw.entries = (raw.entries + code.entries).sort()
                    raw.symbolBytes += code.symbolBytes
                    raw.symbolDexStringBytes = (raw.symbolDexStringBytes ?: 0L) + code.symbolDexStringBytes
                    raw.files.addAll(code.files)
                    code.files.each { file ->
                        file.matches.each { match ->
                            if (!raw.symbols.containsKey(match.symbol)) raw.symbols[match.symbol] = []
                            raw.symbols[match.symbol].add(match + [dex: file.dex])
                        }
                    }
                }
                def packed = PackagedCatalogReport.inspect(archive)
                raw.entries = (raw.entries + packed.entries).sort()
                raw.symbolBytes += packed.symbolBytes
                raw.symbolZipBytes += packed.symbolZipBytes
                raw.files.addAll(packed.files)
                packed.files.each { file ->
                    file.matches.each { match ->
                        if (!raw.symbols.containsKey(match.symbol)) raw.symbols[match.symbol] = []
                        raw.symbols[match.symbol].add(match + [apkPath: file.apkPath])
                    }
                }
            }
            raw
        }]
        def output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.setText(JsonOutput.prettyPrint(JsonOutput.toJson(result)) + '\n', 'UTF-8')
        result.apks.each { logger.lifecycle("SFCompose ${it.apk}: ${it.symbolBytes} resource bytes in ${it.apkBytes} APK bytes") }
    }

    private static Map inspect(File apk, Map byHash, Set extensions) {
        def retained = []
        def unmatched = []
        new ZipFile(apk).withCloseable { archive ->
            archive.entries().each { item ->
                if (!item.isDirectory() && item.name.startsWith('res/') &&
                    (extensions.contains(ReportSymbols.extensionOf(item.name)) || item.name.endsWith('.sfgz'))) {
                    def hash = archive.getInputStream(item).withCloseable { stream ->
                        MessageDigest.getInstance('SHA-256').digest(stream.bytes).encodeHex().toString()
                    }
                    def candidates = byHash[hash]
                    if (candidates) {
                        retained.add([apkPath: item.name, bytes: item.size, zipBytes: item.compressedSize, candidates: candidates])
                    } else if (item.name.endsWith('.sfgz')) {
                        unmatched.add(item.name)
                    }
                }
            }
        }
        def activeEntries = retained.collectMany { file ->
            file.candidates.findAll { it.entryMarker == true ||
                (!it.containsKey('entryMarker') && it.asset == 'symbols/index.json') }.collect { it.entry }
        }.toSet()
        def files = retained.collect { file ->
            def matches = file.candidates.findAll { activeEntries.contains(it.entry) }
            [apkPath: file.apkPath, bytes: file.bytes, zipBytes: file.zipBytes,
             matches: matches.collect { it.findAll { key, value -> key != 'sha256' } }]
        }.findAll { file ->
            if (file.matches.isEmpty() && file.apkPath.endsWith('.sfgz')) unmatched.add(file.apkPath)
            !file.matches.isEmpty()
        }.sort { it.apkPath }
        def symbols = files.collectMany { file ->
            file.matches.collect { match -> match + [apkPath: file.apkPath] }
        }.groupBy { it.symbol }.sort()
        [apk: apk.name, apkBytes: apk.length(), symbolBytes: files.sum(0L) { it.bytes },
         symbolZipBytes: files.sum(0L) { it.zipBytes }, entries: activeEntries.sort(),
         symbols: symbols, files: files, unmatchedSfgz: unmatched.sort()]
    }

    private static String extensionOf(String path) {
        path.substring(path.lastIndexOf('.') + 1)
    }
}
