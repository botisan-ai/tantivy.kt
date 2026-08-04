package ai.botisan.tantivy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Query DSL mirroring the Rust `TantivyQueryDsl` serde enum
 * (`#[serde(tag = "type", rename_all = "snake_case")]`). `toJson()` must stay
 * byte-level compatible with what serde deserializes — key names, tag values,
 * and omission (not null-ing) of absent optionals. Covered by golden tests and
 * an integration test that runs every node type through the Rust side.
 */
public sealed class TantivyQuery {
    public object All : TantivyQuery()

    public object Empty : TantivyQuery()

    public data class Term(val field: String, val value: TantivyValue) : TantivyQuery()

    public data class TermSet(val terms: List<Pair<String, TantivyValue>>) : TantivyQuery()

    public data class Boolean(val clauses: List<Clause>) : TantivyQuery()

    public data class Phrase(val field: String, val terms: List<String>, val slop: Int? = null) : TantivyQuery() {
        init {
            slop?.let { require(it >= 0) { "slop must be non-negative (got $it)" } }
        }
    }

    public data class PhrasePrefix(
        val field: String,
        val terms: List<String>,
        val maxExpansions: Int? = null,
    ) : TantivyQuery() {
        init {
            maxExpansions?.let { require(it >= 0) { "maxExpansions must be non-negative (got $it)" } }
        }
    }

    public data class Range(
        val field: String,
        val lower: TantivyValue? = null,
        val upper: TantivyValue? = null,
        val includeLower: kotlin.Boolean = true,
        val includeUpper: kotlin.Boolean = true,
    ) : TantivyQuery()

    public data class Regex(val field: String, val pattern: String) : TantivyQuery()

    public data class Fuzzy(
        val field: String,
        val term: String,
        val distance: Int = 1,
        val transposeCostOne: kotlin.Boolean = false,
    ) : TantivyQuery() {
        init {
            // Deserialized as u8 on the Rust side.
            require(distance in 0..255) { "distance must be in 0..255 (got $distance)" }
        }
    }

    public data class Exists(val field: String) : TantivyQuery()

    public data class Boost(val query: TantivyQuery, val boost: Float) : TantivyQuery() {
        init {
            require(boost.isFinite()) { "boost must be finite (got $boost)" }
        }
    }

    public data class ConstScore(val query: TantivyQuery, val score: Float) : TantivyQuery() {
        init {
            require(score.isFinite()) { "score must be finite (got $score)" }
        }
    }

    public data class DisjunctionMax(
        val queries: List<TantivyQuery>,
        val tieBreaker: Float? = null,
    ) : TantivyQuery() {
        init {
            tieBreaker?.let { require(it.isFinite()) { "tieBreaker must be finite (got $it)" } }
        }
    }

    public data class QueryString(
        val query: String,
        val defaultFields: List<String> = emptyList(),
        val fuzzyFields: List<FuzzyField> = emptyList(),
    ) : TantivyQuery()

    public enum class Occur(internal val json: String) {
        MUST("must"),
        SHOULD("should"),
        MUST_NOT("must_not"),
    }

    public data class Clause(val occur: Occur, val query: TantivyQuery)

    public data class FuzzyField(
        val fieldName: String,
        val prefix: kotlin.Boolean = false,
        val distance: Int = 1,
        val transposeCostOne: kotlin.Boolean = false,
    ) {
        init {
            require(distance in 0..255) { "distance must be in 0..255 (got $distance)" }
        }
    }

    public companion object {
        /** Matches documents whose facet [field] contains any of [paths] — e.g. tag filters. */
        public fun facetAnyOf(field: String, paths: List<String>): TantivyQuery =
            TermSet(paths.map { field to TantivyValue.Facet(it) })
    }

    public fun toJson(): String = toJsonObject().toString()

    internal fun toJsonObject(): JsonObject = buildJsonObject {
        when (this@TantivyQuery) {
            is All -> put("type", "all")
            is Empty -> put("type", "empty")
            is Term -> {
                put("type", "term")
                put("term", termObject(field, value))
            }
            is TermSet -> {
                put("type", "term_set")
                put("terms", buildJsonArray { terms.forEach { add(termObject(it.first, it.second)) } })
            }
            is Boolean -> {
                put("type", "boolean")
                put(
                    "clauses",
                    buildJsonArray {
                        clauses.forEach { clause ->
                            add(
                                buildJsonObject {
                                    put("occur", clause.occur.json)
                                    put("query", clause.query.toJsonObject())
                                },
                            )
                        }
                    },
                )
            }
            is Phrase -> {
                put("type", "phrase")
                put("field", field)
                put("terms", buildJsonArray { terms.forEach { add(it) } })
                slop?.let { put("slop", it) }
            }
            is PhrasePrefix -> {
                put("type", "phrase_prefix")
                put("field", field)
                put("terms", buildJsonArray { terms.forEach { add(it) } })
                maxExpansions?.let { put("max_expansions", it) }
            }
            is Range -> {
                put("type", "range")
                put("field", field)
                lower?.let { put("lower", it.toJsonElement()) }
                upper?.let { put("upper", it.toJsonElement()) }
                put("include_lower", includeLower)
                put("include_upper", includeUpper)
            }
            is Regex -> {
                put("type", "regex")
                put("field", field)
                put("pattern", pattern)
            }
            is Fuzzy -> {
                put("type", "fuzzy")
                put("field", field)
                put("term", term)
                put("distance", distance)
                put("transpose_cost_one", transposeCostOne)
            }
            is Exists -> {
                put("type", "exists")
                put("field", field)
            }
            is Boost -> {
                put("type", "boost")
                put("query", query.toJsonObject())
                put("boost", boost)
            }
            is ConstScore -> {
                put("type", "const_score")
                put("query", query.toJsonObject())
                put("score", score)
            }
            is DisjunctionMax -> {
                put("type", "disjunction_max")
                put("queries", buildJsonArray { queries.forEach { add(it.toJsonObject()) } })
                tieBreaker?.let { put("tie_breaker", it) }
            }
            is QueryString -> {
                put("type", "query_string")
                put("query", query)
                put("default_fields", buildJsonArray { defaultFields.forEach { add(it) } })
                put(
                    "fuzzy_fields",
                    buildJsonArray {
                        fuzzyFields.forEach { fuzzy ->
                            add(
                                buildJsonObject {
                                    put("field_name", fuzzy.fieldName)
                                    put("prefix", fuzzy.prefix)
                                    put("distance", fuzzy.distance)
                                    put("transpose_cost_one", fuzzy.transposeCostOne)
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    private fun termObject(field: String, value: TantivyValue): JsonObject = buildJsonObject {
        put("name", field)
        put("value", value.toJsonElement())
    }
}
