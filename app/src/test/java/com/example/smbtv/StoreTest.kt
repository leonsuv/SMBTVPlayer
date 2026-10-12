package com.example.smbtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoreTest {

    private lateinit var store: Store
    private val a = SmbSource(name = "a", host = "nas", share = "one")
    private val b = SmbSource(name = "b", host = "nas", share = "two")
    private val c = SmbSource(name = "c", host = "nas", share = "three")

    @Before
    fun setUp() {
        store = Store(RuntimeEnvironment.getApplication())
        listOf(a, b, c).forEach(store::addSource)
    }

    @Test
    fun updateKeepsIdAndPosition() {
        store.updateSource(b.copy(name = "renamed", user = "alice"))
        assertEquals(listOf("a", "renamed", "c"), store.sources().map { it.name })
        assertEquals(b.id, store.sources()[1].id)
    }

    @Test
    fun movesSharesWithinBounds() {
        assertTrue(store.moveSource(c.id, -1))
        assertEquals(listOf(a, c, b), store.sources())
        assertTrue(store.moveSource(a.id, 1))
        assertEquals(listOf(c, a, b), store.sources())
        assertFalse(store.moveSource(c.id, -1))
        assertFalse(store.moveSource(b.id, 1))
        assertEquals(listOf(c, a, b), store.sources())
    }

    @Test
    fun favoritesRenameMoveAndFollowTheirShare() {
        val trip = Favorite(sourceId = a.id, path = "Photos/Trip", name = "Trip")
        val kids = Favorite(sourceId = b.id, path = "Kids", name = "Kids")
        store.addFavorite(trip)
        store.addFavorite(kids)

        assertEquals(trip, store.favorite(a.id, "Photos/Trip"))
        assertNull(store.favorite(a.id, "Photos"))

        store.renameFavorite(trip.id, "Road trip")
        assertTrue(store.moveFavorite(kids.id, -1))
        assertEquals(listOf("Kids", "Road trip"), store.favorites().map { it.name })

        // Removing a share drops the favorites that point into it.
        store.removeSource(b.id)
        assertEquals(listOf("Road trip"), store.favorites().map { it.name })
        assertEquals(listOf(a, c), store.sources())
    }

    @Test
    fun sortIsPersistedAndKeepsFoldersFirst() {
        assertEquals(Sort(), store.sort())
        store.saveSort(Sort(SortKey.CREATED, descending = true))
        assertEquals(Sort(SortKey.CREATED, descending = true), store.sort())

        val entries = listOf(
            SmbEntry("b.jpg", false, 1, created = 300),
            SmbEntry("Zfolder", true, 0, created = 100),
            SmbEntry("a.mp4", false, 1, created = 200),
            SmbEntry("c.mov", false, 1, created = 400),
        )
        assertEquals(
            listOf("Zfolder", "c.mov", "b.jpg", "a.mp4"),
            entries.sortedWith(store.sort().comparator()).map { it.name },
        )
        assertEquals(
            listOf("Zfolder", "a.mp4", "b.jpg", "c.mov"),
            entries.sortedWith(Sort(SortKey.NAME).comparator()).map { it.name },
        )
    }
}
