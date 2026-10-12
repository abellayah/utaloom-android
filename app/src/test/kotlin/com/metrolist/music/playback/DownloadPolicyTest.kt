package com.metrolist.music.playback

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.DownloadQualityKey
import com.metrolist.music.constants.OfflineModeKey
import com.metrolist.music.constants.WifiOnlyDownloadsKey
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DownloadPolicyTest {
    private lateinit var context: Context
    private lateinit var database: InternalDatabase
    private lateinit var provider: StandaloneDatabaseProvider
    private lateinit var playerCache: SimpleCache
    private lateinit var downloadCache: SimpleCache
    private lateinit var cacheDirectory: File
    private var util: DownloadUtil? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking {
            context.dataStore.edit {
                it.remove(DownloadQualityKey)
                it[AudioQualityKey] = AudioQuality.HIGH.name
                it[WifiOnlyDownloadsKey] = true
                it[OfflineModeKey] = false
            }
        }
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries().build()
        provider = StandaloneDatabaseProvider(context)
        cacheDirectory = Files.createTempDirectory(context.cacheDir.toPath(), "download-policy").toFile()
        playerCache = SimpleCache(File(cacheDirectory, "player"), NoOpCacheEvictor(), provider)
        downloadCache = SimpleCache(File(cacheDirectory, "download"), NoOpCacheEvictor(), provider)
        setNetwork(ConnectivityManager.TYPE_MOBILE)
    }

    @After
    fun tearDown() {
        util?.release()
        util?.downloadManager?.release()
        playerCache.release()
        downloadCache.release()
        provider.close()
        database.close()
        cacheDirectory.deleteRecursively()
    }

    @Test
    fun `saved unmetered requirement is applied synchronously before resume`() {
        val manager = newUtil().downloadManager

        assertTrue(manager.requirements.isNetworkRequired)
        assertTrue(manager.requirements.isUnmeteredNetworkRequired)
        assertEquals(Requirements.NETWORK_UNMETERED, manager.notMetRequirements)
        assertTrue(manager.downloadsPaused)
        assertEquals(Looper.getMainLooper(), manager.applicationLooper)
    }

    @Test
    fun `download default and saved choice are independent from playback`() {
        val downloadUtil = newUtil()
        val quality = DownloadUtil::class.java.getDeclaredMethod("getDownloadQuality").apply { isAccessible = true }

        assertEquals(AudioQuality.AUTO, quality.invoke(downloadUtil))
        runBlocking { context.dataStore.edit { it[DownloadQualityKey] = AudioQuality.LOW.name } }
        assertEquals(AudioQuality.LOW, quality.invoke(downloadUtil))
        runBlocking { context.dataStore.edit { it[AudioQualityKey] = AudioQuality.AUTO.name } }
        assertEquals(AudioQuality.LOW, quality.invoke(downloadUtil))
    }

    @Test
    fun `native unmetered requirement reacts to network changes without replacing policy`() {
        val manager = newUtil().downloadManager
        manager.resumeDownloads()

        assertEquals(Requirements.NETWORK_UNMETERED, manager.notMetRequirements)
        setNetwork(ConnectivityManager.TYPE_WIFI)
        assertEquals(0, manager.notMetRequirements)
        setNetwork(ConnectivityManager.TYPE_MOBILE)
        assertEquals(Requirements.NETWORK_UNMETERED, manager.notMetRequirements)
        assertTrue(manager.requirements.isUnmeteredNetworkRequired)
    }

    @Test
    fun `offline restores tasks stopped before native service resume and preserves other stop reasons`() {
        val index = DefaultDownloadIndex(provider)
        index.putDownload(download("pending", Download.STOP_REASON_NONE))
        index.putDownload(download("other-stop", 42))
        runBlocking { context.dataStore.edit { it[OfflineModeKey] = true } }
        val downloadUtil = newUtil()
        val manager = downloadUtil.downloadManager
        manager.resumeDownloads()
        await { manager.currentDownloads.size == 2 && manager.isIdle }

        assertEquals(OFFLINE_DOWNLOAD_STOP_REASON, manager.currentDownloads.single { it.request.id == "pending" }.stopReason)
        assertEquals(42, manager.currentDownloads.single { it.request.id == "other-stop" }.stopReason)
        assertTrue(downloadUtil.applyDownloadPreferences())
        assertTrue(manager.downloadsPaused)
        runBlocking { context.dataStore.edit { it[OfflineModeKey] = false } }
        assertFalse(downloadUtil.applyDownloadPreferences())
        await { manager.currentDownloads.single { it.request.id == "pending" }.stopReason == Download.STOP_REASON_NONE }
        assertFalse(manager.downloadsPaused)
        assertEquals(42, manager.currentDownloads.single { it.request.id == "other-stop" }.stopReason)
    }

    @Test
    fun `resolved streams are never shared between download quality choices`() {
        val cache = StreamUrlCache()
        cache.put(downloadStreamCacheKey("song", AudioQuality.HIGH), "high", emptyMap(), "test", 60)
        cache.put(downloadStreamCacheKey("song", AudioQuality.LOW), "low", emptyMap(), "test", 60)

        assertNotEquals(downloadStreamCacheKey("song", AudioQuality.HIGH), downloadStreamCacheKey("song", AudioQuality.LOW))
        assertEquals("high", cache[downloadStreamCacheKey("song", AudioQuality.HIGH)]?.url)
        assertEquals("low", cache[downloadStreamCacheKey("song", AudioQuality.LOW)]?.url)
    }

    private fun newUtil() = DownloadUtil(context, MusicDatabase(database), provider, downloadCache, playerCache)
        .also { util = it }

    private fun download(id: String, stopReason: Int) = Download(
        DownloadRequest.Builder(id, id.toUri()).setCustomCacheKey(id).build(),
        if (stopReason == Download.STOP_REASON_NONE) Download.STATE_QUEUED else Download.STATE_STOPPED,
        0, 0, -1, stopReason, Download.FAILURE_REASON_NONE,
    )

    private fun setNetwork(type: Int) {
        val connectivity = context.getSystemService<ConnectivityManager>()!!
        val shadow = shadowOf(connectivity)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true))
        val capabilities = ShadowNetworkCapabilities.newInstance().apply {
            val addTransport = NetworkCapabilities::class.java.getDeclaredMethod("addTransportType", Int::class.javaPrimitiveType)
            val addCap = NetworkCapabilities::class.java.getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
            addTransport.isAccessible = true
            addCap.isAccessible = true
            if (type == ConnectivityManager.TYPE_WIFI) {
                addTransport.invoke(this, NetworkCapabilities.TRANSPORT_WIFI)
                addCap.invoke(this, NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } else {
                addTransport.invoke(this, NetworkCapabilities.TRANSPORT_CELLULAR)
            }
            addCap.invoke(this, NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCap.invoke(this, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadow.setNetworkCapabilities(connectivity.activeNetwork!!, capabilities)
        shadow.networkCallbacks.forEach {
            it.onAvailable(connectivity.activeNetwork!!)
            it.onCapabilitiesChanged(connectivity.activeNetwork!!, capabilities)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Download manager did not reach expected state", predicate())
    }
}
