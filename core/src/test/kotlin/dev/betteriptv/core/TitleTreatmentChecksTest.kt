package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checks a Title treatment passes before it is shown (ADR 0016), on what Survey C recorded for its
 * 60 posters (2026-11-08, `.survey/artwork/treatment/report.md`): the recognised text, and the luma,
 * chroma and coverage of the colour key. The Mac rule (`scripts/survey_treatment.py`) accepted 45 and
 * rejected 15; the device's rule gives the same verdicts. The names are as the survey recorded them, a year
 * and all where the Provider's name had one.
 */
class TitleTreatmentChecksTest {
    private class Recorded(val name: String, val text: String, val luma: Double, val chroma: Double, val coverage: Double, val verdict: TreatmentVerdict)

    private fun accepted(name: String, text: String, luma: Double, chroma: Double, coverage: Double) = Recorded(name, text, luma, chroma, coverage, TreatmentVerdict.Accepted)
    private fun rejected(verdict: TreatmentVerdict, name: String, text: String, luma: Double, chroma: Double, coverage: Double) = Recorded(name, text, luma, chroma, coverage, verdict)

    private val survey = listOf(
        rejected(TreatmentVerdict.NoText, "STEEL BALL RUN JoJo's Bizarre Adventure (2026) (US) جوجو استیل بال ران", "", 0.0, 0.0, 0.0),
        rejected(TreatmentVerdict.NotTheName, "Avengers: Endgame", "VEELLRS", 0.49, 0.44, 0.32),
        rejected(TreatmentVerdict.NotTheName, "Despicable Me", "TMM | 1160", 0.16, 0.12, 0.34),
        rejected(TreatmentVerdict.NotTheName, "Bolt", "MaL | STAR MALGRE LUK", 0.88, 0.04, 0.55),
        rejected(TreatmentVerdict.NotTheName, "Soul", "ไกยร", 0.58, 0.74, 0.34),
        rejected(TreatmentVerdict.DarkTitle, "Pranked by Melvin", "PRANKED | by Melvin", 0.05, 0.00, 0.24),
        rejected(TreatmentVerdict.NotTheName, "Blood Sacrifice", "업 00$0F년", 0.22, 0.33, 0.43),
        rejected(TreatmentVerdict.DarkTitle, "OK! Let's Get Divorced (2026) (KR) بیا طلاق بگیریم", "OK! | Let's Get | Divorced", 0.28, 0.25, 0.22),
        rejected(TreatmentVerdict.NotTheName, "The Killings at Parrish Station", "Stanoriginals.", 0.62, 0.55, 0.26),
        rejected(TreatmentVerdict.NotTheName, "The Furious (2026)", "REALISE PAR KENJI TANIOAN", 0.45, 0.51, 0.43),
        rejected(TreatmentVerdict.NotTheName, "Твоё сердце будет разбито (2026)", "CORAÇÃO | PARTIDO", 0.97, 0.02, 0.37),
        rejected(TreatmentVerdict.NotTheName, "\"Wuthering Heights\" (2026)", "negrs", 0.45, 0.42, 0.31),
        rejected(TreatmentVerdict.DarkTitle, "Corpse Bride", "Porfs | Brde", 0.10, 0.04, 0.15),
        rejected(TreatmentVerdict.DarkTitle, "Le Maître d'armes (2006)", "JET LI'S | LE MAITRE D'ARMES | UN FILM DE RONNY YU", 0.10, 0.06, 0.05),
        rejected(TreatmentVerdict.NotTheName, "Rex - Vienna Calling", "REX", 0.06, 0.13, 0.44),
        accepted("Oppenheimer", "OPPENHEIMER", 0.49, 0.69, 0.19),
        accepted("Zootopia", "ZOOTOPIA", 0.35, 0.18, 0.36),
        accepted("The Dark Knight", "THE DARK KNIGHT", 0.85, 0.14, 0.19),
        accepted("Moana 2", "MOANA 2", 0.98, 0.02, 0.22),
        accepted("Skyscraper", "SKYSCRAPER", 0.82, 0.17, 0.27),
        accepted("Shōgun", "SHOGUN", 0.69, 0.40, 0.10),
        accepted("Inception", "INCEPTION", 0.34, 0.68, 0.24),
        accepted("The Housemaid", "THE | HOUSEMAID", 0.84, 0.85, 0.24),
        accepted("The Lord of the Rings: The Fellowship of the Ring", "TORDRRINGS", 0.70, 0.37, 0.22),
        accepted("Dutton Ranch", "DUTTON | IRANCH", 0.70, 0.25, 0.40),
        accepted("The Revenant", "THE REVENANT", 0.97, 0.01, 0.22),
        accepted("The Shawshank Redemption", "THE | SHANHANK | PTI", 0.88, 0.02, 0.07),
        accepted("I Will Find You", "TWILL FIND YOU", 0.57, 0.07, 0.47),
        accepted("Marshals", "MARSHALS", 0.71, 0.30, 0.27),
        accepted("You", "YOU", 0.97, 0.02, 0.23),
        accepted("Knives Out", "Knives", 0.75, 0.88, 0.40),
        accepted("The Mongoose (2026)", "nOnGCOSE", 0.44, 0.06, 0.49),
        accepted("YAGA", "YAGA", 0.28, 0.75, 0.24),
        accepted("Swipe Right on the Devil", "SWIPE RIGHT | ON | THE DEVIL", 0.94, 0.05, 0.15),
        accepted("Sumukhi Suresh: Hoemonal (2026)", "• Sumukhi Suresh• | HOEMONAL", 0.68, 0.13, 0.58),
        accepted("Tenzing", "TENZING", 0.97, 0.02, 0.13),
        accepted("Doctor X: Mafia in White", "DOCTOR | MAFIAIN | WHITE", 0.91, 0.03, 0.29),
        accepted("The Last First: Winter K2 (2026)", "4K ULTRAHD | THE LAST FIRST: | WINTER KP", 0.58, 0.03, 0.33),
        accepted("Digger (2026)", "DIGGER", 0.89, 0.16, 0.41),
        accepted("Husdrømmen Med Halvor Bakke", "HUSDRØMMEN | MED HALVOR BAKKE", 0.95, 0.07, 0.14),
        accepted("Heart of the Beast (2026)", "HEARTOF | THEBEAST", 0.94, 0.01, 0.23),
        accepted("Ali Baba aur 40 Bhoot", "hotstar specials | ALI BABA | 40 BHOOT", 0.78, 0.08, 0.30),
        accepted("The Fix (2026)", "THE | FIX", 0.97, 0.00, 0.15),
        accepted("Onslaught", "ONSLAUCHT", 0.39, 0.71, 0.42),
        accepted("Uttar Da Puttar", "UAAR | PUTTAR", 0.58, 0.69, 0.60),
        accepted("Divergent", "DIVERGENT", 0.90, 0.12, 0.30),
        accepted("Honeymoon Travels Pvt. Ltd.", "Молауиcв | TRAVELS PVT. LTD.", 0.40, 0.33, 0.33),
        accepted("Blue Lagoon: The Awakening", "BLUE | LAGOON | THE AWAKENING", 0.97, 0.07, 0.20),
        accepted("StreetDance 3D (2010)", "STREETDANCE", 0.59, 0.19, 0.47),
        accepted("Britain and the Blitz", "BRITAIN | ANDTHE BLTZ", 0.88, 0.01, 0.44),
        accepted("Once Upon a Snowman", "ONCE UPON A | SNOWMAN", 0.66, 0.28, 0.24),
        accepted("Death of a Unicorn", "DEATH | OFA | UNICORN", 0.19, 0.63, 0.33),
        accepted("Vanderpump Villa", "VANDERPUMP | VILLA", 0.96, 0.02, 0.15),
        accepted("The Faculty", "FACÜLTY", 0.93, 0.00, 0.13),
        accepted("0187 UFO (2026)", "0187 UFO", 0.63, 0.63, 0.41),
        accepted("枪神赵子龙", "GOD OF SPEAR", 0.54, 0.33, 0.33),
        accepted("Our Little Secret (2024)", "OurLittle | Secret", 0.71, 0.45, 0.30),
        accepted("Les enfants vont bien (2025)", "LES | ENFANTS | VONT | BIEN", 0.89, 0.10, 0.12),
        accepted("Hillbilly Elegy (2020)", "HILLBILLY | ELEGY", 0.97, 0.01, 0.15),
        accepted("Diarra from Detroit", "DIARRA FROM | DETROIT", 0.87, 0.09, 0.26),
    )

    @Test fun `Survey C's posters get the verdicts the Mac rule gave them`() {
        val wrong = survey.filter { treatmentVerdict(it.name, it.text, it.luma, it.chroma, it.coverage) != it.verdict }
        assertEquals("verdicts that differ: ${wrong.map { it.name }}", emptyList<String>(), wrong.map { it.name })
        assertEquals(listOf(45, 15), listOf(survey.count { it.verdict == TreatmentVerdict.Accepted }, survey.count { it.verdict != TreatmentVerdict.Accepted }))
    }

    @Test fun `a poster with no text recognised has no treatment`() {
        assertEquals(TreatmentVerdict.NoText, treatmentVerdict("STEEL BALL RUN JoJo's Bizarre Adventure", "", 0.0, 0.0, 0.0))
    }

    @Test fun `a key that kept too little or too much of the crop is refused`() {
        assertEquals(TreatmentVerdict.KeyFailed, treatmentVerdict("Oppenheimer", "OPPENHEIMER", 0.49, 0.69, 0.02))
        assertEquals(TreatmentVerdict.KeyFailed, treatmentVerdict("Oppenheimer", "OPPENHEIMER", 0.49, 0.69, 0.71))
        assertEquals(TreatmentVerdict.Accepted, treatmentVerdict("Oppenheimer", "OPPENHEIMER", 0.49, 0.69, 0.03))
        assertEquals(TreatmentVerdict.Accepted, treatmentVerdict("Oppenheimer", "OPPENHEIMER", 0.49, 0.69, 0.70))
    }

    @Test fun `a saturated title dark in luma still shows`() {
        assertEquals(TreatmentVerdict.Accepted, treatmentVerdict("Death of a Unicorn", "DEATH | OFA | UNICORN", 0.19, 0.63, 0.33))
        assertEquals(TreatmentVerdict.DarkTitle, treatmentVerdict("Death of a Unicorn", "DEATH | OFA | UNICORN", 0.29, 0.29, 0.33))
    }

    @Test fun `the name's words are folded, short words and articles dropped`() {
        assertEquals(listOf("shogun"), treatmentNameWords("Shōgun"))
        assertEquals(listOf("fellowship", "lord", "ring", "rings"), treatmentNameWords("The Lord of the Rings: The Fellowship of the Ring"))
        // Digits count whatever their length; "2" is a word of "Moana 2".
        assertEquals(listOf("2", "moana"), treatmentNameWords("Moana 2"))
    }

    @Test fun `one misread letter in three is allowed`() {
        assertTrue(treatmentNameMatches("Onslaught", "ONSLAUCHT"))
        assertTrue(treatmentNameMatches("The Mongoose", "nOnGCOSE"))
        assertFalse(treatmentNameMatches("Avengers: Endgame", "VEELLRS"))
    }

    @Test fun `a title set in spaced letters is read across the spaces`() {
        // ML Kit's reading of Shōgun's poster, its letters spread wide (2026-11-08, on the emulator).
        assertTrue(treatmentNameMatches("Shōgun", "SH O GUN"))
        assertTrue(treatmentNameMatches("Oppenheimer", "O P P E N H E I M E R"))
    }

    @Test fun `a word misread inside a run of letters is still found`() {
        // ML Kit's reading of the Fellowship's logo, its words run together and an R read as P (2026-11-08, on the emulator).
        assertTrue(treatmentNameMatches("The Lord of the Rings: The Fellowship of the Ring", "ÖRDPINGS"))
        // A three-letter word isn't looked for misread inside a run: "you" isn't found in "YOKOHAMA".
        assertFalse(treatmentNameMatches("You", "YOKOHAMA"))
    }

    @Test fun `a name with no word to look for matches any text`() {
        assertTrue(treatmentNameMatches("枪神赵子龙", "GOD OF SPEAR"))
    }
}
