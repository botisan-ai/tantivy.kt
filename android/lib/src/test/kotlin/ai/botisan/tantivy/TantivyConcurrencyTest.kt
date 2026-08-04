package ai.botisan.tantivy

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Barrier regressions for the single-lock guarantees: compound [TypedTantivyIndex.index]
 * holds add+commit indivisibly, and [TypedTantivyIndex.close] waits out in-flight
 * operations. Test-only callbacks live at the production lock boundary, so the
 * tests observe positive events instead of inferring coroutine scheduling from
 * `Job.isCompleted` or from a latch outside [TypedTantivyIndex.close]:
 *
 * - an after-unlock seam deterministically pauses `index()` after its single
 *   lock hold. On the old split-lock implementation the same seam lands between
 *   add and commit, letting the concurrent add become observably committed;
 * - a close-boundary seam proves `close()` itself has entered before the test
 *   checks that an in-flight operation keeps the native index alive;
 * - a single-thread dispatcher reproduces the caller-dispatcher inversion that
 *   deadlocks when the mutex is released only after returning from IO.
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
    fun indexHoldsAddAndCommitAgainstAConcurrentAdd() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val index = TypedTantivyIndex.open(tempIndexPath("compound"), schema, GatedAdapter("gated", entered, release))

        val firstUnlock = CountDownLatch(1)
        val allowCompoundToReturn = CountDownLatch(1)
        val interceptNextUnlock = AtomicBoolean(true)
        index.afterUnlockForTest = {
            if (interceptNextUnlock.compareAndSet(true, false)) {
                firstUnlock.countDown()
                allowCompoundToReturn.await()
            }
        }

        val compound = async(Dispatchers.Default) { index.index(Note("gated", "committed by index()")) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(firstUnlock.await(10, TimeUnit.SECONDS))

            // The fixed implementation reaches this seam only after add+commit.
            // On the checkpoint's split-lock implementation it reaches the same
            // seam after add and before commit, so this add is committed by the
            // resumed compound call and the public assertion below turns red.
            async(Dispatchers.Default) { index.add(Note("pending", "uncommitted")) }.await()
        } finally {
            release.countDown()
            allowCompoundToReturn.countDown()
        }
        compound.await()
        index.afterUnlockForTest = null

        assertEquals(1L, index.search(TantivyQuery.All).count)
        index.commit()
        assertEquals(2L, index.search(TantivyQuery.All).count)
        index.close()
    }

    @Test
    fun closeWaitsForInFlightOperationAndPostCloseCallsFail() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val index = TypedTantivyIndex.open(tempIndexPath("close-race"), schema, GatedAdapter("gated", entered, release))

        val inFlightFailure = AtomicReference<Throwable?>()
        val inFlight = async(Dispatchers.Default) {
            try {
                index.index(Note("gated", "in flight"))
            } catch (t: Throwable) {
                inFlightFailure.set(t)
            }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        val closeArrived = CountDownLatch(1)
        val allowCloseAttempt = CountDownLatch(1)
        val closerDone = CountDownLatch(1)
        index.lockBoundaryForTest = { boundary ->
            if (boundary == TantivyLockBoundary.CLOSE) {
                closeArrived.countDown()
                allowCloseAttempt.await()
            }
        }
        val closer = Thread {
            index.close()
            closerDone.countDown()
        }.apply { isDaemon = true }
        try {
            closer.start()
            assertTrue(closeArrived.await(10, TimeUnit.SECONDS))
            allowCloseAttempt.countDown()
            assertFalse(
                "close() returned while an operation held the mutex",
                closerDone.await(1, TimeUnit.SECONDS),
            )
        } finally {
            allowCloseAttempt.countDown()
            release.countDown()
        }
        inFlight.await()
        closer.join()
        index.lockBoundaryForTest = null

        assertNull(inFlightFailure.get())

        try {
            index.count()
            fail("expected IllegalStateException after close")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("closed"))
        }
        index.close() // idempotent
    }

    @Test
    fun closeDoesNotDeadlockTheCallerDispatcherNeededByAnInFlightOperation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val index = runBlocking {
            TypedTantivyIndex.open(
                tempIndexPath("same-dispatcher-close"),
                schema,
                GatedAdapter("gated", entered, release),
            )
        }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tantivy-single-caller").apply { isDaemon = true }
        }
        val callerDispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + callerDispatcher)
        val inFlight = scope.async { index.index(Note("gated", "in flight")) }
        val closeArrived = CountDownLatch(1)
        index.lockBoundaryForTest = { boundary ->
            if (boundary == TantivyLockBoundary.CLOSE) closeArrived.countDown()
        }

        var closeFuture: Future<*>? = null
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            // The in-flight coroutine is now suspended in IO, leaving its
            // single caller thread free to enter synchronous close().
            val closeTask = executor.submit { index.close() }
            closeFuture = closeTask
            assertTrue(closeArrived.await(10, TimeUnit.SECONDS))
            release.countDown()
            // If unlocking requires dispatching the operation back to this
            // blocked executor first, this bounded wait exposes the cycle.
            closeTask.get(5, TimeUnit.SECONDS)

            runBlocking { withTimeout(5_000) { inFlight.await() } }
            assertTrue(closeTask.isDone)
        } finally {
            release.countDown()
            index.lockBoundaryForTest = null
            closeFuture?.cancel(true)
            scope.cancel()
            callerDispatcher.close()
            executor.shutdownNow()
        }
    }
}
