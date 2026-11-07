package dev.betteriptv.core

/**
 * Finds a Sync's candidate Tier tags (#439) as its live Listings go by: a word before the colon at the
 * start of a Channel's name whose removal makes it the same Channel as another live Listing
 * (`PLUS: TNT SPORT 1 HD` beside `MAX: TNT SPORT 1 SD`, or beside `TNT SPORT 1`). Event channels
 * don't count: one Game is on several networks' channels (`NBA: Lakers vs Celtics`, `ESPN: Lakers vs
 * Celtics`). The candidates go to the cloud for the curator and are never applied.
 */
internal class CandidateTierTags(private val table: PrefixTable) {
    /** The Channel keys of the Listings with no candidate word, as they stand. */
    private val bare = HashSet<String>()

    /** Per Channel key a candidate word's removal gives, the Listings that give it: word and name. */
    private val merged = LinkedHashMap<String, MutableList<Pair<String, String>>>()

    /** Counts a Channel's live Listing named [name], whose Channel key is [key]; an Event channel is left out. */
    fun add(name: String, key: String) {
        val (word, without) = table.tierTagWord(name) ?: return run { bare += key }
        merged.getOrPut(without) { mutableListOf() } += word to name
    }

    /** The candidates, by how many Listings each merges, then by word. */
    fun candidates(): List<CandidateTierTag> {
        val found = LinkedHashMap<String, CandidateTierTag>()
        for ((key, listings) in merged) {
            val words = listings.map { it.first }.toSet()
            // A word merges here when its removal meets a Listing without it: one with no word, or another word.
            if (key !in bare && words.size < 2) continue
            for ((word, name) in listings) {
                found.merge(word, CandidateTierTag(word, 1, name)) { seen, _ -> seen.copy(listingCount = seen.listingCount + 1) }
            }
        }
        return found.values.sortedWith(compareByDescending<CandidateTierTag> { it.listingCount }.thenBy { it.word })
    }
}
