package dev.betteriptv.core

/**
 * One household member's place on the device: their Continue watching, Favourites, "Not this game"
 * marks and player choices, apart from the others'. [name] is null for the Profile that took over
 * what the device kept before there were Profiles, until it is renamed: the app names it after the
 * device's language. A [kids] Profile sees only the Kids catalog (#433).
 */
data class Profile(val id: Long, val name: String?, val kids: Boolean = false) {
    companion object {
        /** The id of the Profile that took over what the device kept before there were Profiles. */
        const val FIRST_ID = 1L
    }
}

/** The household's Profiles on the device, in the order they were made, and the one it is on. */
data class Profiles(val all: List<Profile>, val currentId: Long) {
    init {
        require(all.any { it.id == currentId }) { "The current Profile is one of them" }
    }

    val current: Profile get() = all.single { it.id == currentId }
}
