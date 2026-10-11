/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.OfflineModeKey
import com.metrolist.music.constants.SearchSource
import com.metrolist.music.constants.SearchSourceKey
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.utils.dataStore
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalSearchViewModelTest {
    @Test
    fun `offline search includes every downloaded song and episode without library or preview restrictions`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = ViewModelStore()
        val date = LocalDateTime.of(2026, 1, 1, 0, 0)
        context.dataStore.edit {
            it[OfflineModeKey] = true
            it[HideVideoSongsKey] = true
            it[SearchSourceKey] = SearchSource.ONLINE.name
        }
        try {
            repeat(5) {
                database.dao.insert(SongEntity(id = "stream-$it", title = "Needle stream $it", inLibrary = date))
            }
            repeat(4) {
                database.dao.insert(
                    SongEntity(
                        id = "download-$it",
                        title = "Needle download $it",
                        isDownloaded = true,
                        dateDownload = date.plusSeconds(it * 2L),
                    ),
                )
            }
            database.dao.insert(
                SongEntity(id = "episode", title = "Needle episode", isEpisode = true, isDownloaded = true, dateDownload = date.plusSeconds(1)),
            )
            database.dao.insert(
                SongEntity(id = "video", title = "Needle video", isVideo = true, isDownloaded = true, dateDownload = date.plusSeconds(10)),
            )
            database.dao.insert(PlaylistEntity(id = "playlist", name = "Needle playlist"))
            val viewModel = LocalSearchViewModel(context, MusicDatabase(database))
            store.put("search", viewModel)
            val collecting = launch { viewModel.result.collect {} }
            try {
                val expected = listOf("download-0", "episode", "download-1", "download-2", "download-3")
                viewModel.query.value = "needle"
                val all = awaitResult(viewModel) { it.map[LocalFilter.SONG]?.size == expected.size }
                assertEquals(setOf(LocalFilter.SONG), all.map.keys)
                assertEquals(expected, all.map[LocalFilter.SONG]?.map { it.id })
                assertTrue(all.map.values.flatten().all { database.dao.getSongByIdBlocking(it.id)!!.isDownloaded })
                assertEquals(SearchSource.ONLINE.name, context.dataStore.data.first()[SearchSourceKey])

                for (filter in listOf(LocalFilter.ALBUM, LocalFilter.ARTIST, LocalFilter.PLAYLIST)) {
                    viewModel.filter.value = filter
                    assertTrue(awaitResult(viewModel) { it.filter == filter }.map.isEmpty())
                }
                viewModel.filter.value = LocalFilter.SONG
                assertEquals(expected, awaitResult(viewModel) { it.filter == LocalFilter.SONG }.map[LocalFilter.SONG]?.map { it.id })
                context.dataStore.edit { it[HideVideoSongsKey] = false }
                assertEquals(expected + "video", awaitResult(viewModel) { it.map[LocalFilter.SONG]?.size == 6 }.map[LocalFilter.SONG]?.map { it.id })

                viewModel.query.value = "missing"
                assertTrue(awaitResult(viewModel) { it.query == "missing" }.map.isEmpty())
                viewModel.query.value = ""
                assertTrue(awaitResult(viewModel) { it.query.isEmpty() }.map.isEmpty())

                viewModel.filter.value = LocalFilter.ALL
                viewModel.query.value = "needle"
                context.dataStore.edit { it[OfflineModeKey] = false }
                val online = awaitResult(viewModel) { it.map.containsKey(LocalFilter.PLAYLIST) }
                assertEquals(3, online.map[LocalFilter.SONG]?.size)
                assertTrue(online.map[LocalFilter.SONG]!!.all { it.id.startsWith("stream-") })
            } finally {
                collecting.cancel()
                store.clear()
            }
        } finally {
            context.dataStore.edit {
                it.remove(OfflineModeKey)
                it.remove(HideVideoSongsKey)
                it.remove(SearchSourceKey)
            }
            database.close()
        }
    }

    private suspend fun awaitResult(
        viewModel: LocalSearchViewModel,
        matches: (LocalSearchResult) -> Boolean,
    ): LocalSearchResult = withTimeout(10_000) {
        while (!matches(viewModel.result.value)) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(10)
        }
        viewModel.result.value
    }
}
