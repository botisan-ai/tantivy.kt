package ai.botisan.tantivy

import ai.botisan.tantivy.ffi.DateFieldOptions
import ai.botisan.tantivy.ffi.FacetFieldOptions
import ai.botisan.tantivy.ffi.JsonFieldOptions
import ai.botisan.tantivy.ffi.NumericFieldOptions
import ai.botisan.tantivy.ffi.TantivyDatePrecision
import ai.botisan.tantivy.ffi.TantivyIndexRecordOption
import ai.botisan.tantivy.ffi.TantivySchemaBuilder
import ai.botisan.tantivy.ffi.TantivyTokenizer
import ai.botisan.tantivy.ffi.TextFieldOptions

/*
 * Facade enums: the public schema API never exposes the UniFFI-generated types,
 * so generator/ABI churn cannot leak into consumer signatures.
 */

public enum class Tokenizer {
    RAW,
    DEFAULT,
    UNICODE,
    EN_STEM,
    WHITESPACE,
    ;

    internal fun toFfi(): TantivyTokenizer = when (this) {
        RAW -> TantivyTokenizer.RAW
        DEFAULT -> TantivyTokenizer.DEFAULT
        UNICODE -> TantivyTokenizer.UNICODE
        EN_STEM -> TantivyTokenizer.EN_STEM
        WHITESPACE -> TantivyTokenizer.WHITESPACE
    }
}

public enum class RecordOption {
    BASIC,
    WITH_FREQS,
    WITH_FREQS_AND_POSITIONS,
    ;

    internal fun toFfi(): TantivyIndexRecordOption = when (this) {
        BASIC -> TantivyIndexRecordOption.BASIC
        WITH_FREQS -> TantivyIndexRecordOption.WITH_FREQS
        WITH_FREQS_AND_POSITIONS -> TantivyIndexRecordOption.WITH_FREQS_AND_POSITIONS
    }
}

public enum class DatePrecision {
    SECONDS,
    MILLISECONDS,
    MICROSECONDS,
    ;

    internal fun toFfi(): TantivyDatePrecision = when (this) {
        SECONDS -> TantivyDatePrecision.SECONDS
        MILLISECONDS -> TantivyDatePrecision.MILLISECONDS
        MICROSECONDS -> TantivyDatePrecision.MICROSECONDS
    }
}

/**
 * Explicit schema declaration — the Kotlin replacement for TantivySwift's
 * `Mirror`-reflection property wrappers. Field defaults mirror the Swift
 * wrappers (`@TextField`, `@IDField`, `@U64Field`, …).
 */
public class TantivySchema internal constructor(internal val fields: List<FieldSpec>) {

    /** All declared field names, in declaration order. */
    public val fieldNames: List<String> = fields.map { it.name }

    /** Fields declared with [TantivySchemaScope.idField] (raw-tokenized, fast text fields). */
    public val idFieldNames: List<String> = fields.filterIsInstance<FieldSpec.Id>().map { it.name }

    /** Text fields excluding id fields — the fallback default fields for text queries. */
    public val defaultTextFieldNames: List<String> =
        fields.filterIsInstance<FieldSpec.Text>().map { it.name }

    private val specsByName: Map<String, FieldSpec> = fields.associateBy { it.name }

    /**
     * Stable schema fingerprint (sorted `name:spec` lines). Intentionally NOT
     * compatible with HybridSearch.swift's Swift-type-derived fingerprint.
     */
    public fun fingerprint(): String = fields.map { it.fingerprint() }.sorted().joinToString("|")

    /**
     * A new schema containing these fields plus the ones declared in [block]
     * (names must stay unique) — for composition, e.g. HybridSearch appends its
     * internal `__doc_id` field.
     */
    public fun extending(block: TantivySchemaScope.() -> Unit): TantivySchema {
        val scope = TantivySchemaScope(fields)
        scope.block()
        return scope.build()
    }

    internal fun specFor(name: String): FieldSpec? = specsByName[name]

    /** Builds a fresh FFI schema builder. */
    internal fun newBuilder(): TantivySchemaBuilder {
        val builder = TantivySchemaBuilder()
        fields.forEach { it.register(builder) }
        return builder
    }

    internal sealed class FieldSpec {
        abstract val name: String

        abstract fun register(builder: TantivySchemaBuilder)

        abstract fun fingerprint(): String

        /** The value kind this field stores, e.g. "text" — used in error messages. */
        abstract val kindLabel: String

        /** Whether [value]'s kind matches this field's declared kind. */
        abstract fun accepts(value: TantivyValue): Boolean

        data class Id(override val name: String) : FieldSpec() {
            override val kindLabel: String get() = "text"

            override fun accepts(value: TantivyValue): Boolean = value is TantivyValue.Text

            override fun register(builder: TantivySchemaBuilder) {
                builder.addTextField(
                    name,
                    TextFieldOptions(
                        tokenizer = TantivyTokenizer.RAW,
                        record = TantivyIndexRecordOption.BASIC,
                        stored = true,
                        fast = true,
                        fieldnorms = false,
                    ),
                )
            }

            override fun fingerprint() = "$name:id"
        }

        data class Text(
            override val name: String,
            val tokenizer: Tokenizer,
            val record: RecordOption,
            val stored: Boolean,
            val fast: Boolean,
            val fieldnorms: Boolean,
        ) : FieldSpec() {
            override val kindLabel: String get() = "text"

            override fun accepts(value: TantivyValue): Boolean = value is TantivyValue.Text

            override fun register(builder: TantivySchemaBuilder) {
                builder.addTextField(
                    name,
                    TextFieldOptions(tokenizer.toFfi(), record.toFfi(), stored, fast, fieldnorms),
                )
            }

            override fun fingerprint() = "$name:text($tokenizer,$record,$stored,$fast,$fieldnorms)"
        }

        data class Numeric(
            override val name: String,
            val kind: Kind,
            val indexed: Boolean,
            val stored: Boolean,
            val fast: Boolean,
            val fieldnorms: Boolean,
        ) : FieldSpec() {
            enum class Kind { U64, I64, F64, BOOL }

            override val kindLabel: String get() = kind.name.lowercase()

            override fun accepts(value: TantivyValue): Boolean = when (kind) {
                Kind.U64 -> value is TantivyValue.U64
                Kind.I64 -> value is TantivyValue.I64
                Kind.F64 -> value is TantivyValue.F64
                Kind.BOOL -> value is TantivyValue.Bool
            }

            override fun register(builder: TantivySchemaBuilder) {
                val options = NumericFieldOptions(indexed, stored, fast, fieldnorms)
                when (kind) {
                    Kind.U64 -> builder.addU64Field(name, options)
                    Kind.I64 -> builder.addI64Field(name, options)
                    Kind.F64 -> builder.addF64Field(name, options)
                    Kind.BOOL -> builder.addBoolField(name, options)
                }
            }

            override fun fingerprint() = "$name:${kind.name.lowercase()}($indexed,$stored,$fast,$fieldnorms)"
        }

        data class DateField(
            override val name: String,
            val indexed: Boolean,
            val stored: Boolean,
            val fast: Boolean,
            val fieldnorms: Boolean,
            val precision: DatePrecision,
        ) : FieldSpec() {
            override val kindLabel: String get() = "date"

            override fun accepts(value: TantivyValue): Boolean = value is TantivyValue.DateMicros

            override fun register(builder: TantivySchemaBuilder) {
                builder.addDateField(name, DateFieldOptions(indexed, stored, fast, fieldnorms, precision.toFfi()))
            }

            override fun fingerprint() = "$name:date($indexed,$stored,$fast,$fieldnorms,$precision)"
        }

        data class BytesField(
            override val name: String,
            val stored: Boolean,
            val fast: Boolean,
            val indexed: Boolean,
        ) : FieldSpec() {
            override val kindLabel: String get() = "bytes"

            override fun accepts(value: TantivyValue): Boolean = value is TantivyValue.Bytes

            override fun register(builder: TantivySchemaBuilder) {
                builder.addBytesField(name, stored = stored, fast = fast, indexed = indexed)
            }

            override fun fingerprint() = "$name:bytes($stored,$fast,$indexed)"
        }

        data class FacetField(override val name: String, val stored: Boolean) : FieldSpec() {
            override val kindLabel: String get() = "facet"

            override fun accepts(value: TantivyValue): Boolean = value is TantivyValue.Facet

            override fun register(builder: TantivySchemaBuilder) {
                builder.addFacetField(name, FacetFieldOptions(stored))
            }

            override fun fingerprint() = "$name:facet($stored)"
        }

        data class JsonField(
            override val name: String,
            val stored: Boolean,
            val indexed: Boolean,
            val fast: Boolean,
            val tokenizer: Tokenizer,
            val record: RecordOption,
            val fieldnorms: Boolean,
            val expandDots: Boolean,
            val fastTokenizer: Tokenizer?,
        ) : FieldSpec() {
            override val kindLabel: String get() = "json"

            override fun accepts(value: TantivyValue): Boolean = value is TantivyValue.Json

            override fun register(builder: TantivySchemaBuilder) {
                builder.addJsonField(
                    name,
                    JsonFieldOptions(
                        stored,
                        indexed,
                        fast,
                        tokenizer.toFfi(),
                        record.toFfi(),
                        fieldnorms,
                        expandDots,
                        fastTokenizer?.toFfi(),
                    ),
                )
            }

            override fun fingerprint() = "$name:json($stored,$indexed,$fast,$tokenizer,$record,$fieldnorms,$expandDots,$fastTokenizer)"
        }
    }
}

public fun tantivySchema(block: TantivySchemaScope.() -> Unit): TantivySchema =
    TantivySchemaScope().apply(block).build()

public class TantivySchemaScope internal constructor(
    initial: List<TantivySchema.FieldSpec> = emptyList(),
) {
    private val fields = initial.toMutableList()

    /** Raw-tokenized stored fast text field — the Swift `@IDField`. */
    public fun idField(name: String) {
        fields.add(TantivySchema.FieldSpec.Id(name))
    }

    public fun textField(
        name: String,
        tokenizer: Tokenizer = Tokenizer.UNICODE,
        record: RecordOption = RecordOption.WITH_FREQS_AND_POSITIONS,
        stored: Boolean = true,
        fast: Boolean = false,
        fieldnorms: Boolean = true,
    ) {
        fields.add(TantivySchema.FieldSpec.Text(name, tokenizer, record, stored, fast, fieldnorms))
    }

    public fun u64Field(
        name: String,
        indexed: Boolean = true,
        stored: Boolean = true,
        fast: Boolean = true,
        fieldnorms: Boolean = false,
    ) {
        fields.add(TantivySchema.FieldSpec.Numeric(name, TantivySchema.FieldSpec.Numeric.Kind.U64, indexed, stored, fast, fieldnorms))
    }

    public fun i64Field(
        name: String,
        indexed: Boolean = true,
        stored: Boolean = true,
        fast: Boolean = true,
        fieldnorms: Boolean = false,
    ) {
        fields.add(TantivySchema.FieldSpec.Numeric(name, TantivySchema.FieldSpec.Numeric.Kind.I64, indexed, stored, fast, fieldnorms))
    }

    public fun f64Field(
        name: String,
        indexed: Boolean = true,
        stored: Boolean = true,
        fast: Boolean = true,
        fieldnorms: Boolean = false,
    ) {
        fields.add(TantivySchema.FieldSpec.Numeric(name, TantivySchema.FieldSpec.Numeric.Kind.F64, indexed, stored, fast, fieldnorms))
    }

    public fun boolField(
        name: String,
        indexed: Boolean = true,
        stored: Boolean = true,
        fast: Boolean = true,
        fieldnorms: Boolean = false,
    ) {
        fields.add(TantivySchema.FieldSpec.Numeric(name, TantivySchema.FieldSpec.Numeric.Kind.BOOL, indexed, stored, fast, fieldnorms))
    }

    public fun dateField(
        name: String,
        indexed: Boolean = true,
        stored: Boolean = true,
        fast: Boolean = true,
        fieldnorms: Boolean = false,
        precision: DatePrecision = DatePrecision.MILLISECONDS,
    ) {
        fields.add(TantivySchema.FieldSpec.DateField(name, indexed, stored, fast, fieldnorms, precision))
    }

    public fun bytesField(
        name: String,
        stored: Boolean = true,
        fast: Boolean = false,
        indexed: Boolean = false,
    ) {
        fields.add(TantivySchema.FieldSpec.BytesField(name, stored, fast, indexed))
    }

    public fun facetField(name: String, stored: Boolean = true) {
        fields.add(TantivySchema.FieldSpec.FacetField(name, stored))
    }

    public fun jsonField(
        name: String,
        stored: Boolean = true,
        indexed: Boolean = false,
        fast: Boolean = false,
        tokenizer: Tokenizer = Tokenizer.UNICODE,
        record: RecordOption = RecordOption.WITH_FREQS_AND_POSITIONS,
        fieldnorms: Boolean = true,
        expandDots: Boolean = false,
        fastTokenizer: Tokenizer? = null,
    ) {
        fields.add(
            TantivySchema.FieldSpec.JsonField(name, stored, indexed, fast, tokenizer, record, fieldnorms, expandDots, fastTokenizer),
        )
    }

    internal fun build(): TantivySchema {
        require(fields.isNotEmpty()) { "Schema must declare at least one field" }
        require(fields.map { it.name }.toSet().size == fields.size) { "Schema field names must be unique" }
        return TantivySchema(fields.toList())
    }
}
