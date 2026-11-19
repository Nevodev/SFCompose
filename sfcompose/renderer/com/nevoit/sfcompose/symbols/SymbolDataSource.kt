package com.nevoit.sfcompose.symbols

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Base64
import java.util.Collections

/** Generated catalog data, referenced directly so Android code shrinking can remove unused symbols. */
fun interface SymbolDataSource {
    fun open(path: String): InputStream
}

object EncodedSymbolData {
    @JvmStatic
    fun open(chunks: Array<String>): InputStream = SequenceInputStream(Collections.enumeration(
        chunks.map { ByteArrayInputStream(Base64.getDecoder().decode(it)) },
    ))
}
