# tantivy.kt

[Tantivy](https://github.com/quickwit-oss/tantivy) full-text search for **Android/Kotlin**, via UniFFI bindings and a typed Kotlin façade (schema DSL, query DSL, document adapters).

This is the Kotlin sibling of [tantivy.swift](https://github.com/botisan-ai/tantivy.swift). The Rust core in `rust/tantivy/src/` is **mirrored from tantivy.swift @ 0.3.4 (`dae1d22`)** — including the custom `UnicodeTokenizer` (lower-cased, ASCII-folded, CJK-aware; registered as the default `unicode` tokenizer) — sync it manually when the upstream core changes.

## Install

Artifacts ship as GitHub Release assets (no Maven registry). Each release carries:

- `tantivy-android-<version>-maven.zip` — a complete Maven-layout repository (AAR + POM + Gradle module metadata; JNA and coroutines resolve transitively)
- `tantivy-android-<version>.aar` — bare AAR for quick smoke tests
- `.sha256` for each

Recommended setup (works for all `ai.botisan` search packages): download the `-maven.zip` per package, verify checksums, unzip into e.g. `third_party/maven/<name>/`, then:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        // one entry per unzipped package repo
        maven { url = uri("third_party/maven/tantivy-android") }
    }
}
// build.gradle.kts
dependencies { implementation("ai.botisan:tantivy-android:<version>") }
```

ABIs: `arm64-v8a`, `x86_64` (64-bit only). All `.so`s are 16 KB page-size aligned (gated in `build-android.sh`). minSdk 24. JNA floor 5.17.0 (5.19.1 pinned) — earlier JNA crashes on 16 KB devices.

## Usage

```kotlin
import ai.botisan.tantivy.*

data class Receipt(val id: String, val merchant: String, val total: Double, val tagIds: List<String>)

val schema = tantivySchema {
    idField("id")                               // raw tokenizer, stored, fast
    textField("merchant")                       // unicode tokenizer (CJK-friendly), freqs+positions
    f64Field("total", indexed = false, fast = true)
    facetField("tagIds")
}

object ReceiptAdapter : TantivyDocumentAdapter<Receipt> {
    override fun encode(value: Receipt, doc: TantivyDocumentWriter) {
        doc.text("id", value.id)
        doc.text("merchant", value.merchant)
        doc.f64("total", value.total)
        value.tagIds.forEach { doc.facet("tagIds", "/tag/$it") }   // multi-value: repeat the call
    }
    override fun decode(fields: TantivyFieldMap) = Receipt(
        fields.text("id")!!, fields.text("merchant")!!, fields.f64("total")!!, fields.facets("tagIds"),
    )
}

TypedTantivyIndex.open(dir.absolutePath, schema, ReceiptAdapter).use { index ->
    index.index(receipt)                                    // add + commit
    index.searchText("coffee", limit = 10)                  // BM25 over the schema's text fields

    // Full query DSL — 1:1 with the Rust serde DSL:
    val query = TantivyQuery.Boolean(listOf(
        TantivyQuery.Clause(TantivyQuery.Occur.MUST, TantivyQuery.QueryString("coffee", listOf("merchant"))),
        TantivyQuery.Clause(TantivyQuery.Occur.MUST, TantivyQuery.Term("tagIds", TantivyValue.Facet("/tag/food"))),
    ))
    index.search(query, limit = 10)

    index.deleteDoc("id", TantivyValue.Text(receipt.id))
}
```

All operations are `suspend` (serialized internally, run on `Dispatchers.IO`). Query nodes: `All`, `Empty`, `Term`, `TermSet`, `Boolean`, `Phrase`, `PhrasePrefix`, `Range`, `Regex`, `Fuzzy`, `Exists`, `Boost`, `ConstScore`, `DisjunctionMax`, `QueryString` (with per-field fuzzy config). Dates are epoch **microseconds** (`TantivyValue.DateMicros`).

## Development

```bash
./build-android.sh     # cargo ndk (arm64-v8a + x86_64, API 24) -> 16 KB gate -> gradle test + assembleRelease
./gh-release.sh        # publish GitHub Release with maven.zip + aar + sha256 assets
cd android && ./gradlew test          # host-JVM tests (builds host dylib automatically)
cd android && ./gradlew connectedAndroidTest   # on-device/emulator smoke
```

Toolchain: Rust stable, uniffi 0.30.0, cargo-ndk 4.1.2, NDK r28, AGP 9.0.1 (built-in Kotlin), JNA 5.19.1. Kotlin bindings are generated at build time from the **host** dylib (the release profile strips the ELF `.symtab` uniffi-bindgen reads; the dynamic symbols JNA needs survive).

Note: the upstream snippet APIs are not in this mirror — they are uncommitted work in tantivy.swift; they'll arrive with the next upstream sync after they land there.

## License

MIT — see [LICENSE](LICENSE).
