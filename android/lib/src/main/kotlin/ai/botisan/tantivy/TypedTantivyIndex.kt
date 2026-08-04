package ai.botisan.tantivy

import ai.botisan.tantivy.ffi.DocumentField
import ai.botisan.tantivy.ffi.TantivyIndex
import ai.botisan.tantivy.ffi.TantivyIndexException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Maps app documents to/from index fields. The Kotlin replacement for the Swift `@TantivyDocument` macro. */
public interface TantivyDocumentAdapter<T> {
    public fun encode(value: T, doc: TantivyDocumentWriter)

    public fun decode(fields: TantivyFieldMap): T
}

public data class TantivySearchHit<T>(val score: Float, val doc: T)

public data class TantivySearchResults<T>(val count: Long, val hits: List<TantivySearchHit<T>>)

internal enum class TantivyLockBoundary { OPERATION, CLOSE }

/**
 * Typed index over the UniFFI [TantivyIndex] — port of the Swift
 * `TantivySwiftIndex` actor. Operations serialize through a [Mutex], with the
 * whole lock hold and native work on [Dispatchers.IO]. Acquiring and releasing
 * there keeps synchronous [close] from blocking a confined caller dispatcher
 * that an in-flight operation would otherwise need in order to unlock.
 *
 * Failure contract: adapter encoding problems throw [TantivyEncodingException]
 * before anything crosses the FFI; argument-contract violations throw
 * [IllegalArgumentException]; native failures throw the generated
 * `ai.botisan.tantivy.ffi.TantivyIndexException` (documented raw contract).
 */
public class TypedTantivyIndex<T> private constructor(
    private val index: TantivyIndex,
    public val schema: TantivySchema,
    private val adapter: TantivyDocumentAdapter<T>,
) : AutoCloseable {

    private val mutex = Mutex()

    @Volatile
    private var closed = false

    /** Positive lock-boundary signal for deterministic concurrency regressions. */
    @Volatile
    internal var lockBoundaryForTest: ((TantivyLockBoundary) -> Unit)? = null

    /** Pauses immediately after a successful operation unlock; test-only. */
    @Volatile
    internal var afterUnlockForTest: (() -> Unit)? = null

    public companion object {
        /** Opens (or creates) the index at [path] with [schema]. */
        public suspend fun <T> open(
            path: String,
            schema: TantivySchema,
            adapter: TantivyDocumentAdapter<T>,
        ): TypedTantivyIndex<T> = withContext(Dispatchers.IO) {
            TypedTantivyIndex(TantivyIndex.newWithSchema(path, schema.newBuilder()), schema, adapter)
        }

        public suspend fun <T> open(
            directory: File,
            schema: TantivySchema,
            adapter: TantivyDocumentAdapter<T>,
        ): TypedTantivyIndex<T> = open(directory.absolutePath, schema, adapter)
    }

    public suspend fun clear(): Unit = locked { index.clearIndex() }

    public suspend fun count(): Long = locked { index.docsCount().toLong() }

    /** Adds without committing. */
    public suspend fun add(doc: T): Unit = locked { addUnlocked(doc) }

    public suspend fun addAll(docs: List<T>): Unit = locked { addAllUnlocked(docs) }

    /**
     * Adds and commits under one lock hold — like the Swift actor method, no
     * concurrent caller's uncommitted add can slip between the two steps.
     */
    public suspend fun index(doc: T): Unit = locked {
        addUnlocked(doc)
        index.commit()
    }

    public suspend fun indexAll(docs: List<T>): Unit = locked {
        addAllUnlocked(docs)
        index.commit()
    }

    public suspend fun commit(): Unit = locked { index.commit() }

    private fun addUnlocked(doc: T) {
        index.indexDoc(encode(doc))
    }

    private fun addAllUnlocked(docs: List<T>) {
        index.indexDocs(docs.map(::encode))
    }

    /**
     * Deletes any existing document with [doc]'s id value, then adds [doc]
     * (uncommitted — call [commit]). Both steps run under one lock. [idField]
     * defaults to the schema's single `idField`; pass it explicitly when the
     * schema declares several. Note the delete commits pending changes first
     * (Rust core behavior).
     */
    public suspend fun upsert(doc: T, idField: String? = null): Unit = locked {
        val resolved = idField ?: requireNotNull(schema.idFieldNames.singleOrNull()) {
            "upsert requires exactly one idField in the schema (found ${schema.idFieldNames}) or an explicit idField argument"
        }
        require(resolved in schema.idFieldNames) { "'$resolved' is not an idField of the schema" }
        val writer = TantivyDocumentWriter(schema).also { adapter.encode(doc, it) }
        // Exactly one id value: zero can't be addressed, several would give the
        // document multiple identities and make later upserts/deletes ambiguous.
        val count = writer.valueCount(resolved)
        if (count == 0) throw TantivyEncodingException.MissingIdValue(resolved)
        if (count > 1) throw TantivyEncodingException.AmbiguousIdValue(resolved, count)
        val idValue = checkNotNull(writer.firstFfiValue(resolved))
        index.deleteDoc(DocumentField(resolved, idValue))
        index.indexDoc(writer.build())
    }

    /** Deletes documents whose [field] equals [value]; commits internally (Rust behavior). */
    public suspend fun deleteDoc(field: String, value: TantivyValue): Unit =
        locked { index.deleteDoc(DocumentField(field, value.toFfi())) }

    /**
     * Stages a delete without committing: documents already added — committed
     * or pending — whose [field] equals [value] are masked at the next
     * [commit], while documents added after this call are unaffected (Tantivy
     * applies deletes in operation order). Unlike [deleteDoc], nothing becomes
     * durable here, so a caller can roll back its own pending adds without
     * publishing anyone else's uncommitted work.
     */
    public suspend fun deleteDocWithoutCommit(field: String, value: TantivyValue): Unit =
        locked { index.deleteDocUncommitted(DocumentField(field, value.toFfi())) }

    public suspend fun docExists(field: String, value: TantivyValue): Boolean =
        locked { index.docExists(DocumentField(field, value.toFfi())) }

    /** Fetches one document or throws [TantivyIndexException] (including when absent). */
    public suspend fun getDoc(field: String, value: TantivyValue): T =
        locked { adapter.decode(TantivyFieldMap(index.getDoc(DocumentField(field, value.toFfi())))) }

    /** Like [getDoc] but maps the document-retrieval failure for a missing doc to null. */
    public suspend fun getDocOrNull(field: String, value: TantivyValue): T? = try {
        getDoc(field, value)
    } catch (_: TantivyIndexException.DocRetrievalException) {
        null
    }

    public suspend fun getDocs(ids: List<Pair<String, TantivyValue>>): List<T> = locked {
        index.getDocsByIds(ids.map { DocumentField(it.first, it.second.toFfi()) })
            .map { adapter.decode(TantivyFieldMap(it)) }
    }

    public suspend fun search(query: TantivyQuery, limit: Int = 10, offset: Int = 0): TantivySearchResults<T> =
        locked {
            require(limit > 0) { "limit must be positive (got $limit)" }
            require(offset >= 0) { "offset must be non-negative (got $offset)" }
            val results = index.searchDsl(query.toJson(), limit.toUInt(), offset.toUInt())
            TantivySearchResults(
                count = results.count.toLong(),
                hits = results.docs.map { TantivySearchHit(it.score, adapter.decode(TantivyFieldMap(it.doc))) },
            )
        }

    /**
     * Convenience for the common query-string search (BM25 over [defaultFields],
     * optional fuzzy). [filter] is ANDed with the text query when present.
     */
    public suspend fun searchText(
        query: String,
        defaultFields: List<String> = schema.defaultTextFieldNames,
        fuzzyFields: List<TantivyQuery.FuzzyField> = emptyList(),
        filter: TantivyQuery? = null,
        limit: Int = 10,
        offset: Int = 0,
    ): TantivySearchResults<T> {
        val text = TantivyQuery.QueryString(query, defaultFields, fuzzyFields)
        val combined = if (filter == null) {
            text
        } else {
            TantivyQuery.Boolean(
                listOf(
                    TantivyQuery.Clause(TantivyQuery.Occur.MUST, text),
                    TantivyQuery.Clause(TantivyQuery.Occur.MUST, filter),
                ),
            )
        }
        return search(combined, limit, offset)
    }

    /**
     * Takes the operation mutex (waiting out any in-flight operation), then
     * destroys the native index on [Dispatchers.IO]. Idempotent; afterwards
     * every operation throws [IllegalStateException].
     */
    override fun close() {
        runBlocking(Dispatchers.IO) {
            lockBoundaryForTest?.invoke(TantivyLockBoundary.CLOSE)
            mutex.withLock {
                if (!closed) {
                    closed = true
                    index.destroy()
                }
            }
        }
    }

    private fun encode(doc: T) = TantivyDocumentWriter(schema).also { adapter.encode(doc, it) }.build()

    // Keep acquisition, native work, and release in IO. If release required a
    // hop back to a confined caller dispatcher, synchronous close() on that
    // dispatcher could block the very continuation that must unlock the mutex.
    private suspend fun <R> locked(block: () -> R): R = withContext(Dispatchers.IO) {
        lockBoundaryForTest?.invoke(TantivyLockBoundary.OPERATION)
        val result = mutex.withLock {
            check(!closed) { "TypedTantivyIndex is closed" }
            block()
        }
        afterUnlockForTest?.invoke()
        result
    }
}
