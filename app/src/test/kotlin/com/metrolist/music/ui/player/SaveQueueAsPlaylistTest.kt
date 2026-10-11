/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SaveQueueAsPlaylistTest {
    private lateinit var database: MusicDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = MusicDatabase(
            Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                .allowMainThreadQueries()
                .build(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `click snapshot saves complete displayed order and duplicates after queue changes`() = runBlocking {
        val displayedQueue = mutableListOf(window("third"), window("first"), window("third"), window("second"))
        val snapshot = snapshotQueueForPlaylist(displayedQueue)
        displayedQueue[0].mediaItem = window("replacement").mediaItem
        displayedQueue.reverse()
        displayedQueue.clear()

        val playlist = database.saveQueueAsPlaylist("  Saved queue  ", snapshot)
        val saved = requireNotNull(database.playlistBlocking(playlist.id))
        assertEquals("Saved queue", saved.playlist.name)
        assertNotNull(saved.playlist.bookmarkedAt)
        assertNull(saved.playlist.browseId)
        assertTrue(saved.playlist.isLocal)
        assertTrue(saved.playlist.isEditable)
        assertEquals(4, saved.songCount)
        assertEquals(listOf("third", "first", "third", "second"), database.playlistSongIds(playlist.id))
        assertEquals(listOf(0, 1, 2, 3), database.playlistSongsBlocking(playlist.id).map { it.map.position })
    }

    @Test
    fun `failed song mapping insert rolls back playlist and songs before save returns`() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_queue_insert BEFORE INSERT ON playlist_song_map " +
                "WHEN NEW.position = 1 BEGIN SELECT RAISE(ABORT, 'queue save failed'); END",
        )
        val result = runCatching {
            database.saveQueueAsPlaylist("Queue", snapshotQueueForPlaylist(listOf(window("first"), window("second"))))
        }

        assertTrue(result.isFailure)
        assertEmptyDatabase()
    }

    @Test
    fun `blank names empty queues and missing metadata are rejected without partial saves`() = runBlocking {
        val snapshot = snapshotQueueForPlaylist(listOf(window("song")))
        listOf("", " \t\n ").forEach { name ->
            assertTrue(runCatching { database.saveQueueAsPlaylist(name, snapshot) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { database.saveQueueAsPlaylist("Queue", emptyList()) }.isFailure)
        assertTrue(runCatching { snapshotQueueForPlaylist(emptyList()) }.isFailure)
        assertTrue(runCatching { snapshotQueueForPlaylist(listOf(window("song"), Timeline.Window())) }.isFailure)
        assertEmptyDatabase()
    }

    private fun assertEmptyDatabase() {
        database.openHelper.readableDatabase.query(
            "SELECT (SELECT COUNT(*) FROM playlist), (SELECT COUNT(*) FROM song), (SELECT COUNT(*) FROM playlist_song_map)",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
        }
    }

    private fun window(id: String) = Timeline.Window().apply {
        mediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setUri(id)
            .setTag(MediaMetadata(id = id, title = id, artists = emptyList(), duration = 100))
            .build()
    }
}
