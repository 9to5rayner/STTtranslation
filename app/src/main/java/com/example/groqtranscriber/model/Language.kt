package com.example.groqtranscriber.model

/**
 * The only two languages this app supports. A room/session always
 * translates between exactly these two — no third language, no Japanese.
 *
 * Each device picks the language IT speaks ([Language]) before joining
 * a room. The app transcribes in that language and translates into
 * [other] for display/TTS on both ends.
 */
enum class Language(val displayName: String, val flag: String) {
    INDONESIAN("Indonesian", "🇮🇩"),
    ENGLISH("English", "🇬🇧");

    /** The single other language — there are only ever two. */
    val other: Language get() = if (this == INDONESIAN) ENGLISH else INDONESIAN

    companion object {
        fun fromName(name: String?): Language =
            entries.firstOrNull { it.name == name } ?: INDONESIAN
    }
}
