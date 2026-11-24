package app.callscribe.providers

import app.callscribe.data.Relationship
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Prompt and JSON schema shared by every [CallAnalyzer], so all providers return the same [Analysis]. */
object AnalysisPrompt {
    val SYSTEM = """
        You analyse transcripts of phone calls received by an Israeli user. Transcripts come from automatic
        Hebrew speech recognition and may contain errors, missing words or English fragments.
        Write summary_he and caller_intent in Hebrew.
        summary_he is 1-3 short sentences about what was said: the topic, requests, answers, decisions, dates,
        amounts and follow-ups. Write it like a note, straight to the point, with no preamble.
        Do NOT describe who the caller is or how they relate to the user, the tone or kind of call (short,
        personal, friendly, business), whether it is spam, or what the call did not contain (e.g. "no request
        for money") — those are shown separately.
        DO mention how the call went when it matters for understanding it: it was cut off or ended abruptly,
        nobody answered or it reached voicemail, the user was put on hold, it was a recorded message, one side
        couldn't hear the other, a wrong number, or the call screening assistant answered.
        Example: "אבא שאל אם יש שמן לחנוכה וסיפר שסיים את האימון. השיחה נותקה באמצע." — not
        "המתקשר הוא אבא של המשתמש, השיחה קצרה ומשפחתית ואין בה בקשה כספית."
        Judge whether the call is unwanted. Recorded or robotic messages, telemarketing, loan / insurance /
        investment offers, surveys and political campaigns are spam. Anything asking for codes, ID numbers,
        passwords or payment details is scam_or_phishing. Calls from people the user plausibly knows,
        deliveries, appointments and businesses the user deals with are not spam.
        Samsung's caller-ID label (if given) is a strong hint but can be wrong; the transcript decides.
        A caller saved as a contact defaults to not_spam unless the transcript clearly says otherwise.
        If the call was screened, an AI assistant voice may speak on the user's behalf; focus on what the caller said.
        If the transcript is too short or unclear, use verdict "unsure". Never invent details not in the transcript.
        spam_confidence is the probability (0-1) that the call is unwanted.
        relationship says who the caller is to the user. Pick exactly one:
        - family_friends: relatives, partner, friends, neighbours; personal chats ("אמא", "אבא", "אחי", "סבתא",
          "מה נשמע", "מתי באים לארוחה"), a contact saved by first name or nickname talking about personal matters.
        - work: the user's own job: colleagues, manager, employees, the employer's clients, shifts, meetings,
          projects, recruiters about the user's job ("הבוס", "הישיבה", "המשמרת", "הפרויקט", "הלקוח שלנו").
        - business: a business the user personally buys from or hired: suppliers, contractors, tradespeople,
          shops, garage, landlord, lawyer / accountant, price quotes and orders ("הצעת מחיר", "ההזמנה שלך",
          "האינסטלטור", "החשמלאי", "המוסך").
        - services: institutions and public services: banks, credit cards, insurance, health funds, clinics and
          hospitals, government, municipality, ביטוח לאומי, utilities, couriers and deliveries, appointment
          reminders ("קופת חולים", "הבנק", "שליח", "העירייה", "תור לרופא").
        - other: anything else: strangers, wrong numbers, spam, telemarketing, scams, surveys, or too unclear.
        Spam and telemarketing are always "other" (the verdict covers spam). The caller name is a hint
        (e.g. "אמא", "Bank", "Dr."). If unsure between tags, prefer "other".
        Reply with JSON only, matching the given schema.
    """.trimIndent()

    fun user(transcript: String, meta: CallMeta): String {
        val cut = transcript.length > 30_000
        return buildString {
            appendLine("Call metadata:")
            appendLine("- Caller as shown on the phone: ${meta.counterparty ?: "(hidden)"}")
            appendLine("- Caller type: ${meta.labelKind}")
            meta.samsungLabel?.let { appendLine("- Samsung caller-ID labelled this call: $it") }
            appendLine("- Duration: ${meta.durationSec} s")
            appendLine("- Local time: ${meta.localTime}")
            appendLine()
            appendLine("<transcript>")
            appendLine(if (cut) transcript.take(30_000) else transcript)
            appendLine("</transcript>")
            if (cut) appendLine("(The transcript was cut at 30,000 characters.)")
        }
    }

    private fun str(desc: String) = mapOf("type" to "string", "description" to desc)

    val PROPERTIES: Map<String, Any> = linkedMapOf(
        "summary_he" to str("1-3 sentence Hebrew note of what was said, plus how the call went if relevant (cut off, on hold...); no caller description, tone or spam judgement"),
        "verdict" to mapOf("type" to "string", "enum" to listOf("spam", "not_spam", "unsure")),
        "spam_confidence" to mapOf("type" to "number", "description" to "0.0-1.0 probability the call is unwanted"),
        "category" to mapOf(
            "type" to "string",
            "enum" to listOf(
                "robocall", "telemarketing", "scam_or_phishing", "survey_or_political",
                "business", "personal", "delivery_or_service", "other",
            ),
        ),
        "caller_intent" to str("One short Hebrew line: what the caller wants"),
        "action_items" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
        "relationship" to mapOf(
            "type" to "string",
            "enum" to Relationship.entries.map { it.key },
            "description" to "Who the caller is to the user",
        ),
    )

    /** Full JSON schema as a plain map (for SDKs that take maps). */
    fun schemaMap(strict: Boolean = true): Map<String, Any> = buildMap {
        put("type", "object")
        put("properties", PROPERTIES)
        put("required", PROPERTIES.keys.toList())
        if (strict) put("additionalProperties", false)
    }

    fun schemaJson(strict: Boolean = true): JsonElement = toJson(schemaMap(strict))

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
        is Iterable<*> -> JsonArray(v.map(::toJson))
        else -> JsonPrimitive(v.toString())
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses the model's JSON text; tolerates ```json fences some models add. */
    fun parse(text: String): Analysis {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { json.decodeFromString(Analysis.serializer(), t) }
            .map { it.copy(relationship = Relationship.fromKey(it.relationship).key) }
            .getOrElse { throw ProviderError("Could not parse the model's JSON: ${text.take(200)}", false) }
    }

    /** The tag the AI gave, from a stored analysis JSON; null if absent (not analysed / analysed before tags). */
    fun relationshipFrom(analysisJson: String?): Relationship? =
        analysisJson?.takeIf { "\"relationship\"" in it }
            ?.let { runCatching { Relationship.fromKey(parse(it).relationship) }.getOrNull() }
}
