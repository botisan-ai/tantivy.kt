package ai.botisan.tantivy

import ai.botisan.tantivy.ffi.DocumentField
import ai.botisan.tantivy.ffi.TantivyIndex
import ai.botisan.tantivy.ffi.TantivyIndexException
import kotlinx.coroutines.Dispatchers
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

/**
 * Typed index over the UniFFI [TantivyIndex] — port of the Swift
 * `TantivySwiftIndex` actor. Operations are serialized through a [Mutex] and
 * run on [Dispatchers.IO].
 */
public class TypedTantivyIndex<T> private constructor(
    private val index: TantivyIndex,
    public val schema: TantivySchema,
    private val adapter: TantivyDocumentAdapter<T>,
) : AutoCloseable {

    private val mutex = Mutex()

    public companion object {
        /** Opens (or creates) the index at [path] with [schema]. */
        public suspend fun <T> open(
            path: String,
            schema: TantivySchema,
            adapter: TantivyDocumentAdapter<T>,
        ): TypedTantivyIndex<T> = withContext(Dispatchers.IO) {
            TypedTantivyIndex(TantivyIndex.newWithSchema(path, schema.newBuilder()), schema, adapter)
        }
    }

    public suspend fun clear(): Unit = locked { index.clearIndex() }

    public suspend fun count(): Long = locked { index.docsCount().toLong() }

    /** Adds without committing. */
    public suspend fun add(doc: T): Unit = locked { index.indexDoc(encode(doc)) }

    public suspend fun addAll(docs: List<T>): Unit = locked { index.indexDocs(docs.map(::encode)) }

    /** Adds and commits. */
    public suspend fun index(doc: T) {
        add(doc)
        commit()
    }

    public suspend fun indexAll(docs: List<T>) {
        addAll(docs)
        commit()
    }

    public suspend fun commit(): Unit = locked { index.commit() }

    /** Deletes documents whose [field] equals [value]; commits internally (Rust behavior). */
    public suspend fun deleteDoc(field: String, value: TantivyValue): Unit =
        locked { index.deleteDoc(DocumentField(field, value.toFfi())) }

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
            val results = index.searchDsl(query.toJson(), limit.toUInt(), offset.toUInt())
            TantivySearchResults(
                count = results.count.toLong(),
                hits = results.docs.map { TantivySearchHit(it.score, adapter.decode(TantivyFieldMap(it.doc))) },
            )
        }

    /** Convenience for the common query-string search (BM25 over [defaultFields], optional fuzzy). */
    public suspend fun searchText(
        query: String,
        defaultFields: List<String> = schema.defaultTextFieldNames,
        fuzzyFields: List<TantivyQuery.FuzzyField> = emptyList(),
        limit: Int = 10,
        offset: Int = 0,
    ): TantivySearchResults<T> =
        search(TantivyQuery.QueryString(query, defaultFields, fuzzyFields), limit, offset)

    override fun close() {
        index.destroy()
    }

    private fun encode(doc: T) = TantivyDocumentWriter().also { adapter.encode(doc, it) }.build()

    private suspend fun <R> locked(block: () -> R): R =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }
}
