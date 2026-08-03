package ai.botisan.tantivy

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression tests from the #195 review: schema-aware encoding (T2), signed
 * boundary validation (T3), and the promised call-site ergonomics (T5).
 */
class TantivyValidationTest {

    private data class Receipt(val id: String, val merchant: String, val total: Double, val tagIds: List<String>)

    private val schema = tantivySchema {
        idField("id")
        textField("merchant")
        f64Field("total")
        facetField("tagIds")
    }

    private object ReceiptAdapter : TantivyDocumentAdapter<Receipt> {
        override fun encode(value: Receipt, doc: TantivyDocumentWriter) {
            doc.text("id", value.id)
            doc.text("merchant", value.merchant)
            doc.f64("total", value.total)
            value.tagIds.forEach { doc.facet("tagIds", it) }
        }

        override fun decode(fields: TantivyFieldMap): Receipt = Receipt(
            id = fields.text("id").orEmpty(),
            merchant = fields.text("merchant").orEmpty(),
            total = fields.f64("total") ?: 0.0,
            tagIds = fields.facets("tagIds"),
        )
    }

    private fun tempDir(): File = Files.createTempDirectory("tantivy_validation_").toFile()

    private suspend fun openIndex() = TypedTantivyIndex.open(tempDir(), schema, ReceiptAdapter)

    // -- T2: schema-aware document writer -------------------------------------

    @Test
    fun typoFieldNameFailsBeforeAnythingIsIndexed() = runTest {
        val typoAdapter = object : TantivyDocumentAdapter<Receipt> {
            override fun encode(value: Receipt, doc: TantivyDocumentWriter) {
                doc.text("merhcant", value.merchant) // typo
            }

            override fun decode(fields: TantivyFieldMap): Receipt = ReceiptAdapter.decode(fields)
        }
        TypedTantivyIndex.open(tempDir(), schema, typoAdapter).use { index ->
            try {
                index.index(Receipt("r1", "Blue Bottle", 12.5, emptyList()))
                fail("expected UnknownField")
            } catch (e: TantivyEncodingException.UnknownField) {
                assertEquals("merhcant", e.field)
            }
            assertEquals(0L, index.count())
        }
    }

    @Test
    fun wrongValueKindFailsBeforeAnythingIsIndexed() = runTest {
        val wrongKindAdapter = object : TantivyDocumentAdapter<Receipt> {
            override fun encode(value: Receipt, doc: TantivyDocumentWriter) {
                doc.text("id", value.id)
                doc.text("total", "twelve fifty") // f64 field written as text
            }

            override fun decode(fields: TantivyFieldMap): Receipt = ReceiptAdapter.decode(fields)
        }
        TypedTantivyIndex.open(tempDir(), schema, wrongKindAdapter).use { index ->
            try {
                index.add(Receipt("r1", "Blue Bottle", 12.5, emptyList()))
                fail("expected ValueKindMismatch")
            } catch (e: TantivyEncodingException.ValueKindMismatch) {
                assertEquals("total", e.field)
            }
            assertEquals(0L, index.count())
        }
    }

    @Test
    fun writerReportsValueCounts() {
        val writer = TantivyDocumentWriter(schema)
        assertEquals(0, writer.valueCount("tagIds"))
        writer.text("id", "r1")
        writer.facet("tagIds", "/tag/a")
        writer.facet("tagIds", "/tag/b")
        assertEquals(1, writer.valueCount("id"))
        assertEquals(2, writer.valueCount("tagIds"))
    }

    // -- T3: signed boundaries -------------------------------------------------

    @Test
    fun numericBoundariesAreRejectedAtConstruction() {
        fun expectInvalid(block: () -> Unit) {
            try {
                block()
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
        expectInvalid { TantivyValue.U64(-1) }
        expectInvalid { TantivyQuery.Fuzzy("merchant", "cofee", distance = -1) }
        expectInvalid { TantivyQuery.Fuzzy("merchant", "cofee", distance = 256) }
        expectInvalid { TantivyQuery.FuzzyField("merchant", distance = 256) }
        expectInvalid { TantivyQuery.Phrase("merchant", listOf("blue", "bottle"), slop = -1) }
        expectInvalid { TantivyQuery.PhrasePrefix("merchant", listOf("blue"), maxExpansions = -1) }
        expectInvalid { TantivyQuery.Boost(TantivyQuery.All, Float.NaN) }
        expectInvalid { TantivyQuery.ConstScore(TantivyQuery.All, Float.POSITIVE_INFINITY) }

        // The valid edges construct fine.
        TantivyValue.U64(0)
        TantivyValue.U64(Long.MAX_VALUE)
        TantivyQuery.Fuzzy("merchant", "cofee", distance = 255)
    }

    @Test
    fun nativeU64AboveLongMaxIsRejectedOnTheWayOut() {
        // A stored u64 above Long.MAX_VALUE cannot round-trip through the
        // Kotlin boundary; fromFfi must fail with the documented message, not
        // wrap to a negative Long.
        try {
            TantivyValue.fromFfi(ai.botisan.tantivy.ffi.FieldValue.U64(Long.MAX_VALUE.toULong() + 1uL))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("exceeds the supported Kotlin domain"))
        }
        // The largest supported native value converts exactly.
        assertEquals(
            TantivyValue.U64(Long.MAX_VALUE),
            TantivyValue.fromFfi(ai.botisan.tantivy.ffi.FieldValue.U64(Long.MAX_VALUE.toULong())),
        )
    }

    @Test
    fun pagingBoundariesAreRejected() = runTest {
        openIndex().use { index ->
            index.index(Receipt("r1", "Blue Bottle", 12.5, emptyList()))

            suspend fun expectInvalid(block: suspend () -> Unit) {
                try {
                    block()
                    fail("expected IllegalArgumentException")
                } catch (_: IllegalArgumentException) {
                }
            }
            expectInvalid { index.search(TantivyQuery.All, limit = 0) }
            expectInvalid { index.search(TantivyQuery.All, limit = -1) }
            expectInvalid { index.search(TantivyQuery.All, limit = 10, offset = -1) }
            expectInvalid { index.searchText("coffee", limit = -5) }

            assertEquals(1L, index.search(TantivyQuery.All, limit = 1).count)
        }
    }

    // -- T5: promised ergonomics ------------------------------------------------

    @Test
    fun upsertReplacesTheDocumentWithTheSameId() = runTest {
        openIndex().use { index ->
            index.index(Receipt("r1", "Blue Bottle", 12.5, emptyList()))
            index.upsert(Receipt("r1", "Blue Bottle Oakland", 13.0, emptyList()))
            index.commit()

            assertEquals(1L, index.count())
            assertEquals("Blue Bottle Oakland", index.getDoc("id", TantivyValue.Text("r1")).merchant)

            // Upserting an id that does not exist yet behaves like add.
            index.upsert(Receipt("r2", "Sightglass", 9.0, emptyList()))
            index.commit()
            assertEquals(2L, index.count())
        }
    }

    @Test
    fun upsertRequiresAResolvableIdField() = runTest {
        val twoIds = tantivySchema {
            idField("id")
            idField("altId")
            textField("merchant")
        }
        val adapter = object : TantivyDocumentAdapter<Receipt> {
            override fun encode(value: Receipt, doc: TantivyDocumentWriter) {
                doc.text("id", value.id)
                doc.text("altId", "alt-${value.id}")
                doc.text("merchant", value.merchant)
            }

            override fun decode(fields: TantivyFieldMap): Receipt = Receipt(
                id = fields.text("id").orEmpty(),
                merchant = fields.text("merchant").orEmpty(),
                total = 0.0,
                tagIds = emptyList(),
            )
        }
        TypedTantivyIndex.open(tempDir(), twoIds, adapter).use { index ->
            try {
                index.upsert(Receipt("r1", "Blue Bottle", 0.0, emptyList()))
                fail("expected IllegalArgumentException (ambiguous id field)")
            } catch (_: IllegalArgumentException) {
            }
            index.upsert(Receipt("r1", "Blue Bottle", 0.0, emptyList()), idField = "id")
            index.commit()
            assertEquals(1L, index.count())
        }
    }

    @Test
    fun upsertWithoutAnIdValueIsATypedError() = runTest {
        val noIdAdapter = object : TantivyDocumentAdapter<Receipt> {
            override fun encode(value: Receipt, doc: TantivyDocumentWriter) {
                doc.text("merchant", value.merchant) // never writes "id"
            }

            override fun decode(fields: TantivyFieldMap): Receipt = ReceiptAdapter.decode(fields)
        }
        TypedTantivyIndex.open(tempDir(), schema, noIdAdapter).use { index ->
            try {
                index.upsert(Receipt("r1", "Blue Bottle", 0.0, emptyList()))
                fail("expected MissingIdValue")
            } catch (e: TantivyEncodingException.MissingIdValue) {
                assertEquals("id", e.field)
            }
        }
    }

    @Test
    fun upsertWithSeveralIdValuesIsATypedError() = runTest {
        // An adapter emitting the id field twice would give the document two
        // identities; later replacements/deletes could match either value.
        val doubleIdAdapter = object : TantivyDocumentAdapter<Receipt> {
            override fun encode(value: Receipt, doc: TantivyDocumentWriter) {
                doc.text("id", value.id)
                doc.text("id", "${value.id}-again")
                doc.text("merchant", value.merchant)
            }

            override fun decode(fields: TantivyFieldMap): Receipt = ReceiptAdapter.decode(fields)
        }
        TypedTantivyIndex.open(tempDir(), schema, doubleIdAdapter).use { index ->
            try {
                index.upsert(Receipt("r1", "Blue Bottle", 0.0, emptyList()))
                fail("expected AmbiguousIdValue")
            } catch (e: TantivyEncodingException.AmbiguousIdValue) {
                assertEquals("id", e.field)
            }
            index.commit()
            assertEquals(0L, index.count()) // nothing was indexed
        }
    }

    @Test
    fun searchTextWithFilterNarrowsResults() = runTest {
        openIndex().use { index ->
            index.indexAll(
                listOf(
                    Receipt("r1", "Blue Bottle Coffee", 12.5, listOf("/tag/coffee")),
                    Receipt("r2", "Blue Star Donuts", 8.0, listOf("/tag/food")),
                ),
            )

            val unfiltered = index.searchText("blue", limit = 10)
            assertEquals(2L, unfiltered.count)

            val filtered = index.searchText(
                "blue",
                filter = TantivyQuery.facetAnyOf("tagIds", listOf("/tag/coffee")),
                limit = 10,
            )
            assertEquals(1L, filtered.count)
            assertEquals("r1", filtered.hits.first().doc.id)
        }
    }

    @Test
    fun facetAnyOfEncodesAsATermSet() {
        val json = TantivyQuery.facetAnyOf("tagIds", listOf("/tag/a", "/tag/b")).toJson()
        assertEquals(
            """{"type":"term_set","terms":[{"name":"tagIds","value":{"type":"facet","value":"/tag/a"}},""" +
                """{"name":"tagIds","value":{"type":"facet","value":"/tag/b"}}]}""",
            json,
        )
    }

    @Test
    fun schemaExtendingAddsFieldsAndRejectsDuplicates() = runTest {
        val extended = schema.extending {
            u64Field("__doc_id")
        }
        assertEquals(schema.fieldNames + "__doc_id", extended.fieldNames)
        assertTrue(extended.fingerprint() != schema.fingerprint())

        try {
            schema.extending { textField("merchant") }
            fail("expected IllegalArgumentException (duplicate field)")
        } catch (_: IllegalArgumentException) {
        }

        // An extended schema opens a working index (the writer accepts the new field).
        val adapter = object : TantivyDocumentAdapter<Pair<Receipt, Long>> {
            override fun encode(value: Pair<Receipt, Long>, doc: TantivyDocumentWriter) {
                ReceiptAdapter.encode(value.first, doc)
                doc.u64("__doc_id", value.second)
            }

            override fun decode(fields: TantivyFieldMap): Pair<Receipt, Long> =
                ReceiptAdapter.decode(fields) to (fields.u64("__doc_id") ?: -1L)
        }
        TypedTantivyIndex.open(tempDir(), extended, adapter).use { index ->
            index.index(Receipt("r1", "Blue Bottle", 12.5, emptyList()) to 7L)
            val hit = index.search(TantivyQuery.Term("__doc_id", TantivyValue.U64(7)), limit = 1)
            assertEquals(1L, hit.count)
            assertEquals(7L, hit.hits.first().doc.second)
        }
    }

    @Test
    fun openAcceptsAFileDirectory() = runTest {
        TypedTantivyIndex.open(tempDir(), schema, ReceiptAdapter).use { index ->
            index.index(Receipt("r1", "Blue Bottle", 12.5, emptyList()))
            assertEquals(1L, index.count())
        }
    }
}
