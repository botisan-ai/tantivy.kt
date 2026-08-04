package ai.botisan.tantivy

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
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
 * operations. Gating uses real latches inside the adapter — the gated encode runs
 * inside the locked section on a real IO thread, so the test thread can await and
 * release it deterministically (no delay()-based scheduling assumptions).
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
        entered.await()

        // index() is mid-encode inside its single lock hold; this add must
        // queue behind the whole add+commit section, not slip into its commit.
        val concurrentAdd = launch(Dispatchers.Default) { index.add(Note("pending", "uncommitted")) }
        assertFalse(concurrentAdd.isCompleted)

        release.countDown()
        compound.join()
        concurrentAdd.join()

        // Only index()'s own document is committed. Pre-fix, the FIFO mutex
        // deterministically let the concurrent add in between add and commit,
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
        entered.await()

        val closer = launch(Dispatchers.Default) { index.close() }
        assertFalse(closer.isCompleted) // blocked on the mutex while the operation runs

        release.countDown()
        inFlight.join()
        closer.join()

        // close() waited: the in-flight compound operation finished cleanly
        // against a live native index.
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
