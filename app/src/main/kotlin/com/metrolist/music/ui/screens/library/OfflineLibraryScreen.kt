/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CONTENT_TYPE_SONG
import com.metrolist.music.constants.OfflineModeKey
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.flow.combine

@Composable
fun OfflineLibraryScreen() {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    var offlineMode by rememberPreference(OfflineModeKey, defaultValue = false)
    val title = stringResource(R.string.offline_mode)
    val downloaded = remember(database) {
        combine(
            database.downloadedSongs(SongSortType.CREATE_DATE, descending = false),
            database.downloadedPodcastEpisodes(SongSortType.CREATE_DATE, descending = false),
        ) { songs, episodes ->
            (songs + episodes).sortedBy { it.song.dateDownload }
        }
    }
    val songs by downloaded.collectAsStateWithLifecycle(initialValue = emptyList())
    val isPlaying = playerConnection?.isEffectivelyPlaying?.collectAsStateWithLifecycle()?.value ?: false
    val mediaMetadata = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
    ) {
        item(key = "offline_header") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { offlineMode = false }) {
                    Text(stringResource(R.string.disable_offline_mode))
                }
            }
        }
        if (songs.isEmpty()) {
            item(key = "offline_empty") {
                Text(
                    text = stringResource(R.string.offline_mode_empty),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(
            items = songs,
            key = { _, song -> song.id },
            contentType = { _, _ -> CONTENT_TYPE_SONG },
        ) { index, song ->
            SongListItem(
                song = song,
                isActive = song.id == mediaMetadata?.id,
                isPlaying = isPlaying,
                modifier = Modifier.clickable(enabled = playerConnection != null) {
                    playerConnection?.playQueue(
                        ListQueue(
                            title = title,
                            items = songs.map { it.toMediaItem() },
                            startIndex = index,
                        ),
                    )
                },
            )
        }
    }
}
