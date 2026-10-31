package dev.betteriptv.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The search index's primitive containers (#63), past the sizes where their arrays grow. */
class PackedTest {
    @Test fun `strings keep one number each, through growth`() {
        val table = StringTable()
        val words = List(5_000) { "word$it" } + "" + "été"
        val numbers = words.map(table::add)
        assertEquals(words.indices.toList(), numbers)
        assertEquals(numbers, words.map(table::add))
        table.freeze()
        assertEquals(words, List(table.size) { table[it] })
        assertEquals("word42", table.view().of(42).toString())
    }

    @Test fun `postings come out in order, each value once`() {
        val builder = PostingsBuilder()
        for (value in listOf(5, 5, 3, 9, 3, 1)) builder.add(0, value)
        for (value in 0 until 3_000) builder.add(7, value)
        builder.add(2, 4)
        val postings = builder.freeze(9)
        assertArrayEquals(intArrayOf(1, 3, 5, 9), postings[0])
        assertArrayEquals(intArrayOf(), postings[1])
        assertArrayEquals(intArrayOf(4), postings[2])
        assertArrayEquals(IntArray(3_000) { it }, postings[7])
        assertArrayEquals(intArrayOf(), postings[8])
    }

    @Test fun `longs map to ints, through growth`() {
        val map = LongIntMap()
        for (i in 0 until 10_000) map[i * 1_000_003L - 5_000] = i
        for (i in 0 until 10_000) assertEquals(i, map[i * 1_000_003L - 5_000])
        assertEquals(-1, map[7])
        map[-5_000] = 42
        assertEquals(42, map[-5_000])
    }

    @Test fun `Titles of either kind, Matched or not, are found by their position`() {
        val ids = listOf(
            TitleId.Tmdb(TitleKind.Movie, 603), TitleId.Tmdb(TitleKind.Show, 603),
            TitleId.Unmatched(TitleKind.Movie, 603), TitleId.Unmatched(TitleKind.Show, 9_000_000_000),
        )
        val builder = SearchIndex.Builder()
        ids.forEach { builder.title(it, listOf("The Matrix"), setOf("en")) }
        val index = builder.build()
        assertEquals(ids, List(index.titleCount) { index.titleId(it) })
        assertEquals(ids.indices.toList(), ids.map(index::position))
        assertNull(index.position(TitleId.Tmdb(TitleKind.Movie, 604)))
        assertEquals(ids.indices.toSet(), index.findTitles("matrix").map { it.index }.toSet())
    }
}
