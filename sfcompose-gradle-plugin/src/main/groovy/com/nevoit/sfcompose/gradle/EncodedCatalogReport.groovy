package com.nevoit.sfcompose.gradle

import groovy.json.JsonSlurper

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile

final class EncodedCatalogReport {
    static Map strings(ZipFile archive) {
        def strings = [:]
        archive.entries().findAll { it.name.endsWith('.dex') }.each { file ->
            byte[] bytes = archive.getInputStream(file).withCloseable { it.bytes }
            def dex = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            int count = dex.getInt(56)
            int offset = dex.getInt(60)
            for (int index = 0; index < count; index++) {
                int start = dex.getInt(offset + index * 4)
                int cursor = start
                while ((bytes[cursor] & 0x80) != 0) cursor++
                cursor++
                int end = cursor
                while (bytes[end] != 0) end++
                def hash = MessageDigest.getInstance('SHA-256').digest(Arrays.copyOfRange(bytes, cursor, end)).encodeHex().toString()
                if (!strings.containsKey(hash)) strings[hash] = []
                strings[hash].add([dex: file.name, bytes: end + 1 - start])
            }
        }
        strings
    }

    static Map inspect(File inventory, Map strings) {
        def entries = []
        def files = [:]
        def chunks = [] as Set
        def observedChunks = [] as Set
        inventory.withReader('UTF-8') { reader ->
            reader.readLine()
            String line
            while ((line = reader.readLine()) != null) {
                def entry = new JsonSlurper().parseText(line)
                entry.files.each { file ->
                    observedChunks.addAll(file.chunks.findAll { strings.containsKey(it) })
                }
                def marker = entry.files.find { it.entryMarker }
                if (marker.chunks.every { strings.containsKey(it) }) {
                    entries.add(entry.entry)
                    entry.files.each { file ->
                        if (!file.chunks.every { strings.containsKey(it) }) {
                            throw new IllegalStateException("Incomplete SFCompose data for ${entry.entry}: ${file.asset}")
                        }
                        chunks.addAll(file.chunks)
                        if (!files.containsKey(file.sha256)) {
                            files[file.sha256] = [bytes: file.bytes, dex: strings[file.chunks.first()]*.dex.unique(), matches: []]
                        }
                        files[file.sha256].matches.add([entry: entry.entry] + file.findAll { key, value -> key != 'chunks' })
                    }
                }
            }
        }
        if (observedChunks != chunks) {
            throw new IllegalStateException("Unexpected SFCompose payload chunks outside retained closures: ${(observedChunks - chunks).size()}")
        }
        def values = files.collect { hash, file -> [sha256: hash] + file }
        [entries: entries.sort(), files: values, symbolBytes: values.sum(0L) { it.bytes },
         symbolDexStringBytes: chunks.sum(0L) { chunk -> strings[chunk].sum(0L) { it.bytes } }]
    }
}
