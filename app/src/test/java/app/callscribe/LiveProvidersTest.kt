package app.callscribe

import app.callscribe.providers.CallAnalyzer
import app.callscribe.providers.CallMeta
import app.callscribe.providers.gemini.GeminiAnalyzer
import app.callscribe.providers.openrouter.OpenRouterAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Calls the real APIs with the app's own analyzer classes. Skipped unless CALLSCRIBE_LIVE=1 and the key is set,
 * so normal test runs never spend money. One request per provider.
 */
class LiveProvidersTest {
    private val transcript = """
        שלום, זוהי הודעה מוקלטת מחברת אשראי פלוס. אושרה עבורך הלוואה של עד חמישים אלף שקל ללא בדיקת היסטוריה.
        כדי לקבל את הכסף כבר היום, הקש אחת או השאר את מספר תעודת הזהות שלך לאחר הצליל.
    """.trimIndent()
    private val meta = CallMeta("+972501234567", null, "UNKNOWN_NUMBER", 24, "Mon 2026-11-23 10:15")

    private fun live(key: String): String? =
        System.getenv(key)?.takeIf { System.getenv("CALLSCRIBE_LIVE") == "1" && it.isNotBlank() }

    private fun check(an: CallAnalyzer) = runBlocking {
        println("[${an.id}] test(): ${an.test()}")
        val r = an.analyze(transcript, meta)
        println("[${an.id}] verdict=${r.analysis.verdict} conf=${r.analysis.spam_confidence} category=${r.analysis.category}")
        println("[${an.id}] intent=${r.analysis.caller_intent}")
        println("[${an.id}] summary=${r.analysis.summary_he}")
        println("[${an.id}] tokens=${r.inputTokens}/${r.outputTokens} cost=$${"%.6f".format(r.cost?.usd ?: 0.0)}")
        assertEquals("spam", r.analysis.verdict)
    }

    @Test fun gemini() {
        val key = live("GEMINI_API_KEY"); assumeTrue(key != null)
        val model = System.getenv("CALLSCRIBE_GEMINI_MODEL") ?: "gemini-3.5-flash-lite"
        check(GeminiAnalyzer(key, model, rpm = 15, priceInPerM = 0.0, priceOutPerM = 0.0))
    }

    @Test fun openRouter() {
        val key = live("OPENROUTER_API_KEY"); assumeTrue(key != null)
        check(OpenRouterAnalyzer(key, "anthropic/claude-haiku-5.5"))
    }
}
