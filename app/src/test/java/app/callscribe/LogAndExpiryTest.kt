package app.callscribe

import app.callscribe.providers.TranscriptionState
import app.callscribe.providers.ivrit.IvritRunPodTranscriber
import app.callscribe.ui.LogView
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogAndExpiryTest {
    @Test fun logEntriesKeepStackTracesWithTheirLine() {
        val text = """
            11-26 16:42:10.001 I ProcessWorker: call 5: submitted
            11-26 17:33:28.120 W ProcessWorker: provider error
            java.io.IOException: boom
                at a.b.c(X.kt:1)
            11-26 17:33:29.000 E Crash: uncaught
        """.trimIndent()
        val e = LogView.entries(text)
        assertEquals(listOf('I', 'W', 'E'), e.map { it.level })
        assertTrue(e[1].text.endsWith("at a.b.c(X.kt:1)"))
        assertEquals(listOf(false, true, true), e.map { it.problem })
        assertTrue(LogView.entries("").isEmpty())
    }

    /** RunPod answers 404 once a finished job's result is older than 30 minutes: the app should send it again. */
    @Test fun expiredJobAsksForResubmit() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                .body("""{"error":"request does not exist"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val tr = IvritRunPodTranscriber("ep", "key", "m", false, 0.00034, http = http)
        val st = tr.poll("job") as TranscriptionState.Failed
        assertTrue(st.resubmit)
        assertFalse(st.retryable)
    }
}
