package dev.betteriptv.core

import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * The tests that search, run again with the switch on ([Library.searchInMemory]): the index built in
 * memory at each launch stays in the code until the Fire TV Stick has been checked (#585), and must
 * answer as it did until it is removed (#586).
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    SearchTest::class,
    SearchScreenTest::class,
    KidsCatalogTest::class,
    ChannelsTest::class,
    ChannelVariantsTest::class,
    ReplaysTest::class,
    ProfilesTest::class,
    HeadStartTest::class,
    WithoutCloudMetadataTest::class,
)
class SearchInMemorySuite {
    companion object {
        @BeforeClass @JvmStatic fun switchOn() {
            System.setProperty(Library.SEARCH_PROPERTY, Library.SEARCH_IN_MEMORY)
        }

        @AfterClass @JvmStatic fun switchOff() {
            System.clearProperty(Library.SEARCH_PROPERTY)
        }
    }
}
