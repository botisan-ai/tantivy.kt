package ai.botisan.tantivy

import ai.botisan.tantivy.ffi.TantivyIndex
import ai.botisan.tantivy.ffi.TantivyIndexException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fidelity tests for the query DSL.
 *
 * [TantivyQuery.toJson] is the only contract between Kotlin and the Rust
 * `TantivyQueryDsl` serde enum, and serde is strict: an unknown key, a wrong
 * tag, or `"slop": null` where the field is `Option<u32>` all fail to
 * deserialize. So this class checks the encoding twice over:
 *
 * 1. Golden structure assertions on the parsed JSON (exact keys and tags).
 * 2. An integration test that pushes every node type through a real index and
 *    records the outcome, so a shape serde rejects shows up as an error string
 *    instead of a hit count.
 */
class TantivyQueryDslTest {

    private fun TantivyQuery.parsed(): JsonObject = Json.parseToJsonElement(toJson()).jsonObject

    /** The `{"type": …, "value": …}` payload of a term query's value. */
    private fun encodedValue(value: TantivyValue): JsonObject =
        TantivyQuery.Term("field", value).parsed()
            .getValue("term").jsonObject
            .getValue("value").jsonObject

    // ============================================================ golden JSON

    @Test
    fun allAndEmptyEncodeOnlyTheirTag() {
        assertEquals(buildJsonObject { put("type", "all") }, TantivyQuery.All.parsed())
        assertEquals(buildJsonObject { put("type", "empty") }, TantivyQuery.Empty.parsed())
    }

    @Test
    fun fieldValuesAreAdjacentlyTagged() {
        assertEquals(
            buildJsonObject { put("type", "text"); put("value", "hello") },
            encodedValue(TantivyValue.Text("hello")),
        )
        assertEquals(
            buildJsonObject { put("type", "u64"); put("value", 42) },
            encodedValue(TantivyValue.U64(42)),
        )
        assertEquals(
            buildJsonObject { put("type", "i64"); put("value", -7) },
            encodedValue(TantivyValue.I64(-7)),
        )
        assertEquals(
            buildJsonObject { put("type", "f64"); put("value", 0.75) },
            encodedValue(TantivyValue.F64(0.75)),
        )
        assertEquals(
            buildJsonObject { put("type", "bool"); put("value", true) },
            encodedValue(TantivyValue.Bool(true)),
        )
        assertEquals(
            buildJsonObject { put("type", "date"); put("value", 1_700_000_000_123_456L) },
            encodedValue(TantivyValue.DateMicros(1_700_000_000_123_456L)),
        )
        assertEquals(
            buildJsonObject {
                put("type", "bytes")
                putJsonArray("value") { add(202); add(254) }
            },
            encodedValue(TantivyValue.Bytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))),
        )
        assertEquals(
            buildJsonObject { put("type", "facet"); put("value", "/receipt/tags/home") },
            encodedValue(TantivyValue.Facet("/receipt/tags/home")),
        )
        // A json value travels as a *string*, not as a nested object.
        assertEquals(
            buildJsonObject { put("type", "json"); put("value", """{"source":"ocr"}""") },
            encodedValue(TantivyValue.Json("""{"source":"ocr"}""")),
        )
    }

    @Test
    fun termWrapsNameAndValue() {
        assertEquals(
            buildJsonObject {
                put("type", "term")
                putJsonObject("term") {
                    put("name", "amount")
                    putJsonObject("value") { put("type", "u64"); put("value", 42) }
                }
            },
            TantivyQuery.Term("amount", TantivyValue.U64(42)).parsed(),
        )
    }

    @Test
    fun termSetUsesSnakeCaseTagAndAnArrayOfTerms() {
        val query = TantivyQuery.TermSet(
            listOf(
                "id" to TantivyValue.Text("a1"),
                "amount" to TantivyValue.U64(7),
            ),
        )
        assertEquals(
            buildJsonObject {
                put("type", "term_set")
                putJsonArray("terms") {
                    addJsonObject {
                        put("name", "id")
                        putJsonObject("value") { put("type", "text"); put("value", "a1") }
                    }
                    addJsonObject {
                        put("name", "amount")
                        putJsonObject("value") { put("type", "u64"); put("value", 7) }
                    }
                }
            },
            query.parsed(),
        )
    }

    @Test
    fun booleanClausesUseSnakeCaseOccurTags() {
        val query = TantivyQuery.Boolean(
            listOf(
                TantivyQuery.Clause(TantivyQuery.Occur.MUST, TantivyQuery.All),
                TantivyQuery.Clause(TantivyQuery.Occur.SHOULD, TantivyQuery.Empty),
                TantivyQuery.Clause(TantivyQuery.Occur.MUST_NOT, TantivyQuery.Exists("amount")),
            ),
        )
        assertEquals(
            buildJsonObject {
                put("type", "boolean")
                putJsonArray("clauses") {
                    addJsonObject {
                        put("occur", "must")
                        putJsonObject("query") { put("type", "all") }
                    }
                    addJsonObject {
                        put("occur", "should")
                        putJsonObject("query") { put("type", "empty") }
                    }
                    addJsonObject {
                        put("occur", "must_not")
                        putJsonObject("query") { put("type", "exists"); put("field", "amount") }
                    }
                }
            },
            query.parsed(),
        )
    }

    @Test
    fun phraseOmitsSlopWhenAbsent() {
        val without = TantivyQuery.Phrase("title", listOf("swift", "and")).parsed()
        assertEquals(setOf("type", "field", "terms"), without.keys)
        assertEquals(
            buildJsonObject {
                put("type", "phrase")
                put("field", "title")
                putJsonArray("terms") { add("swift"); add("and") }
            },
            without,
        )

        val with = TantivyQuery.Phrase("title", listOf("swift", "rust"), slop = 2).parsed()
        assertEquals(setOf("type", "field", "terms", "slop"), with.keys)
        assertEquals(JsonPrimitive(2), with.getValue("slop"))
    }

    @Test
    fun phrasePrefixUsesSnakeCaseTagAndOmitsMaxExpansions() {
        val without = TantivyQuery.PhrasePrefix("title", listOf("swi")).parsed()
        assertEquals(setOf("type", "field", "terms"), without.keys)
        assertEquals(JsonPrimitive("phrase_prefix"), without.getValue("type"))

        val with = TantivyQuery.PhrasePrefix("title", listOf("swi"), maxExpansions = 5).parsed()
        assertEquals(setOf("type", "field", "terms", "max_expansions"), with.keys)
        assertEquals(JsonPrimitive(5), with.getValue("max_expansions"))
    }

    @Test
    fun rangeOmitsAbsentBoundsButAlwaysSendsInclusiveFlags() {
        val unbounded = TantivyQuery.Range("amount").parsed()
        assertEquals(setOf("type", "field", "include_lower", "include_upper"), unbounded.keys)
        assertEquals(JsonPrimitive(true), unbounded.getValue("include_lower"))
        assertEquals(JsonPrimitive(true), unbounded.getValue("include_upper"))

        val lowerOnly = TantivyQuery.Range("amount", lower = TantivyValue.U64(1)).parsed()
        assertEquals(setOf("type", "field", "lower", "include_lower", "include_upper"), lowerOnly.keys)

        val bounded = TantivyQuery.Range(
            field = "amount",
            lower = TantivyValue.U64(1),
            upper = TantivyValue.U64(100),
            includeLower = false,
            includeUpper = false,
        ).parsed()
        assertEquals(
            buildJsonObject {
                put("type", "range")
                put("field", "amount")
                putJsonObject("lower") { put("type", "u64"); put("value", 1) }
                putJsonObject("upper") { put("type", "u64"); put("value", 100) }
                put("include_lower", false)
                put("include_upper", false)
            },
            bounded,
        )
    }

    @Test
    fun regexAndFuzzyAndExistsUseSnakeCaseKeys() {
        assertEquals(
            buildJsonObject {
                put("type", "regex")
                put("field", "title")
                put("pattern", "swi.*")
            },
            TantivyQuery.Regex("title", "swi.*").parsed(),
        )
        assertEquals(
            buildJsonObject {
                put("type", "fuzzy")
                put("field", "title")
                put("term", "swifr")
                put("distance", 2)
                put("transpose_cost_one", true)
            },
            TantivyQuery.Fuzzy("title", "swifr", distance = 2, transposeCostOne = true).parsed(),
        )
        // Defaults still travel explicitly — the Rust fields are not Options.
        val defaults = TantivyQuery.Fuzzy("title", "swift").parsed()
        assertEquals(JsonPrimitive(1), defaults.getValue("distance"))
        assertEquals(JsonPrimitive(false), defaults.getValue("transpose_cost_one"))

        assertEquals(
            buildJsonObject { put("type", "exists"); put("field", "amount") },
            TantivyQuery.Exists("amount").parsed(),
        )
    }

    @Test
    fun boostAndConstScoreNestTheirInnerQuery() {
        assertEquals(
            buildJsonObject {
                put("type", "boost")
                putJsonObject("query") { put("type", "all") }
                put("boost", 2.5f)
            },
            TantivyQuery.Boost(TantivyQuery.All, 2.5f).parsed(),
        )
        assertEquals(
            buildJsonObject {
                put("type", "const_score")
                putJsonObject("query") { put("type", "all") }
                put("score", 1.5f)
            },
            TantivyQuery.ConstScore(TantivyQuery.All, 1.5f).parsed(),
        )
    }

    @Test
    fun disjunctionMaxOmitsTieBreakerWhenAbsent() {
        val without = TantivyQuery.DisjunctionMax(listOf(TantivyQuery.All, TantivyQuery.Empty)).parsed()
        assertEquals(setOf("type", "queries"), without.keys)
        assertEquals(JsonPrimitive("disjunction_max"), without.getValue("type"))

        val with = TantivyQuery.DisjunctionMax(listOf(TantivyQuery.All), tieBreaker = 0.3f).parsed()
        assertEquals(setOf("type", "queries", "tie_breaker"), with.keys)
        assertEquals(JsonPrimitive(0.3f), with.getValue("tie_breaker"))
    }

    @Test
    fun queryStringAlwaysSendsBothFieldListsAndSnakeCaseFuzzyKeys() {
        val bare = TantivyQuery.QueryString("swift").parsed()
        assertEquals(
            buildJsonObject {
                put("type", "query_string")
                put("query", "swift")
                putJsonArray("default_fields") {}
                putJsonArray("fuzzy_fields") {}
            },
            bare,
        )

        val fuzzy = TantivyQuery.QueryString(
            query = "swift",
            defaultFields = listOf("title", "body"),
            fuzzyFields = listOf(
                TantivyQuery.FuzzyField("title", prefix = true, distance = 2, transposeCostOne = true),
            ),
        ).parsed()
        assertEquals(
            buildJsonObject {
                put("type", "query_string")
                put("query", "swift")
                putJsonArray("default_fields") { add("title"); add("body") }
                putJsonArray("fuzzy_fields") {
                    addJsonObject {
                        put("field_name", "title")
                        put("prefix", true)
                        put("distance", 2)
                        put("transpose_cost_one", true)
                    }
                }
            },
            fuzzy,
        )
    }

    // ====================================================== integration checks

    /**
     * Control test: proves the integration test below has teeth — a shape serde
     * cannot deserialize surfaces as [TantivyIndexException.SerializationException].
     */
    @Test
    fun rustRejectsAnUnknownQueryTag() {
        val index = TantivyIndex.newWithSchema(tempIndexPath("dsl_reject"), DSL_SCHEMA.newBuilder())
        try {
            assertThrows(TantivyIndexException.SerializationException::class.java) {
                index.searchDsl("""{"type":"definitely_not_a_query"}""", 10u, 0u)
            }
        } finally {
            index.destroy()
        }
    }

    @Test
    fun everyQueryNodeTypeRoundTripsThroughRust() = runTest {
        TypedTantivyIndex.open(tempIndexPath("dsl_all_nodes"), DSL_SCHEMA, DslDocAdapter).use { index ->
            index.indexAll(DSL_DOCS)
            assertEquals(3L, index.count())

            val outcomes = LinkedHashMap<String, String>()
            for (case in QUERY_CASES) {
                // Not swallowed: a rejected shape becomes the recorded outcome and
                // fails the assertEquals below with the serde message attached.
                outcomes[case.label] = try {
                    "${index.search(case.query, limit = 10, offset = 0).count} hits"
                } catch (e: TantivyIndexException) {
                    "${e::class.simpleName}: ${e.message}"
                }
            }

            assertEquals(QUERY_CASES.associate { it.label to "${it.expectedHits} hits" }, outcomes)
        }
    }

    private fun tempIndexPath(name: String): String =
        Files.createTempDirectory("tantivy-$name").toAbsolutePath().toString()
}

// ============================================================================
// Fixture: one small index that carries every field type the DSL can query
// ============================================================================

private data class DslDoc(
    val id: String,
    val title: String,
    val amount: Long,
    val delta: Long,
    val ratio: Double,
    val active: Boolean,
    val createdAtMicros: Long,
    val blob: ByteArray,
    val category: String,
    val tag: String,
)

private val DSL_SCHEMA: TantivySchema = tantivySchema {
    idField("id")
    textField("title")
    u64Field("amount")
    i64Field("delta")
    f64Field("ratio")
    boolField("active")
    dateField("createdAt", fast = true, precision = DatePrecision.MICROSECONDS)
    bytesField("blob", indexed = true)
    facetField("category")
    textField("tag", tokenizer = Tokenizer.RAW, record = RecordOption.BASIC)
}

private object DslDocAdapter : TantivyDocumentAdapter<DslDoc> {
    override fun encode(value: DslDoc, doc: TantivyDocumentWriter) {
        doc.text("id", value.id)
        doc.text("title", value.title)
        doc.u64("amount", value.amount)
        doc.i64("delta", value.delta)
        doc.f64("ratio", value.ratio)
        doc.bool("active", value.active)
        doc.dateMicros("createdAt", value.createdAtMicros)
        doc.bytes("blob", value.blob)
        doc.facet("category", value.category)
        doc.text("tag", value.tag)
    }

    override fun decode(fields: TantivyFieldMap): DslDoc = DslDoc(
        id = fields.text("id").orEmpty(),
        title = fields.text("title").orEmpty(),
        amount = fields.u64("amount") ?: 0L,
        delta = fields.i64("delta") ?: 0L,
        ratio = fields.f64("ratio") ?: 0.0,
        active = fields.bool("active") ?: false,
        createdAtMicros = fields.dateMicros("createdAt") ?: 0L,
        blob = fields.bytes("blob") ?: ByteArray(0),
        category = fields.facet("category").orEmpty(),
        tag = fields.text("tag").orEmpty(),
    )
}

// Whole-second timestamps: tantivy truncates *indexed* date terms to seconds,
// so second-aligned values keep term and range queries meaningful.
private const val T1 = 1_700_000_000_000_000L
private const val T2 = 1_700_000_100_000_000L
private const val T3 = 1_700_000_200_000_000L

private val DSL_DOCS = listOf(
    DslDoc("1", "Swift and Rust", 42, -4, 0.75, true, T1, byteArrayOf(0xCA.toByte(), 0xFE.toByte()), "/tech", "alpha"),
    DslDoc("2", "Cooking Pasta", 7, 3, 0.5, false, T2, byteArrayOf(0xBA.toByte(), 0xBE.toByte()), "/food", "beta"),
    DslDoc("3", "Swift Concurrency", 100, 0, 0.25, true, T3, byteArrayOf(0x01), "/tech", "gamma"),
)

private data class QueryCase(val label: String, val query: TantivyQuery, val expectedHits: Long)

private val QUERY_CASES = listOf(
    QueryCase("all", TantivyQuery.All, 3),
    QueryCase("empty", TantivyQuery.Empty, 0),
    QueryCase("term/text", TantivyQuery.Term("title", TantivyValue.Text("swift")), 2),
    QueryCase("term/raw-text", TantivyQuery.Term("tag", TantivyValue.Text("alpha")), 1),
    QueryCase("term/u64", TantivyQuery.Term("amount", TantivyValue.U64(42)), 1),
    QueryCase("term/i64", TantivyQuery.Term("delta", TantivyValue.I64(-4)), 1),
    QueryCase("term/f64", TantivyQuery.Term("ratio", TantivyValue.F64(0.75)), 1),
    QueryCase("term/bool", TantivyQuery.Term("active", TantivyValue.Bool(true)), 2),
    QueryCase("term/date", TantivyQuery.Term("createdAt", TantivyValue.DateMicros(T1)), 1),
    QueryCase(
        "term/bytes",
        TantivyQuery.Term("blob", TantivyValue.Bytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))),
        1,
    ),
    QueryCase("term/facet", TantivyQuery.Term("category", TantivyValue.Facet("/tech")), 2),
    QueryCase(
        "term_set",
        TantivyQuery.TermSet(listOf("id" to TantivyValue.Text("1"), "id" to TantivyValue.Text("2"))),
        2,
    ),
    QueryCase("term_set/empty", TantivyQuery.TermSet(emptyList()), 0),
    QueryCase(
        "boolean/must+must_not",
        TantivyQuery.Boolean(
            listOf(
                TantivyQuery.Clause(TantivyQuery.Occur.MUST, TantivyQuery.Term("title", TantivyValue.Text("swift"))),
                TantivyQuery.Clause(
                    TantivyQuery.Occur.MUST_NOT,
                    TantivyQuery.Term("category", TantivyValue.Facet("/food")),
                ),
            ),
        ),
        2,
    ),
    QueryCase(
        "boolean/should",
        TantivyQuery.Boolean(
            listOf(
                TantivyQuery.Clause(TantivyQuery.Occur.SHOULD, TantivyQuery.Term("title", TantivyValue.Text("swift"))),
                TantivyQuery.Clause(TantivyQuery.Occur.SHOULD, TantivyQuery.Term("title", TantivyValue.Text("pasta"))),
            ),
        ),
        3,
    ),
    QueryCase("phrase", TantivyQuery.Phrase("title", listOf("swift", "and")), 1),
    QueryCase("phrase/slop", TantivyQuery.Phrase("title", listOf("swift", "rust"), slop = 1), 1),
    QueryCase("phrase_prefix", TantivyQuery.PhrasePrefix("title", listOf("swift", "an")), 1),
    QueryCase(
        "phrase_prefix/max_expansions",
        TantivyQuery.PhrasePrefix("title", listOf("swift", "co"), maxExpansions = 5),
        1,
    ),
    QueryCase(
        "range/u64",
        TantivyQuery.Range("amount", lower = TantivyValue.U64(1), upper = TantivyValue.U64(50)),
        2,
    ),
    QueryCase("range/u64-lower-only", TantivyQuery.Range("amount", lower = TantivyValue.U64(50)), 1),
    QueryCase(
        "range/u64-upper-only-exclusive",
        TantivyQuery.Range("amount", upper = TantivyValue.U64(42), includeUpper = false),
        1,
    ),
    QueryCase(
        "range/date",
        TantivyQuery.Range("createdAt", lower = TantivyValue.DateMicros(T1), upper = TantivyValue.DateMicros(T3)),
        3,
    ),
    QueryCase("regex", TantivyQuery.Regex("title", "swi.*"), 2),
    QueryCase("fuzzy", TantivyQuery.Fuzzy("title", "swifr"), 2),
    QueryCase(
        "fuzzy/transpose",
        TantivyQuery.Fuzzy("title", "siwft", distance = 1, transposeCostOne = true),
        2,
    ),
    QueryCase("exists", TantivyQuery.Exists("amount"), 3),
    QueryCase("boost", TantivyQuery.Boost(TantivyQuery.Term("title", TantivyValue.Text("swift")), 2.0f), 2),
    QueryCase(
        "const_score",
        TantivyQuery.ConstScore(TantivyQuery.Term("title", TantivyValue.Text("swift")), 1.5f),
        2,
    ),
    QueryCase(
        "disjunction_max",
        TantivyQuery.DisjunctionMax(
            listOf(
                TantivyQuery.Term("title", TantivyValue.Text("swift")),
                TantivyQuery.Term("title", TantivyValue.Text("pasta")),
            ),
        ),
        3,
    ),
    QueryCase(
        "disjunction_max/tie_breaker",
        TantivyQuery.DisjunctionMax(
            listOf(
                TantivyQuery.Term("title", TantivyValue.Text("swift")),
                TantivyQuery.Term("title", TantivyValue.Text("pasta")),
            ),
            tieBreaker = 0.3f,
        ),
        3,
    ),
    QueryCase("query_string", TantivyQuery.QueryString("swift", listOf("title")), 2),
    QueryCase(
        "query_string/fuzzy_fields",
        TantivyQuery.QueryString(
            query = "swifr",
            defaultFields = listOf("title"),
            fuzzyFields = listOf(TantivyQuery.FuzzyField("title", prefix = false, distance = 1)),
        ),
        2,
    ),
)
