package com.nevoit.sfcompose.symbols

import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SymbolLoadCacheTest {
    @Test
    fun sameKeyWaitersShareWorkAndAnotherKeyDoesNotWaitForThem() = runBlocking {
        withTimeout(5_000) {
            val cache = SymbolLoadCache(this, LruCache<String, Any>(4))
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val expected = Any()
            val first = async { cache.get("xmark") { entered.complete(Unit); finish.await(); expected } }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("xmark") { error("Duplicate request") }
            }
            assertEquals("other", cache.get("circle") { "other" })
            first.cancelAndJoin()
            finish.complete(Unit)
            assertSame(expected, second.await())
            assertSame(expected, cache.get("xmark") { error("Cache miss") })
        }
    }
}
