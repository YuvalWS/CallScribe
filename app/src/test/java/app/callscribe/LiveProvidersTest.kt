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

    /** Made-up Hebrew calls, one per tag. Gemini only (free tier; spaced by the analyzer's rpm limiter). */
    private val tagCases = listOf(
        Triple("family_friends", CallMeta("אמא", null, "CONTACT", 40, "Fri 2026-11-20 12:30"), """
            היי מותק, זאת אמא. רציתי לשאול אם אתם באים לארוחת שישי הערב. סבתא מאוד רוצה לראות את הילדים.
            תביא גם את הסלט שאבא אוהב. תתקשר אליי כשאתה מתפנה, נשיקות.
        """.trimIndent()),
        Triple("work", CallMeta("דני מהמשרד", null, "CONTACT", 55, "Mon 2026-11-23 09:10"), """
            בוקר טוב, זה דני. הישיבה עם הצוות של הפרויקט זזה לשתיים במקום לאחת עשרה.
            המנהלת ביקשה שתכין את המצגת לרבעון ותשלח לי לפני כן. גם תעדכן את הלקוח שלנו שהמסירה נדחית בשבוע.
        """.trimIndent()),
        Triple("business", CallMeta("+972521112233", null, "UNKNOWN_NUMBER", 48, "Tue 2026-11-24 15:00"), """
            שלום, מדבר יוסי מחומרי בניין השרון. ההזמנה שלך של הקרמיקה והדבק הגיעה למחסן.
            אני צריך שתאשר את הצעת המחיר שנשלחה אליך ואת מועד האספקה ליום רביעי בבוקר. תודה.
        """.trimIndent()),
        Triple("other", CallMeta("+972541239876", null, "UNKNOWN_NUMBER", 15, "Wed 2026-11-25 18:40"), """
            הלו? רינה? אה, סליחה, כנראה טעיתי במספר. חיפשתי את רינה כהן. סליחה על ההפרעה, יום טוב.
        """.trimIndent()),
    )

    @Test fun geminiTags() = runBlocking {
        val key = live("GEMINI_API_KEY"); assumeTrue(key != null)
        val model = System.getenv("CALLSCRIBE_GEMINI_MODEL") ?: "gemini-3.5-flash-lite"
        val an = GeminiAnalyzer(key!!, model, rpm = 15, priceInPerM = 0.0, priceOutPerM = 0.0)
        val results = tagCases.map { (want, meta, text) ->
            val r = an.analyze(text, meta).analysis
            println("[gemini tags] want=$want got=${r.relationship} verdict=${r.verdict} category=${r.category} intent=${r.caller_intent}")
            want to r.relationship
        }
        assertEquals(results.map { it.first }, results.map { it.second })
    }
}
