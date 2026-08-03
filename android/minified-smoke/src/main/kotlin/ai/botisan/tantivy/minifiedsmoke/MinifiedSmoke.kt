package ai.botisan.tantivy.minifiedsmoke

import ai.botisan.tantivy.TantivyDocumentAdapter
import ai.botisan.tantivy.TantivyDocumentWriter
import ai.botisan.tantivy.TantivyFieldMap
import ai.botisan.tantivy.TypedTantivyIndex
import ai.botisan.tantivy.tantivySchema
import java.io.File
import kotlinx.coroutines.runBlocking

/** The round-trip the instrumentation test runs against the R8-minified APK. */
object MinifiedSmoke {

    data class Note(val id: String, val text: String)

    private object NoteAdapter : TantivyDocumentAdapter<Note> {
        override fun encode(value: Note, doc: TantivyDocumentWriter) {
            doc.text("id", value.id)
            doc.text("text", value.text)
        }

        override fun decode(fields: TantivyFieldMap): Note =
            Note(fields.text("id")!!, fields.text("text")!!)
    }

    /** Opens an index in [dir], writes a document, searches it, returns the hit's id. */
    fun runRoundTrip(dir: File): String = runBlocking {
        val schema = tantivySchema {
            idField("id")
            textField("text")
        }
        TypedTantivyIndex.open(dir, schema, NoteAdapter).use { index ->
            index.index(Note("n1", "coffee at blue bottle"))
            index.searchText("coffee", limit = 1).hits.first().doc.id
        }
    }
}
