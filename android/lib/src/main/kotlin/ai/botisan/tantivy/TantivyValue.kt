package ai.botisan.tantivy

import ai.botisan.tantivy.ffi.DocumentField
import ai.botisan.tantivy.ffi.FieldValue
import ai.botisan.tantivy.ffi.TantivyDocumentFields
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Thrown by [TantivyDocumentWriter] before anything crosses the FFI. */
public sealed class TantivyEncodingException(message: String) : Exception(message) {
    /** The adapter wrote a field name the schema does not declare (usually a typo). */
    public class UnknownField(public val field: String, knownFields: List<String>) :
        TantivyEncodingException("Unknown field '$field' — the schema declares: ${knownFields.joinToString()}")

    /** The adapter wrote a value whose kind does not match the field's declared kind. */
    public class ValueKindMismatch(public val field: String, expected: String, got: String) :
        TantivyEncodingException("Field '$field' expects a $expected value but got $got")

    /** An operation needed an id value the encoded document does not carry. */
    public class MissingIdValue(public val field: String) :
        TantivyEncodingException("Encoded document has no value for id field '$field'")
}

/**
 * A typed field value, used both in documents and in queries. Mirrors the Rust
 * `FieldValue` enum; `toJsonElement()` matches its serde encoding
 * (`#[serde(tag = "type", content = "value", rename_all = "snake_case")]`).
 */
public sealed class TantivyValue {
    public data class Text(val value: String) : TantivyValue()

    /** Interpreted as unsigned on the Rust side; the supported domain is 0..Long.MAX_VALUE. */
    public data class U64(val value: Long) : TantivyValue() {
        init {
            require(value >= 0) { "U64 values must be non-negative (got $value)" }
        }
    }

    public data class I64(val value: Long) : TantivyValue()

    public data class F64(val value: Double) : TantivyValue()

    public data class Bool(val value: Boolean) : TantivyValue()

    /** Unix timestamp in microseconds. */
    public data class DateMicros(val epochMicros: Long) : TantivyValue()

    public class Bytes(val value: ByteArray) : TantivyValue() {
        override fun equals(other: Any?): Boolean = other is Bytes && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()
    }

    /** A facet path such as `/receipt-tag/<id>`. */
    public data class Facet(val path: String) : TantivyValue()

    public data class Json(val json: String) : TantivyValue()

    internal val kindName: String
        get() = when (this) {
            is Text -> "text"
            is U64 -> "u64"
            is I64 -> "i64"
            is F64 -> "f64"
            is Bool -> "bool"
            is DateMicros -> "date"
            is Bytes -> "bytes"
            is Facet -> "facet"
            is Json -> "json"
        }

    internal fun toFfi(): FieldValue = when (this) {
        is Text -> FieldValue.Text(value)
        is U64 -> FieldValue.U64(value.toULong())
        is I64 -> FieldValue.I64(value)
        is F64 -> FieldValue.F64(value)
        is Bool -> FieldValue.Bool(value)
        is DateMicros -> FieldValue.Date(epochMicros)
        is Bytes -> FieldValue.Bytes(value)
        is Facet -> FieldValue.Facet(path)
        is Json -> FieldValue.Json(json)
    }

    internal fun toJsonElement(): JsonElement = buildJsonObject {
        when (this@TantivyValue) {
            is Text -> { put("type", "text"); put("value", value) }
            is U64 -> { put("type", "u64"); put("value", JsonPrimitive(value.toULong())) }
            is I64 -> { put("type", "i64"); put("value", value) }
            is F64 -> { put("type", "f64"); put("value", value) }
            is Bool -> { put("type", "bool"); put("value", value) }
            is DateMicros -> { put("type", "date"); put("value", epochMicros) }
            is Bytes -> {
                put("type", "bytes")
                put("value", buildJsonArray { value.forEach { add(JsonPrimitive(it.toInt() and 0xFF)) } })
            }
            is Facet -> { put("type", "facet"); put("value", path) }
            is Json -> { put("type", "json"); put("value", json) }
        }
    }

    internal companion object {
        internal fun fromFfi(value: FieldValue): TantivyValue = when (value) {
            is FieldValue.Text -> Text(value.v1)
            is FieldValue.U64 -> {
                check(value.v1 <= Long.MAX_VALUE.toULong()) {
                    "u64 value ${value.v1} exceeds the supported Kotlin domain (0..Long.MAX_VALUE)"
                }
                U64(value.v1.toLong())
            }
            is FieldValue.I64 -> I64(value.v1)
            is FieldValue.F64 -> F64(value.v1)
            is FieldValue.Bool -> Bool(value.v1)
            is FieldValue.Date -> DateMicros(value.v1)
            is FieldValue.Bytes -> Bytes(value.v1)
            is FieldValue.Facet -> Facet(value.v1)
            is FieldValue.Json -> Json(value.v1)
        }
    }
}

/**
 * Builder handed to [TantivyDocumentAdapter.encode]; call a method per field
 * value (repeat for multi-value fields). Schema-aware: unknown field names and
 * mismatched value kinds throw [TantivyEncodingException] before anything is
 * handed to the native side (the Rust core silently drops unknown fields).
 */
public class TantivyDocumentWriter public constructor(private val schema: TantivySchema) {
    private val fields = mutableListOf<DocumentField>()
    private val counts = mutableMapOf<String, Int>()

    public fun value(name: String, value: TantivyValue) {
        val spec = schema.specFor(name) ?: throw TantivyEncodingException.UnknownField(name, schema.fieldNames)
        if (!spec.accepts(value)) {
            throw TantivyEncodingException.ValueKindMismatch(name, spec.kindLabel, value.kindName)
        }
        fields.add(DocumentField(name, value.toFfi()))
        counts.merge(name, 1, Int::plus)
    }

    public fun text(name: String, value: String): Unit = value(name, TantivyValue.Text(value))

    public fun u64(name: String, value: Long): Unit = value(name, TantivyValue.U64(value))

    public fun i64(name: String, value: Long): Unit = value(name, TantivyValue.I64(value))

    public fun f64(name: String, value: Double): Unit = value(name, TantivyValue.F64(value))

    public fun bool(name: String, value: Boolean): Unit = value(name, TantivyValue.Bool(value))

    public fun dateMicros(name: String, epochMicros: Long): Unit = value(name, TantivyValue.DateMicros(epochMicros))

    public fun bytes(name: String, value: ByteArray): Unit = value(name, TantivyValue.Bytes(value))

    public fun facet(name: String, path: String): Unit = value(name, TantivyValue.Facet(path))

    public fun json(name: String, json: String): Unit = value(name, TantivyValue.Json(json))

    /** How many values have been written for [name] so far. */
    public fun valueCount(name: String): Int = counts[name] ?: 0

    internal fun firstFfiValue(name: String): FieldValue? = fields.firstOrNull { it.name == name }?.value

    internal fun build(): TantivyDocumentFields = TantivyDocumentFields(fields.toList())
}

/** Read-side view of a stored document, keyed by field name. Port of Swift's `TantivyDocumentFieldMap`. */
public class TantivyFieldMap internal constructor(doc: TantivyDocumentFields) {
    private val values: Map<String, List<TantivyValue>> =
        doc.fields.groupBy({ it.name }, { TantivyValue.fromFfi(it.value) })

    public fun values(name: String): List<TantivyValue> = values[name] ?: emptyList()

    public fun firstValue(name: String): TantivyValue? = values(name).firstOrNull()

    public fun text(name: String): String? = (firstValue(name) as? TantivyValue.Text)?.value

    public fun texts(name: String): List<String> =
        values(name).mapNotNull { (it as? TantivyValue.Text)?.value }

    public fun u64(name: String): Long? = (firstValue(name) as? TantivyValue.U64)?.value

    public fun u64s(name: String): List<Long> =
        values(name).mapNotNull { (it as? TantivyValue.U64)?.value }

    public fun i64(name: String): Long? = (firstValue(name) as? TantivyValue.I64)?.value

    public fun i64s(name: String): List<Long> =
        values(name).mapNotNull { (it as? TantivyValue.I64)?.value }

    public fun f64(name: String): Double? = (firstValue(name) as? TantivyValue.F64)?.value

    public fun f64s(name: String): List<Double> =
        values(name).mapNotNull { (it as? TantivyValue.F64)?.value }

    public fun bool(name: String): Boolean? = (firstValue(name) as? TantivyValue.Bool)?.value

    public fun bools(name: String): List<Boolean> =
        values(name).mapNotNull { (it as? TantivyValue.Bool)?.value }

    public fun dateMicros(name: String): Long? = (firstValue(name) as? TantivyValue.DateMicros)?.epochMicros

    public fun datesMicros(name: String): List<Long> =
        values(name).mapNotNull { (it as? TantivyValue.DateMicros)?.epochMicros }

    public fun bytes(name: String): ByteArray? = (firstValue(name) as? TantivyValue.Bytes)?.value

    public fun bytesValues(name: String): List<ByteArray> =
        values(name).mapNotNull { (it as? TantivyValue.Bytes)?.value }

    public fun facet(name: String): String? = (firstValue(name) as? TantivyValue.Facet)?.path

    public fun facets(name: String): List<String> =
        values(name).mapNotNull { (it as? TantivyValue.Facet)?.path }

    public fun json(name: String): String? = (firstValue(name) as? TantivyValue.Json)?.json

    public fun jsons(name: String): List<String> =
        values(name).mapNotNull { (it as? TantivyValue.Json)?.json }
}
