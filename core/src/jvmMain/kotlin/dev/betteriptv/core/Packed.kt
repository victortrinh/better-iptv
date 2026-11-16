package dev.betteriptv.core

/*
 * The search index's containers: millions of words, names and postings kept in a few large
 * primitive arrays instead of millions of objects. Android's garbage collector marks such an array
 * once, never scans it and never moves it, where it copied every String, IntArray and map entry of
 * the index at each collection. On the stick that made each collection ~4× longer while the index
 * was held or being built (#63).
 */

/** Strings kept end to end in one CharArray, each known by its number, in the order added. */
internal class StringTable {
    private var chars = CharArray(1 shl 12)
    private var ends = IntArray(1 shl 8)
    private var length = 0

    /** Open addressing: each slot a string's number plus one, 0 when empty. Let go by [freeze]. */
    private var slots = IntArray(1 shl 9)

    var size = 0
        private set

    fun start(number: Int): Int = if (number == 0) 0 else ends[number - 1]

    fun end(number: Int): Int = ends[number]

    operator fun get(number: Int): String = String(chars, start(number), end(number) - start(number))

    /** [text]'s number, adding it when it is new. */
    fun add(text: String): Int {
        var slot = spread(text.hashCode()) and (slots.size - 1)
        while (slots[slot] != 0) {
            val number = slots[slot] - 1
            if (sameAs(number, text)) return number
            slot = (slot + 1) and (slots.size - 1)
        }
        if (length + text.length > chars.size) chars = chars.copyOf(maxOf(chars.size * 2, length + text.length))
        text.toCharArray(chars, length)
        length += text.length
        if (size == ends.size) ends = ends.copyOf(size * 2)
        ends[size++] = length
        slots[slot] = size
        if (size * 2 > slots.size) rehash()
        return size - 1
    }

    /** No more strings come: the arrays are trimmed and the lookup table let go. */
    fun freeze(): StringTable {
        chars = chars.copyOf(length)
        ends = ends.copyOf(size)
        slots = IntArray(0)
        return this
    }

    /** A reusable view of one string at a time, so matching the vocabulary allocates nothing per word. */
    fun view(): View = View()

    inner class View : CharSequence {
        private var start = 0
        override var length = 0
            private set

        fun of(number: Int): View {
            start = start(number)
            length = end(number) - start
            return this
        }

        override fun get(index: Int): Char = chars[start + index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = String(chars, start + startIndex, endIndex - startIndex)

        override fun toString(): String = String(chars, start, length)
    }

    private fun sameAs(number: Int, text: String): Boolean {
        val start = start(number)
        if (end(number) - start != text.length) return false
        for (i in text.indices) if (chars[start + i] != text[i]) return false
        return true
    }

    private fun rehash() {
        slots = IntArray(slots.size * 2)
        for (number in 0 until size) {
            var hash = 0
            for (at in start(number) until end(number)) hash = 31 * hash + chars[at].code
            var slot = spread(hash) and (slots.size - 1)
            while (slots[slot] != 0) slot = (slot + 1) and (slots.size - 1)
            slots[slot] = number + 1
        }
    }

    private fun spread(hash: Int) = hash xor (hash ushr 16)
}

/**
 * Sets of ints, one per list number, added to in any order without boxing: each value a link in a
 * chain through two arrays. A value added twice in a row to a list is kept once; [freeze] sorts
 * and removes the other repeats.
 */
internal class PostingsBuilder {
    private var values = IntArray(1 shl 10)
    private var next = IntArray(1 shl 10)
    private var links = 0
    private var heads = IntArray(1 shl 8) { -1 }
    private var tails = IntArray(1 shl 8)
    private var unsorted = BooleanArray(1 shl 8)
    private var lists = 0

    fun add(list: Int, value: Int) {
        if (list >= heads.size) grow(list)
        lists = maxOf(lists, list + 1)
        val tail = tails[list]
        if (heads[list] >= 0) {
            val last = values[tail]
            if (last == value) return
            if (last > value) unsorted[list] = true
        }
        if (links == values.size) {
            values = values.copyOf(links * 2)
            next = next.copyOf(links * 2)
        }
        values[links] = value
        next[links] = -1
        if (heads[list] < 0) heads[list] = links else next[tail] = links
        tails[list] = links++
    }

    /** The lists as [lists] packed sets, each in order and without repeats. */
    fun freeze(lists: Int = this.lists): PackedPostings {
        val packed = IntArray(links)
        val ends = IntArray(lists)
        var at = 0
        for (list in 0 until lists) {
            val start = at
            var link = if (list < heads.size) heads[list] else -1
            while (link >= 0) {
                packed[at++] = values[link]
                link = next[link]
            }
            if (list < unsorted.size && unsorted[list]) {
                packed.sort(start, at)
                var kept = start
                for (i in start until at) if (kept == start || packed[kept - 1] != packed[i]) packed[kept++] = packed[i]
                at = kept
            }
            ends[list] = at
        }
        return PackedPostings(packed.copyOf(at), ends)
    }

    private fun grow(list: Int) {
        var size = heads.size
        while (size <= list) size *= 2
        val before = heads.size
        heads = heads.copyOf(size).also { it.fill(-1, before) }
        tails = tails.copyOf(size)
        unsorted = unsorted.copyOf(size)
    }
}

/** Sets of ints, each list's values in order, end to end in one array. */
internal class PackedPostings(private val values: IntArray, private val ends: IntArray) {
    val size: Int get() = ends.size

    fun start(list: Int): Int = if (list == 0) 0 else ends[list - 1]

    fun count(list: Int): Int = ends[list] - start(list)

    operator fun get(list: Int): IntArray = values.copyOfRange(start(list), ends[list])

    fun value(at: Int): Int = values[at]

    fun end(list: Int): Int = ends[list]
}

/** Words, each with the entries (Titles, people, Channels or genres) that have it. */
internal class WordPostings(val words: StringTable, val entries: PackedPostings) {
    class Builder {
        private val words = StringTable()
        private val entries = PostingsBuilder()

        fun add(word: String, entry: Int) = entries.add(words.add(word), entry)

        fun build() = WordPostings(words.freeze(), entries.freeze(words.size))
    }

    companion object {
        val NONE = Builder().build()
    }
}

/** Longs to ints by open addressing, without boxing either. */
internal class LongIntMap {
    private var keys = LongArray(1 shl 10)
    private var values = IntArray(1 shl 10) { -1 }
    private var size = 0

    operator fun set(key: Long, value: Int) {
        val slot = slotOf(key)
        if (values[slot] < 0) size++
        keys[slot] = key
        values[slot] = value
        if (size * 2 > keys.size) rehash()
    }

    /** [key]'s value, or -1 when it has none. */
    operator fun get(key: Long): Int = values[slotOf(key)]

    private fun slotOf(key: Long): Int {
        var slot = spread(key) and (keys.size - 1)
        while (values[slot] >= 0 && keys[slot] != key) slot = (slot + 1) and (keys.size - 1)
        return slot
    }

    private fun rehash() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2)
        values = IntArray(oldKeys.size * 2) { -1 }
        for (i in oldKeys.indices) if (oldValues[i] >= 0) {
            val slot = slotOf(oldKeys[i])
            keys[slot] = oldKeys[i]
            values[slot] = oldValues[i]
        }
    }

    private fun spread(key: Long): Int {
        val hash = (key xor (key ushr 32)).toInt() * -0x61c88647
        return hash xor (hash ushr 16)
    }
}
