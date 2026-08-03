package ai.botisan.tantivy

import ai.botisan.tantivy.ffi.TantivyDocumentFields
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of `Tests/TantivySwiftTests/TantivySwiftTests.swift` from tantivy.swift.
 *
 * The Swift suite declares documents with the `@TantivyDocument` macro plus
 * property wrappers; here the same schemas are declared with [tantivySchema] and
 * the same field mapping with a [TantivyDocumentAdapter]. Field options mirror
 * the Swift property-wrapper defaults exactly (see the notes on each schema).
 */
class TantivyIndexTest {

    // ---------------------------------------------------------------- helpers

    private fun tempIndexPath(name: String): String =
        Files.createTempDirectory("tantivy-$name").toAbsolutePath().toString()

    private fun <T> encodeFields(adapter: TantivyDocumentAdapter<T>, value: T): TantivyDocumentFields =
        TantivyDocumentWriter().also { adapter.encode(value, it) }.build()

    private fun TantivyDocumentFields.countOf(name: String): Int = fields.count { it.name == name }

    private fun assertBytesEqual(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.map(ByteArray::toList), actual.map(ByteArray::toList))
    }

    // ------------------------------------------------- schemaTemplateAndCodingKeys

    /** Swift: `schemaTemplateAndCodingKeys`. */
    @Test
    fun schemaTemplateAndFieldNames() {
        val template = UnifiedDocAdapter.decode(TantivyFieldMap(TantivyDocumentFields(emptyList())))
        assertEquals("", template.id)
        assertEquals("", template.title)
        assertEquals("", template.body)
        assertEquals(0.0, template.score, 0.0)
        assertFalse(template.isActive)
        assertEquals("", template.category)
        assertEquals("", template.meta.source)

        val doc = UnifiedDoc(
            id = "sample",
            title = "Title",
            body = "Body",
            score = 1.0,
            isActive = true,
            category = "/sample",
            meta = ArticleMeta(source = "kotlin", rating = 1),
        )
        val fields = encodeFields(UnifiedDocAdapter, doc)
        assertEquals(
            listOf("id", "title", "body", "score", "isActive", "category", "meta"),
            fields.fields.map { it.name },
        )
        assertEquals(listOf("id"), UNIFIED_SCHEMA.idFieldNames)
        assertEquals(listOf("title", "body"), UNIFIED_SCHEMA.defaultTextFieldNames)
        assertEquals(doc, UnifiedDocAdapter.decode(TantivyFieldMap(fields)))
    }

    // ---------------------------------------------------------- indexLifecycle

    /** Swift: `indexLifecycle`. */
    @Test
    fun indexLifecycle() = runTest {
        openUnifiedIndex("unified_index_lifecycle").use { index ->
            index.clear()

            val doc = UnifiedDoc(
                id = "1",
                title = "Swift and Rust",
                body = "Exploring search indexes",
                score = 9.5,
                isActive = true,
                category = "/tech",
                meta = ArticleMeta(source = "swift", rating = 5),
            )

            index.add(doc)
            index.commit()

            assertEquals(1L, index.count())

            val id = TantivyValue.Text("1")
            assertTrue(index.docExists("id", id))
            assertEquals("Swift and Rust", index.getDoc("id", id).title)

            index.deleteDoc("id", id)
            assertFalse(index.docExists("id", id))
            assertEquals(0L, index.count())
        }
    }

    // ------------------------------------------------------ bulkIndexAndGetDocs

    /** Swift: `bulkIndexAndGetDocs`. */
    @Test
    fun bulkIndexAndGetDocs() = runTest {
        openUnifiedIndex("unified_index_bulk").use { index ->
            index.clear()

            val docs = listOf(
                UnifiedDoc("a1", "Alpha", "First letter", 1.0, true, "/letters", ArticleMeta("alpha", 1)),
                UnifiedDoc("b2", "Beta", "Second letter", 2.0, false, "/letters", ArticleMeta("beta", 2)),
                UnifiedDoc("c3", "Charlie", "Third letter", 3.0, true, "/letters", ArticleMeta("charlie", 3)),
            )

            index.addAll(docs)
            index.commit()
            assertEquals(3L, index.count())

            index.index(UnifiedDoc("d4", "Delta", "Fourth letter", 4.0, true, "/letters", ArticleMeta("delta", 4)))
            assertEquals(4L, index.count())

            index.indexAll(
                listOf(UnifiedDoc("e5", "Echo", "Fifth letter", 5.0, false, "/letters", ArticleMeta("echo", 5))),
            )
            assertEquals(5L, index.count())

            val retrieved = index.getDocs(
                listOf("id" to TantivyValue.Text("a1"), "id" to TantivyValue.Text("b2")),
            )
            assertEquals(2, retrieved.size)
        }
    }

    // ----------------------------------------------------------- searchQueries

    /** Swift: `searchQueries`. */
    @Test
    fun searchQueries() = runTest {
        openUnifiedIndex("unified_index_search").use { index ->
            index.clear()

            val doc1 = UnifiedDoc(
                id = "1",
                title = "Swift and Rust",
                body = "Exploring search indexes",
                score = 10.0,
                isActive = true,
                category = "/tech",
                meta = ArticleMeta(source = "swift", rating = 5),
            )
            val doc2 = UnifiedDoc(
                id = "2",
                title = "Cooking Pasta",
                body = "Simple recipes",
                score = 6.0,
                isActive = false,
                category = "/food",
                meta = ArticleMeta(source = "kitchen", rating = 4),
            )

            index.indexAll(listOf(doc1, doc2))

            val textQuery = TantivyQuery.QueryString(query = "swift", defaultFields = listOf("title", "body"))
            val facetQuery = TantivyQuery.Term("category", TantivyValue.Facet("/tech"))
            val combined = TantivyQuery.Boolean(
                listOf(
                    TantivyQuery.Clause(TantivyQuery.Occur.MUST, textQuery),
                    TantivyQuery.Clause(TantivyQuery.Occur.MUST, facetQuery),
                ),
            )

            val results = index.search(combined, limit = 10, offset = 0)
            assertEquals(1L, results.count)
            assertEquals("1", results.hits.first().doc.id)

            val results2 = index.searchText("pasta", defaultFields = listOf("title", "body"))
            assertEquals(1L, results2.count)
            assertEquals("2", results2.hits.first().doc.id)
        }
    }

    // --------------------------------------------- multiValueTextAndFacetFields

    /** Swift: `multiValueTextAndFacetFields`. */
    @Test
    fun multiValueTextAndFacetFields() = runTest {
        val doc = MultiValueDoc(
            id = "receipt-1",
            tags = listOf("groceries", "weekly"),
            receiptTagIds = listOf("/receipt/tags/groceries", "/receipt/tags/home"),
            note = "Weekly grocery run",
        )

        val native = encodeFields(MultiValueDocAdapter, doc)
        assertEquals(2, native.countOf("tags"))
        assertEquals(2, native.countOf("receiptTagIds"))

        val mapped = TantivyFieldMap(native)
        assertEquals(doc.tags.toSet(), mapped.texts("tags").toSet())
        assertEquals(doc.receiptTagIds.toSet(), mapped.facets("receiptTagIds").toSet())

        val rebuilt = MultiValueDocAdapter.decode(mapped)
        assertEquals(doc.tags.toSet(), rebuilt.tags.toSet())
        assertEquals(doc.receiptTagIds.toSet(), rebuilt.receiptTagIds.toSet())

        openMultiValueIndex("multivalue_text_facet").use { index ->
            index.clear()
            index.index(doc)

            val retrieved = index.getDoc("id", TantivyValue.Text("receipt-1"))
            assertEquals("receipt-1", retrieved.id)
            assertEquals(doc.tags.toSet(), retrieved.tags.toSet())
            assertEquals(doc.receiptTagIds.toSet(), retrieved.receiptTagIds.toSet())

            val tagsResults = index.search(
                TantivyQuery.Term("tags", TantivyValue.Text("weekly")),
                limit = 10,
                offset = 0,
            )
            assertEquals(1L, tagsResults.count)
            assertEquals("receipt-1", tagsResults.hits.first().doc.id)

            val facetResults = index.search(
                TantivyQuery.Term("receiptTagIds", TantivyValue.Facet("/receipt/tags/home")),
                limit = 10,
                offset = 0,
            )
            assertEquals(1L, facetResults.count)
            assertEquals("receipt-1", facetResults.hits.first().doc.id)
        }
    }

    // ------------------------------------------ multiValueAllSupportedFieldTypes

    /** Swift: `multiValueAllSupportedFieldTypes`. */
    @Test
    fun multiValueAllSupportedFieldTypes() = runTest {
        // Swift used Date(timeIntervalSince1970: 1_700_000_000.123456); Kotlin
        // carries the same instants as exact microsecond timestamps.
        val nowMicros = 1_700_000_000_123_456L
        val laterMicros = 1_700_000_100_654_321L

        val doc = MultiValueAllDoc(
            id = "multi-1",
            tags = listOf("groceries", "household"),
            amounts = listOf(12L, 42L),
            deltas = listOf(-4L, 7L),
            scores = listOf(0.75, 0.95),
            flags = listOf(true, false),
            timesMicros = listOf(nowMicros, laterMicros),
            blobs = listOf(byteArrayOf(0xCA.toByte(), 0xFE.toByte()), byteArrayOf(0xBA.toByte(), 0xBE.toByte())),
            receiptTagIds = listOf("/receipt/tags/groceries", "/receipt/tags/home"),
            metas = listOf(ArticleMeta("ocr", 5), ArticleMeta("manual", 4)),
        )

        val native = encodeFields(MultiValueAllDocAdapter, doc)
        assertEquals(doc.tags.size, native.countOf("tags"))
        assertEquals(doc.amounts.size, native.countOf("amounts"))
        assertEquals(doc.deltas.size, native.countOf("deltas"))
        assertEquals(doc.scores.size, native.countOf("scores"))
        assertEquals(doc.flags.size, native.countOf("flags"))
        assertEquals(doc.timesMicros.size, native.countOf("times"))
        assertEquals(doc.blobs.size, native.countOf("blobs"))
        assertEquals(doc.receiptTagIds.size, native.countOf("receiptTagIds"))
        assertEquals(doc.metas.size, native.countOf("metas"))

        val mapped = TantivyFieldMap(native)
        assertEquals(doc.tags.toSet(), mapped.texts("tags").toSet())
        assertEquals(doc.amounts, mapped.u64s("amounts"))
        assertEquals(doc.deltas, mapped.i64s("deltas"))
        assertEquals(doc.scores, mapped.f64s("scores"))
        assertEquals(doc.flags, mapped.bools("flags"))
        assertBytesEqual(doc.blobs, mapped.bytesValues("blobs"))
        assertEquals(doc.receiptTagIds.toSet(), mapped.facets("receiptTagIds").toSet())
        assertEquals(doc.timesMicros, mapped.datesMicros("times"))
        assertEquals(doc.metas, mapped.jsons("metas").map(ArticleMeta::fromJson))

        val rebuilt = MultiValueAllDocAdapter.decode(mapped)
        assertEquals(doc.tags.toSet(), rebuilt.tags.toSet())
        assertEquals(doc.amounts, rebuilt.amounts)
        assertEquals(doc.deltas, rebuilt.deltas)
        assertEquals(doc.scores, rebuilt.scores)
        assertEquals(doc.flags, rebuilt.flags)
        assertBytesEqual(doc.blobs, rebuilt.blobs)
        assertEquals(doc.receiptTagIds.toSet(), rebuilt.receiptTagIds.toSet())
        assertEquals(doc.metas, rebuilt.metas)
        assertEquals(doc.timesMicros, rebuilt.timesMicros)

        openMultiValueAllIndex("multivalue_all_fields").use { index ->
            index.clear()
            index.index(doc)

            val retrieved = index.getDoc("id", TantivyValue.Text(doc.id))
            assertEquals(doc.id, retrieved.id)
            assertEquals(doc.amounts, retrieved.amounts)
            assertEquals(doc.deltas, retrieved.deltas)
            assertEquals(doc.scores, retrieved.scores)
            assertEquals(doc.flags, retrieved.flags)
            assertBytesEqual(doc.blobs, retrieved.blobs)
            assertEquals(doc.metas, retrieved.metas)

            assertEquals(
                1L,
                index.search(TantivyQuery.Term("tags", TantivyValue.Text("groceries")), 10, 0).count,
            )
            assertEquals(
                1L,
                index.search(TantivyQuery.Term("amounts", TantivyValue.U64(42)), 10, 0).count,
            )
            assertEquals(
                1L,
                index.search(TantivyQuery.Term("deltas", TantivyValue.I64(-4)), 10, 0).count,
            )
            assertEquals(
                1L,
                index.search(TantivyQuery.Term("scores", TantivyValue.F64(0.95)), 10, 0).count,
            )
            assertEquals(
                1L,
                index.search(TantivyQuery.Term("flags", TantivyValue.Bool(true)), 10, 0).count,
            )
            assertEquals(
                1L,
                index.search(
                    TantivyQuery.Term("blobs", TantivyValue.Bytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))),
                    10,
                    0,
                ).count,
            )
            assertEquals(
                1L,
                index.search(
                    TantivyQuery.Term("receiptTagIds", TantivyValue.Facet("/receipt/tags/home")),
                    10,
                    0,
                ).count,
            )
        }
    }

    // --------------------------------------------------------- index factories

    private suspend fun openUnifiedIndex(name: String) =
        TypedTantivyIndex.open(tempIndexPath(name), UNIFIED_SCHEMA, UnifiedDocAdapter)

    private suspend fun openMultiValueIndex(name: String) =
        TypedTantivyIndex.open(tempIndexPath(name), MULTI_VALUE_SCHEMA, MultiValueDocAdapter)

    private suspend fun openMultiValueAllIndex(name: String) =
        TypedTantivyIndex.open(tempIndexPath(name), MULTI_VALUE_ALL_SCHEMA, MultiValueAllDocAdapter)
}

// ============================================================================
// Documents, schemas and adapters — the Kotlin stand-in for @TantivyDocument
// ============================================================================

/** Swift `ArticleMeta`, carried through a JSON field. */
data class ArticleMeta(val source: String = "", val rating: Int = 0) {
    fun toJson(): String = buildJsonObject {
        put("source", source)
        put("rating", rating)
    }.toString()

    companion object {
        fun fromJson(json: String): ArticleMeta {
            val obj = Json.parseToJsonElement(json).jsonObject
            return ArticleMeta(
                source = obj.getValue("source").jsonPrimitive.content,
                rating = obj.getValue("rating").jsonPrimitive.int,
            )
        }
    }
}

/** Swift `UnifiedDoc`. */
data class UnifiedDoc(
    val id: String,
    val title: String,
    val body: String,
    val score: Double,
    val isActive: Boolean,
    val category: String,
    val meta: ArticleMeta,
)

/** `@IDField`, `@TextField`, `@F64Field`, `@BoolField`, `@FacetField`, `@JsonField` defaults. */
val UNIFIED_SCHEMA: TantivySchema = tantivySchema {
    idField("id")
    textField("title")
    textField("body")
    f64Field("score")
    boolField("isActive")
    facetField("category")
    // Swift's @JsonField defaults to indexed = false with the unicode tokenizer.
    jsonField("meta", indexed = false, tokenizer = Tokenizer.UNICODE)
}

object UnifiedDocAdapter : TantivyDocumentAdapter<UnifiedDoc> {
    override fun encode(value: UnifiedDoc, doc: TantivyDocumentWriter) {
        doc.text("id", value.id)
        doc.text("title", value.title)
        doc.text("body", value.body)
        doc.f64("score", value.score)
        doc.bool("isActive", value.isActive)
        doc.facet("category", value.category)
        doc.json("meta", value.meta.toJson())
    }

    override fun decode(fields: TantivyFieldMap): UnifiedDoc = UnifiedDoc(
        id = fields.text("id").orEmpty(),
        title = fields.text("title").orEmpty(),
        body = fields.text("body").orEmpty(),
        score = fields.f64("score") ?: 0.0,
        isActive = fields.bool("isActive") ?: false,
        category = fields.facet("category").orEmpty(),
        meta = fields.json("meta")?.let(ArticleMeta::fromJson) ?: ArticleMeta(),
    )
}

/** Swift `MultiValueDoc`. */
data class MultiValueDoc(
    val id: String,
    val tags: List<String>,
    val receiptTagIds: List<String>,
    val note: String,
)

val MULTI_VALUE_SCHEMA: TantivySchema = tantivySchema {
    idField("id")
    textField(
        "tags",
        tokenizer = Tokenizer.RAW,
        record = RecordOption.BASIC,
        stored = true,
        fast = false,
        fieldnorms = true,
    )
    facetField("receiptTagIds", stored = true)
    textField("note")
}

object MultiValueDocAdapter : TantivyDocumentAdapter<MultiValueDoc> {
    override fun encode(value: MultiValueDoc, doc: TantivyDocumentWriter) {
        doc.text("id", value.id)
        value.tags.forEach { doc.text("tags", it) }
        value.receiptTagIds.forEach { doc.facet("receiptTagIds", it) }
        doc.text("note", value.note)
    }

    override fun decode(fields: TantivyFieldMap): MultiValueDoc = MultiValueDoc(
        id = fields.text("id").orEmpty(),
        tags = fields.texts("tags"),
        receiptTagIds = fields.facets("receiptTagIds"),
        note = fields.text("note").orEmpty(),
    )
}

/** Swift `MultiValueAllDoc` — every supported field type, all multi-valued. */
data class MultiValueAllDoc(
    val id: String,
    val tags: List<String>,
    val amounts: List<Long>,
    val deltas: List<Long>,
    val scores: List<Double>,
    val flags: List<Boolean>,
    val timesMicros: List<Long>,
    val blobs: List<ByteArray>,
    val receiptTagIds: List<String>,
    val metas: List<ArticleMeta>,
)

val MULTI_VALUE_ALL_SCHEMA: TantivySchema = tantivySchema {
    idField("id")
    textField(
        "tags",
        tokenizer = Tokenizer.RAW,
        record = RecordOption.BASIC,
        stored = true,
        fast = false,
        fieldnorms = true,
    )
    u64Field("amounts", indexed = true, stored = true, fast = true, fieldnorms = false)
    i64Field("deltas", indexed = true, stored = true, fast = true, fieldnorms = false)
    f64Field("scores", indexed = true, stored = true, fast = true, fieldnorms = false)
    boolField("flags", indexed = true, stored = true, fast = true, fieldnorms = false)
    dateField(
        "times",
        indexed = true,
        stored = true,
        fast = true,
        fieldnorms = false,
        precision = DatePrecision.MICROSECONDS,
    )
    bytesField("blobs", stored = true, fast = false, indexed = true)
    facetField("receiptTagIds", stored = true)
    jsonField("metas", stored = true, indexed = false, fast = false, tokenizer = Tokenizer.UNICODE)
}

object MultiValueAllDocAdapter : TantivyDocumentAdapter<MultiValueAllDoc> {
    override fun encode(value: MultiValueAllDoc, doc: TantivyDocumentWriter) {
        doc.text("id", value.id)
        value.tags.forEach { doc.text("tags", it) }
        value.amounts.forEach { doc.u64("amounts", it) }
        value.deltas.forEach { doc.i64("deltas", it) }
        value.scores.forEach { doc.f64("scores", it) }
        value.flags.forEach { doc.bool("flags", it) }
        value.timesMicros.forEach { doc.dateMicros("times", it) }
        value.blobs.forEach { doc.bytes("blobs", it) }
        value.receiptTagIds.forEach { doc.facet("receiptTagIds", it) }
        value.metas.forEach { doc.json("metas", it.toJson()) }
    }

    override fun decode(fields: TantivyFieldMap): MultiValueAllDoc = MultiValueAllDoc(
        id = fields.text("id").orEmpty(),
        tags = fields.texts("tags"),
        amounts = fields.u64s("amounts"),
        deltas = fields.i64s("deltas"),
        scores = fields.f64s("scores"),
        flags = fields.bools("flags"),
        timesMicros = fields.datesMicros("times"),
        blobs = fields.bytesValues("blobs"),
        receiptTagIds = fields.facets("receiptTagIds"),
        metas = fields.jsons("metas").map(ArticleMeta::fromJson),
    )
}

// ============================================================================
// Multi-value read accessors.
//
// Swift's TantivyDocumentFieldMap exposes u64s/i64s/f64s/bools/dates/
// bytesValues/jsons; the Kotlin TantivyFieldMap currently only ships the
// `texts`/`facets` plural accessors, so the rest are derived here from the
// public `values(name)` API rather than by widening the library surface.
// ============================================================================

fun TantivyFieldMap.u64s(name: String): List<Long> =
    values(name).filterIsInstance<TantivyValue.U64>().map { it.value }

fun TantivyFieldMap.i64s(name: String): List<Long> =
    values(name).filterIsInstance<TantivyValue.I64>().map { it.value }

fun TantivyFieldMap.f64s(name: String): List<Double> =
    values(name).filterIsInstance<TantivyValue.F64>().map { it.value }

fun TantivyFieldMap.bools(name: String): List<Boolean> =
    values(name).filterIsInstance<TantivyValue.Bool>().map { it.value }

fun TantivyFieldMap.datesMicros(name: String): List<Long> =
    values(name).filterIsInstance<TantivyValue.DateMicros>().map { it.epochMicros }

fun TantivyFieldMap.bytesValues(name: String): List<ByteArray> =
    values(name).filterIsInstance<TantivyValue.Bytes>().map { it.value }

fun TantivyFieldMap.jsons(name: String): List<String> =
    values(name).filterIsInstance<TantivyValue.Json>().map { it.json }
