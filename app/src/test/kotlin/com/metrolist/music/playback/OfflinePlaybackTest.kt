package com.metrolist.music.playback

import android.app.Application
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OfflinePlaybackTest {
    private lateinit var cache: SimpleCache
    private lateinit var directory: File
    private val bytes = ByteArray(100) { it.toByte() }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "offline-playback-${System.nanoTime()}")
        cache = SimpleCache(directory, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
    }

    @After
    fun tearDown() {
        cache.release()
        directory.deleteRecursively()
    }

    @Test
    fun `complete download is readable and unknown length is bounded to local bytes`() {
        store(bytes)
        val spec = requireNotNull(offlinePlaybackDataSpec(cache, request(20), 200))
        assertEquals(80L, spec.length)
        val source = CacheDataSource.Factory().setCache(cache).createDataSource()
        val result = ByteArray(80)
        try {
            assertEquals(80L, source.open(spec))
            assertEquals(80, source.read(result, 0, result.size))
            assertEquals(-1, source.read(result, 0, result.size))
            assertArrayEquals(bytes.copyOfRange(20, 100), result)
        } finally {
            source.close()
        }
        assertEquals(80L, offlinePlaybackDataSpec(cache, request(20, 500), null)?.length)
        assertEquals(10L, offlinePlaybackDataSpec(cache, request(20, 10), null)?.length)
    }

    @Test
    fun `partial downloads missing keys and out of range positions are rejected`() {
        assertNull(offlinePlaybackDataSpec(cache, request(), 100))
        store(bytes.copyOf(50))
        assertNull(offlinePlaybackDataSpec(cache, request(10, 20), 100))
        assertNull(offlinePlaybackDataSpec(cache, request(100), 100))
        assertNull(offlinePlaybackDataSpec(cache, DataSpec.Builder().setUri("missing").build(), 100))
    }

    private fun request(position: Long = 0, length: Long = -1) = DataSpec.Builder()
        .setUri("song").setKey("song").setPosition(position).setLength(length).build()

    private fun store(data: ByteArray) {
        val span = cache.startReadWrite("song", 0, bytes.size.toLong())
        try {
            val file = cache.startFile("song", 0, data.size.toLong())
            file.writeBytes(data)
            cache.commitFile(file, data.size.toLong())
            cache.applyContentMetadataMutations(
                "song",
                ContentMetadataMutations().set(ContentMetadata.KEY_CONTENT_LENGTH, bytes.size.toLong()),
            )
        } finally {
            cache.releaseHoleSpan(span)
        }
    }
}
