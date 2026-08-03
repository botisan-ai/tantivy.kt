package ai.botisan.tantivy

import ai.botisan.tantivy.ffi.DocumentField
import ai.botisan.tantivy.ffi.FieldValue
import ai.botisan.tantivy.ffi.TantivyDocumentFields
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A typed field value, used both in documents and in queries. Mirrors the Rust
 * `FieldValue` enum; `toJsonElement()` matches its serde encoding
 * (`#[serde(tag = "type", content = "value", rename_all = "snake_case")]`).
 */
public sealed class TantivyValue {
    public data class Text(val value: String) : TantivyValue()

    /** Interpreted as unsigned on the Rust side; negative values are invalid. */
    public data class U64(val value: Long) : TantivyValue()

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
            is FieldValue.U64 -> U64(value.v1.toLong())
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

/** Builder handed to [TantivyDocumentAdapter.encode]; call a method per field value (repeat for multi-value fields). */
public class TantivyDocumentWriter public constructor() {
    internal val fields = mutableListOf<DocumentField>()

    public fun value(name: String, value: TantivyValue) {
        fields.add(DocumentField(name, value.toFfi()))
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

    public fun build(): TantivyDocumentFields = TantivyDocumentFields(fields.toList())
}

/** Read-side view of a stored document, keyed by field name. Port of Swift's `TantivyDocumentFieldMap`. */
public class TantivyFieldMap public constructor(doc: TantivyDocumentFields) {
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
