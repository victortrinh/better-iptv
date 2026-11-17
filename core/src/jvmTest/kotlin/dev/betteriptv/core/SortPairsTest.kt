package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** The search index's names are written in hash order, sorted without boxing a number (#567). */
class SortPairsTest {
    @Test fun `pairs sort by their first number, then their second, as a sort of pairs does`() {
        val random = Random(567)
        for (count in listOf(0, 1, 2, 3, 10, 1_000)) {
            // Few distinct first numbers, so ties on it are common; extremes, so no subtraction overflows.
            val pairs = List(count) { listOf(Long.MIN_VALUE, -1L, 0L, 7L, Long.MAX_VALUE).random(random) to random.nextLong(-3, 3) }
            val array = LongArray(count * 2 + 4) { -42 }
            pairs.forEachIndexed { i, (first, second) -> array[i * 2] = first; array[i * 2 + 1] = second }

            sortPairs(array, count)

            val sorted = pairs.sortedWith(compareBy({ it.first }, { it.second }))
            assertEquals(sorted, List(count) { array[it * 2] to array[it * 2 + 1] })
            // What lies past the pairs is left alone.
            assertEquals(List(4) { -42L }, array.drop(count * 2))
        }
    }
}
