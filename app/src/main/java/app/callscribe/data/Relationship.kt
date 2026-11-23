package app.callscribe.data

/**
 * Closed list of "who is this caller to me" tags, set by the AI analysis or manually by the user.
 * Stored on [RecordingEntity.relationship] (by enum name); [key] is the value used in the AI JSON schema.
 * Stable public API for tag-based policies and filters — do not rename keys without a migration.
 * Spam is deliberately not a tag: it is the separate verdict (spam calls are tagged [OTHER]).
 */
enum class Relationship(val key: String, val en: String, val he: String, private val aliases: List<String>) {
    FAMILY_FRIENDS("family_friends", "Family & friends", "משפחה וחברים",
        listOf("family", "friends", "friend", "personal", "משפחה", "חברים", "חבר", "אישי")),
    WORK("work", "Work", "עבודה",
        listOf("colleague", "colleagues", "job", "office", "עמית", "עמיתים", "קולגה", "קולגות", "משרד")),
    BUSINESS("business", "Business & suppliers", "עסקים וספקים",
        listOf("supplier", "suppliers", "contractor", "shop", "עסק", "ספק", "ספקים", "קבלן")),
    SERVICES("services", "Services & official", "שירותים ומוסדות",
        listOf("service", "official", "bank", "government", "clinic", "delivery", "שירות", "מוסדות", "בנק", "ממשלה", "קופת חולים", "משלוח", "שליח")),
    OTHER("other", "Other", "אחר", listOf("אחר"));

    /** Words that find this tag in search: key, labels and aliases, lower-case. */
    val searchTerms: List<String> by lazy { (listOf(key, en, he, name) + aliases).map { it.lowercase() } }

    companion object {
        /** Maps an AI value (or anything else) to a tag; unknown / blank → [OTHER]. */
        fun fromKey(value: String?): Relationship {
            val v = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.key == v || it.name.lowercase() == v } ?: OTHER
        }

        /** Tags whose name or alias contains [query] (e.g. "משפחה", "work", "בנק"). Needs ≥ 2 characters. */
        fun matching(query: String): Set<Relationship> {
            val q = query.trim().lowercase()
            if (q.length < 2) return emptySet()
            return entries.filter { r -> r.searchTerms.any { it.contains(q) } }.toSet()
        }
    }
}
