package com.nevoit.sfcompose.gradle

import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.ZipFile

final class PackagedCatalogReport {
    static Map inspect(ZipFile archive) {
        def payloads = archive.entries().findAll { it.name.startsWith('assets/sfcompose/data/') && !it.directory }
            .collectEntries { [(it.name.tokenize('/').last().replace('.sfgz', '')): it] }
        def retained = [:]
        def entries = []
        archive.entries().findAll { it.name.startsWith('assets/sfcompose/entries/') && it.name.endsWith('.json') }.each { item ->
            def manifest = archive.getInputStream(item).withCloseable { new JsonSlurper().parse(it, 'UTF-8') }
            entries.add(manifest.entry)
            manifest.records.each { record ->
                String hash = record.sha256
                def file = payloads[hash]
                if (file == null || manifest.files[record.asset] != hash || file.size != record.bytes) {
                    throw new IllegalStateException("Incomplete SFCompose closure: ${manifest.entry}/${record.asset}")
                }
                if (!retained.containsKey(hash)) {
                    def actual = archive.getInputStream(file).withCloseable {
                        MessageDigest.getInstance('SHA-256').digest(it.bytes).encodeHex().toString()
                    }
                    if (actual != hash) throw new IllegalStateException("Corrupt SFCompose asset: ${file.name}")
                    retained[hash] = [apkPath: file.name, bytes: file.size, zipBytes: file.compressedSize, matches: []]
                }
                retained[hash].matches.add([entry: manifest.entry] + record)
            }
        }
        if (payloads.keySet() != retained.keySet()) throw new IllegalStateException("Orphan SFCompose payloads in APK")
        [entries: entries.sort(), files: retained.values().toList(), symbolBytes: retained.values().sum(0L) { it.bytes },
         symbolZipBytes: retained.values().sum(0L) { it.zipBytes }]
    }
}
