package ai.botisan.tantivy.minifiedsmoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the round-trip against the R8-minified release APK (testBuildType = release). */
@RunWith(AndroidJUnit4::class)
class MinifiedSmokeTest {

    @Test
    fun roundTripSurvivesMinification() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "minified_smoke_${System.currentTimeMillis()}")
        try {
            assertEquals("n1", MinifiedSmoke.runRoundTrip(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
