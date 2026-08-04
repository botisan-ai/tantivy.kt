package ai.botisan.tantivy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** On-device smoke: proves the packaged .so loads via JNA and a BM25 round-trip works. */
@RunWith(AndroidJUnit4::class)
class TantivySmokeTest {

    private data class Note(val id: String, val text: String)

    private object NoteAdapter : TantivyDocumentAdapter<Note> {
        override fun encode(value: Note, doc: TantivyDocumentWriter) {
            doc.text("id", value.id)
            doc.text("text", value.text)
        }

        override fun decode(fields: TantivyFieldMap): Note =
            Note(fields.text("id")!!, fields.text("text")!!)
    }

    @Test
    fun indexAndSearchOnDevice() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "tantivy_smoke_${System.currentTimeMillis()}")
        val schema = tantivySchema {
            idField("id")
            textField("text")
        }
        TypedTantivyIndex.open(dir.absolutePath, schema, NoteAdapter).use { index ->
            index.index(Note("n1", "coffee at blue bottle"))
            val hits = index.searchText("coffee", limit = 1)
            assertEquals("n1", hits.hits.first().doc.id)
        }
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun indexPersistsAcrossCloseAndReopenOnDevice() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "tantivy_reopen_${System.currentTimeMillis()}")
        val schema = tantivySchema {
            idField("id")
            textField("text")
        }
        try {
            TypedTantivyIndex.open(dir, schema, NoteAdapter).use { index ->
                index.index(Note("n1", "coffee at blue bottle"))
                assertEquals("n1", index.searchText("coffee", limit = 1).hits.first().doc.id)
            }

            TypedTantivyIndex.open(dir, schema, NoteAdapter).use { reopened ->
                assertEquals("n1", reopened.searchText("coffee", limit = 1).hits.first().doc.id)
            }
        } finally {
            dir.deleteRecursively()
        }
        Unit
    }
}
