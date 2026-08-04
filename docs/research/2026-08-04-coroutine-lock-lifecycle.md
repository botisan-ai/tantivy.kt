# Coroutine lock lifecycle in tantivy.kt PR #1

Date: 2026-08-04
Scope: Kotlin/JVM and the project-pinned `kotlinx-coroutines-core:1.10.2`.

## Bottom line

`Mutex.withLock` does not choose a dispatcher. In the PR's current nesting,

```kotlin
mutex.withLock {
    withContext(Dispatchers.IO) { nativeCall() }
}
```

the mutex is acquired in the calling coroutine's context, remains held during the IO work **and the dispatch back to the caller**, and is normally unlocked only after that return dispatch runs. This is safe for mutual exclusion, but it makes release depend on the caller's dispatcher being able to execute the returning continuation.

That dependency can form a real deadlock when synchronous `close()` calls default `runBlocking` on the same serial/thread-confined dispatcher needed by an in-flight holder to return and unlock. `runBlocking` does not automatically deadlock merely because it is invoked on a dispatcher thread, however: its default private event loop continues processing its own continuations, and a multithreaded dispatcher may have another worker available. The deadlock requires the circular dependency described below.

## `Mutex.withLock`: acquisition and release

- The 1.10.2 implementation is exactly `lock(owner); try { action() } finally { unlock(owner) }`; it contains no context or dispatcher switch. [`Mutex.kt`, lines 116–127](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/sync/Mutex.kt#L116-L127)
- If the mutex is contended, `lock` suspends the calling coroutine rather than blocking its thread. When its continuation runs again, it still has the caller's coroutine context; the dispatcher controls the actual resume thread. If uncontended, the fast path returns without suspending. [Official Mutex documentation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/lock.html), [`Mutex.kt`, lines 166–174](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/sync/Mutex.kt#L166-L174), [Kotlin concurrency guide](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html#mutual-exclusion)
- Normal release is the synchronous, non-suspending `unlock` in `finally`, wherever the calling continuation is executing after `action` returns or throws. Cancellation while suspended in `lock` is a separate race: `lock` has a prompt-cancellation guarantee and releases an acquisition that won just before cancellation, so `withLock` does not leak the lock even though its `action` never starts. [`Mutex.kt`, lines 41–65](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/sync/Mutex.kt#L41-L65)

Consequently, the two nestings have different release dependencies:

```text
mutex.withLock { withContext(IO) { work() } }
caller dispatcher: acquire ──► IO work ──► return to caller ──► unlock

withContext(IO) { mutex.withLock { work() } }
caller dispatcher ──► IO: acquire ──► work ──► unlock ──► return to caller
```

Putting acquisition inside `Dispatchers.IO` does not occupy an IO thread for the duration of contention: after `Mutex.lock` suspends, that thread is free. It does add an IO scheduling step before the waiter can join/acquire the lock.

## `withContext`: hops and prompt cancellation

For 1.10.2, `withContext` merges the supplied context with the current one and always checks that the **resulting** context is active before starting. If the `ContinuationInterceptor`/dispatcher differs, it dispatches the block to the new dispatcher and dispatches completion back to the original dispatcher. The return dispatch is prompt-cancellable: cancellation of the original context before it runs discards a successful result and resumes with `CancellationException`. [`Builders.common.kt`, lines 116–173](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Builders.common.kt#L116-L173), [official `withContext` API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html)

A dispatcher hop is not a guarantee of a different physical thread. In particular, `Dispatchers.IO` shares threads with `Dispatchers.Default` and may keep execution on the same thread on a best-effort basis. [`Dispatchers.kt`, lines 53–64](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/jvm/src/Dispatchers.kt#L53-L64)

If the dispatcher is unchanged, `withContext` uses an undispatched fast path: it adds no outbound or return dispatcher hop. The initial `ensureActive` check still occurs for the resulting context, but there is no prompt-cancellable **return dispatch**. `withContext(NonCancellable)` is the documented example where replacing the job without changing dispatcher prevents cancellation on both entry and exit. [`Builders.common.kt`, lines 136–167](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Builders.common.kt#L136-L167)

Applied to an outer `withLock`, either a value, an exception, or prompt cancellation must return through the original dispatcher before the surrounding `finally` can perform its normal `unlock`. The lock therefore covers the return hop as well as the native IO block.

## `runBlocking` on a dispatcher thread

On JVM, `runBlocking` always blocks the physical calling thread until its new coroutine completes. With no explicit dispatcher, it creates or reuses a thread-local event loop and `joinBlocking` repeatedly processes that event loop while waiting. Thus the thread is blocked from returning to its caller, but it is not simply asleep: the default `runBlocking` coroutine and its own continuations can make progress there. [`Builders.kt`, lines 12–70](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/jvm/src/Builders.kt#L12-L70), [`Builders.kt`, lines 73–110](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/jvm/src/Builders.kt#L73-L110)

If a dispatcher is explicitly supplied, the new coroutine runs on that dispatcher while the calling thread remains blocked. Calling `runBlocking` from a suspend function is officially discouraged because it prevents that thread from being released and can cause thread starvation; the new `runBlocking` coroutine does not inherit the ambient coroutine context unless it is explicitly passed. [Official `runBlocking` API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html)

### The PR-specific deadlock cycle (inference from the documented mechanics)

Assume `D` is a serial or thread-confined dispatcher:

```text
operation on D: acquire mutex ─► native work on IO ─► queued return to D ─► unlock
close() on D:    runBlocking ─► wait for the same mutex
```

Once `close()` enters `runBlocking`, `D`'s only thread cannot service the operation's queued return. The operation cannot reach `withLock`'s `finally` to unlock; `close()` cannot acquire the mutex and return. The private `runBlocking` event loop does not break this cycle because the holder's continuation is queued to `D`, not to the new `runBlocking` context.

This is conditional, not a blanket rule:

- A standalone `runBlocking { withContext(IO) { ... } }` can return because the IO result is dispatched to—and processed by—the `runBlocking` event loop.
- A dispatcher with spare workers may resume the holder elsewhere, so blocking one worker is not necessarily deadlock, though it still risks starvation.
- If acquisition and normal release both occur inside the IO context (`withContext(IO) { mutex.withLock { ... } }`), release happens before the return to `D`, removing this particular circular dependency. The caller still blocks synchronously until `close()` finishes.
