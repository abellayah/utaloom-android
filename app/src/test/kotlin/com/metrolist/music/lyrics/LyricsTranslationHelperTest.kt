package com.metrolist.music.lyrics

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LyricsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LyricsTranslationHelperTest {
    @Test
    fun `fresh Google translation without keys persists and reloads through the real helper`() = runBlocking {
        assumeTrue("Opt-in live endpoint check", System.getenv("UTALOOM_GOOGLE_TRANSLATION_TEST") == "1")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build())
        val entries = listOf(LyricsEntry(1000, "The moon is bright"), LyricsEntry(2000, ""), LyricsEntry(3000, "Good night"))
        val original = LyricsEntity("live-google", entries.joinToString("\n") { it.text })
        database.upsert(original)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        LyricsTranslationHelper.setCompositionActive(true)
        try {
            LyricsTranslationHelper.translateLyrics(
                lyrics = entries, targetLanguage = "id", apiKey = "", baseUrl = "", model = "", mode = "Transcribed",
                scope = scope, context = context, provider = "Google", songId = original.id, database = database,
            )
            val status = withTimeout(30_000) { LyricsTranslationHelper.status.first {
                it is LyricsTranslationHelper.TranslationStatus.Success || it is LyricsTranslationHelper.TranslationStatus.Error
            } }
            assertTrue("Translation failed: $status", status is LyricsTranslationHelper.TranslationStatus.Success)
            assertTrue(entries[0].translatedTextFlow.value.orEmpty().contains("bulan", ignoreCase = true))
            assertNull(entries[1].translatedTextFlow.value)
            assertTrue(entries[2].translatedTextFlow.value.orEmpty().contains("malam", ignoreCase = true))
            val saved = withTimeout(5000) { database.lyrics(original.id).first { it?.translationMode == "Google:Literal" } }
            assertEquals(original.lyrics, saved?.lyrics)
            assertEquals(2, saved?.translatedLyrics?.lines()?.size)
            val reopened = entries.map { LyricsEntry(it.time, it.text) }
            LyricsTranslationHelper.loadTranslationsFromDatabase(reopened, saved, "id", "Literal", "Google")
            assertEquals(entries.map { it.translatedTextFlow.value }, reopened.map { it.translatedTextFlow.value })
            assertEquals(entries.map { it.time }, reopened.map { it.time })
        } finally {
            scope.cancel()
            LyricsTranslationHelper.cancelTranslation()
            LyricsTranslationHelper.triggerClearTranslations()
            LyricsTranslationHelper.setCompositionActive(false)
            database.close()
        }
    }

    @Test
    fun `Google needs no key and other providers retain their credentials and modes`() {
        assertTrue(LyricsTranslationHelper.canTranslate("Google", "", ""))
        assertFalse(LyricsTranslationHelper.canTranslate("DeepL", "ai-key", ""))
        assertTrue(LyricsTranslationHelper.canTranslate("DeepL", "", "deepl-key"))
        assertFalse(LyricsTranslationHelper.canTranslate("OpenRouter", "", "deepl-key"))
        assertTrue(LyricsTranslationHelper.canTranslate("Custom", "ai-key", ""))
        assertEquals("Google:Literal", LyricsTranslationHelper.translationMode("Google", "Transcribed"))
        assertEquals("Literal", LyricsTranslationHelper.translationMode("DeepL", "Literal"))
        assertEquals("Transcribed", LyricsTranslationHelper.translationMode("OpenRouter", "Transcribed"))
    }

    @Test
    fun `saved Google lines reuse cache without keys and cannot leak into DeepL`() = runBlocking {
        val entries = listOf(LyricsEntry(1000, "Cache first line"), LyricsEntry(2000, ""), LyricsEntry(3000, "Cache last line"))
        val saved = LyricsEntity("google-cache", "original", translatedLyrics = "Baris satu\nBaris dua", translationLanguage = "id", translationMode = "Google:Literal")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build())
        database.upsert(saved.copy(translatedLyrics = "Old provider", translationMode = "Literal"))
        LyricsTranslationHelper.setCompositionActive(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            LyricsTranslationHelper.loadTranslationsFromDatabase(entries, saved, "id", "Transcribed", "Google")
            assertEquals(listOf("Baris satu", null, "Baris dua"), entries.map { it.translatedTextFlow.value })
            LyricsTranslationHelper.translateLyrics(
                lyrics = entries, targetLanguage = "id", apiKey = "", baseUrl = "", model = "", mode = "Literal",
                scope = scope, context = context, provider = "Google", songId = saved.id, database = database,
            )
            withTimeout(5000) { LyricsTranslationHelper.status.first { it is LyricsTranslationHelper.TranslationStatus.Success } }
            assertEquals(listOf("Baris satu", null, "Baris dua"), entries.map { it.translatedTextFlow.value })
            assertEquals(listOf(1000L, 2000L, 3000L), entries.map { it.time })
            val persisted = withTimeout(5000) { database.lyrics(saved.id).first { it?.translationMode == "Google:Literal" } }
            assertEquals(saved.translatedLyrics, persisted?.translatedLyrics)
            LyricsTranslationHelper.loadTranslationsFromDatabase(entries, saved, "id", "Literal", "DeepL")
            assertFalse(LyricsTranslationHelper.hasActiveTranslations.value)
            entries.forEach { assertNull(it.translatedTextFlow.value) }
            LyricsTranslationHelper.loadTranslationsFromDatabase(entries, saved.copy(translationMode = "Literal"), "id", "Literal", "DeepL")
            assertTrue(LyricsTranslationHelper.hasActiveTranslations.value)
        } finally {
            scope.cancel()
            LyricsTranslationHelper.cancelTranslation()
            LyricsTranslationHelper.triggerClearTranslations()
            LyricsTranslationHelper.setCompositionActive(false)
            database.close()
        }
    }
}
