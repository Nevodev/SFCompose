package com.nevoit.sfcompose.symbols

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Base64
import java.util.Collections

/**
 * Supplies imported catalog documents by logical path for [SymbolAssetStore.fromDataSource].
 *
 * Used by generated encoded catalogs so code shrinking can remove unused entries. The normal
 * build-plugin catalog uses packaged entry manifests instead; applications usually use generated
 * symbol accessors and [rememberSFSymbol] without implementing this interface.
 */
fun interface SymbolDataSource {
    /** Opens a fresh stream for [path], such as `symbols/index.json`; the loader closes it after reading. */
    fun open(path: String): InputStream
}

/** Stream adapter used by generated catalogs with Base64-encoded payload chunks. */
object EncodedSymbolData {
    /** Decodes each Base64 element and concatenates its bytes in array order into a new stream. */
    @JvmStatic
    fun open(chunks: Array<String>): InputStream = SequenceInputStream(Collections.enumeration(
        chunks.map { ByteArrayInputStream(Base64.getDecoder().decode(it)) },
    ))
}
