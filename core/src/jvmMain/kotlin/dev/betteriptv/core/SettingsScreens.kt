package dev.betteriptv.core

/**
 * Settings' Profiles ([ProfilesContent]): the household's, for the Profile the device is on.
 *
 * The content is worked out from the household's Profiles alone ([ProfilesContent.of]), which an
 * app already holds as live state ([Library.profiles]): one that draws them in the frame it is
 * handed them asks that way, and is given the same content as here.
 */
data object ProfilesScreen : Screen<ProfilesContent>() {
    override suspend fun read(library: Library) = ProfilesContent.of(library.profiles.value)
}

/**
 * Settings' Profiles: the household's [profiles] in the order they were made, each saying whether
 * the device is on it and what choosing it opens, and whether one more can be [adding] added.
 */
data class ProfilesContent(val profiles: List<ListedProfile>, val adding: AddingProfile) {
    companion object {
        /**
         * The list for the household's [profiles]. On a Kids profile it only switches: another
         * Profile is watched as once the Household PIN is typed (#437), its own row opens nothing,
         * and no Profile is added (#433). Elsewhere each Profile opens its page, and one can be
         * added until the household has [Library.MAX_PROFILES].
         */
        fun of(profiles: Profiles): ProfilesContent {
            val kids = profiles.current.kids
            return ProfilesContent(
                profiles.all.map { profile ->
                    val current = profile.id == profiles.currentId
                    ListedProfile(
                        profile,
                        current,
                        when {
                            !kids -> ListedProfile.Opens.Page
                            current -> null
                            else -> ListedProfile.Opens.SwitchWithPin
                        },
                    )
                },
                when {
                    kids -> AddingProfile.NotOffered
                    profiles.all.size >= Library.MAX_PROFILES -> AddingProfile.Full
                    else -> AddingProfile.Offered
                },
            )
        }
    }
}

/** One of Settings' Profiles: whether the device is on it ([current]), and what choosing it [opens], or null when nothing. */
data class ListedProfile(val profile: Profile, val current: Boolean, val opens: Opens?) {
    enum class Opens {
        /** The Profile's page: watch as it, rename it, delete it. */
        Page,

        /** The Household PIN, then the device watches as it: all a Kids profile may do. */
        SwitchWithPin,
    }
}

/** Whether Settings offers to add a Profile. */
enum class AddingProfile {
    Offered,

    /** Shown but off: the household has [Library.MAX_PROFILES]. */
    Full,

    /** Left out: a Kids profile adds none. */
    NotOffered,
}

/**
 * Settings' Content languages ([ContentLanguagesContent]), for a Viewer who had [chosen] these on
 * opening the list, in the order they chose them.
 */
data class ContentLanguagesScreen(val chosen: List<String>) : Screen<ContentLanguagesContent>() {
    override suspend fun read(library: Library) =
        ContentLanguagesContent(if (library.kids) emptyList() else (chosen + library.catalogLanguages()).distinct())
}

/**
 * The [languages] a Viewer turns on and off as Content languages, as primary language subtags: the
 * ones chosen on opening first, in the order they were chosen (the first one a Title has is the one
 * Play picks), then the rest of the catalog's, the one with most Listings first. Which are on now is
 * the Viewer's setting, which the app keeps. A Kids profile is offered none: its Settings have no
 * languages.
 */
data class ContentLanguagesContent(val languages: List<String>)

/** Settings' League picker ([LeaguesContent]). */
data object LeaguesScreen : Screen<LeaguesContent>() {
    override suspend fun read(library: Library) = if (library.kids) LeaguesContent(emptyList()) else LeaguesContent.ALL
}

/**
 * The Leagues a Viewer can follow, grouped by sport: the [sports] in [Sport]'s order, each with its
 * Leagues in [League]'s, a sport with no League left out. Which are on is the Viewer's setting,
 * which the app keeps. A Kids profile is offered none: its Settings have no Leagues.
 */
data class LeaguesContent(val sports: List<SportLeagues>) {
    companion object {
        /** Every League, by sport: the same for every Viewer outside a Kids profile, so a screen may draw it without waiting. */
        val ALL: LeaguesContent = LeaguesContent(
            Sport.entries.map { sport -> SportLeagues(sport, League.entries.filter { it.sport == sport }) }.filter { it.leagues.isNotEmpty() },
        )
    }
}

/** A [sport] and its [leagues], in [League]'s order. */
data class SportLeagues(val sport: Sport, val leagues: List<League>)

/** Settings' device list ([AccountDevicesContent]): the TVs signed in to this one's Account (#434). */
data object AccountDevicesScreen : Screen<AccountDevicesContent>() {
    override suspend fun read(library: Library): AccountDevicesContent = when {
        library.kids -> AccountDevicesContent.Listed(emptyList())
        else -> library.accountDevices()?.let { AccountDevicesContent.Listed(it) } ?: AccountDevicesContent.Unavailable
    }
}

/** What the Account's device list holds once the cloud was asked. */
sealed interface AccountDevicesContent {
    /** The cloud couldn't be reached, there is none, or this device isn't signed in. */
    data object Unavailable : AccountDevicesContent

    /**
     * The Account's [devices] in the order they signed in, this one marked
     * ([AccountDevice.thisDevice]). A Kids profile is listed none: its Settings have no Account.
     */
    data class Listed(val devices: List<AccountDevice>) : AccountDevicesContent
}
