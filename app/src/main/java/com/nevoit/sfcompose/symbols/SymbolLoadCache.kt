package com.nevoit.sfcompose.symbols

import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive

internal class SymbolLoadCache<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val values: LruCache<K, V>,
) {
    private class Request<V>(val result: Deferred<V>, var waiters: Int = 0)

    private val lock = Any()
    private val requests = HashMap<K, Request<V>>()

    suspend fun get(key: K, load: suspend () -> V): V {
        val request = synchronized(lock) {
            values.get(key)?.let { return it }
            requests.getOrPut(key) {
                Request(scope.async(start = CoroutineStart.LAZY) {
                    val value = load()
                    synchronized(lock) {
                        ensureActive()
                        values.put(key, value)
                    }
                    value
                })
            }.also { it.waiters++ }
        }
        try {
            return request.result.await()
        } finally {
            synchronized(lock) {
                if (--request.waiters == 0 && requests[key] === request) {
                    requests.remove(key)
                    request.result.cancel()
                }
            }
        }
    }
}
