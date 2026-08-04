package ai.botisan.tantivy

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Barrier regressions for the single-lock guarantees: compound [TypedTantivyIndex.index]
 * holds add+commit indivisibly, and [TypedTantivyIndex.close] waits out in-flight
 * operations. The gated encode runs inside the locked section on a real IO thread,
 * so the test thread can hold the lock open indefinitely; each contender is then
 * synchronized to the point of no return *before* the gate opens:
 *
 * - the concurrent add runs on the test's single-threaded scheduler, where its
 *   only suspension point before acquiring is `mutex.lock()` itself — when
 *   `runCurrent()` hands control back, it has provably suspended in the lock
 *   queue (the mutex is held the whole time);
 * - the closer signals immediately before calling `close()`, and the test then
 *   requires `close()` not to return while the lock is held — impossible on the
 *   fixed implementation, immediate on one that closes without the mutex.
 */
class TantivyConcurrencyTest {

    private data class Note(val id: String, val text: String)

    private val schema = tantivySchema {
        idField("id")
        textField("text")
    }

    private class GatedAdapter(
        private val gatedId: String,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : TantivyDocumentAdapter<Note> {
        override fun encode(value: Note, doc: TantivyDocumentWriter) {
            if (value.id == gatedId) {
                entered.countDown()
                release.await()
            }
            doc.text("id", value.id)
            doc.text("text", value.text)
        }

        override fun decode(fields: TantivyFieldMap): Note =
            Note(fields.text("id")!!, fields.text("text")!!)
    }

    private fun tempIndexPath(name: String): String =
        Files.createTempDirectory("tantivy-$name").toAbsolutePath().toString()

    @Test
    fun indexHoldsAddAndCommitAgainstAConcurrentAdd() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val index = TypedTantivyIndex.open(tempIndexPath("compound"), schema, GatedAdapter("gated", entered, release))

        val compound = launch(Dispatchers.Default) { index.index(Note("gated", "committed by index()")) }
        // Once the gated encode has entered, index() holds the mutex and its
        // block is parked on an IO thread — the lock stays held until released.
        entered.await()

        // Deterministic barrier: this contender inherits the test's
        // single-threaded scheduler, and the first suspension point on its path
        // is mutex.lock(). runCurrent() runs it on this very thread until it
        // suspends — which, with the mutex provably held above, can only be
        // inside the lock queue. No released gate can beat an unstarted caller.
        val concurrentAdd = launch { index.add(Note("pending", "uncommitted")) }
        try {
            testScheduler.runCurrent()
            assertFalse(compound.isCompleted)
            assertFalse(concurrentAdd.isCompleted)
        } finally {
            // Also on assertion failure — a parked encode must not hang runTest.
            release.countDown()
        }
        compound.join()
        concurrentAdd.join()

        // Only index()'s own document is committed. With split add/commit lock
        // holds, the FIFO mutex hands the queued add the lock between them,
        // making this 2.
        assertEquals(1L, index.search(TantivyQuery.All).count)
        index.commit()
        assertEquals(2L, index.search(TantivyQuery.All).count)
        index.close()
    }

    @Test
    fun closeWaitsForInFlightOperationAndPostCloseCallsFail() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val index = TypedTantivyIndex.open(tempIndexPath("close-race"), schema, GatedAdapter("gated", entered, release))

        var inFlightFailure: Throwable? = null
        val inFlight = launch(Dispatchers.Default) {
            try {
                index.index(Note("gated", "in flight"))
            } catch (t: Throwable) {
                inFlightFailure = t
            }
        }
        entered.await() // the operation holds the mutex; its encode is parked on an IO thread

        val closerStarted = CountDownLatch(1)
        val closerDone = CountDownLatch(1)
        val closer = Thread {
            closerStarted.countDown()
            index.close()
            closerDone.countDown()
        }
        try {
            closer.start()
            closerStarted.await()
            // With the mutex held, close() cannot return — on the fixed
            // implementation this await can only run out its full second (that
            // second is the test's cost, not a scheduling assumption). An
            // implementation that closes without taking the mutex returns
            // immediately and trips this assertion deterministically.
            assertFalse(
                "close() returned while an operation held the mutex",
                closerDone.await(1, TimeUnit.SECONDS),
            )
        } finally {
            // Also on assertion failure — a parked encode must not hang runTest.
            release.countDown()
        }
        inFlight.join()
        closer.join()

        // close() waited: the in-flight compound operation finished cleanly
        // against a live native index. Racing destroy() instead makes the
        // operation throw the FFI's use-after-destroy failure.
        assertNull(inFlightFailure)

        try {
            index.count()
            fail("expected IllegalStateException after close")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("closed"))
        }
        index.close() // idempotent
    }
}
