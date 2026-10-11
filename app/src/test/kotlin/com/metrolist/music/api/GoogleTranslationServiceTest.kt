package com.metrolist.music.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GoogleTranslationServiceTest {
    @Test
    fun `response fragments and invalid payloads keep their positions`() {
        assertEquals("Langit biru", GoogleTranslationService.parseResponse("""[[["Langit ","Sky "],["biru","blue"]],null,"en"]"""))
        assertEquals("Satu Dua", GoogleTranslationService.parseResponse("""[[["Satu\nDua","One\nTwo"]]]"""))
        for (body in listOf("not json", "{}", "[]", "[[[]]]", "[[null]]", "[[{}]]", "[[[null]]]", "[[[42]]]")) {
            assertEquals("", GoogleTranslationService.parseResponse(body))
        }
        assertEquals("id", GoogleTranslationService.normalizeLanguage(" ID "))
        assertEquals("en", GoogleTranslationService.normalizeLanguage("EN-US"))
        assertEquals("zh-CN", GoogleTranslationService.normalizeLanguage("zh"))
        assertEquals("zh-TW", GoogleTranslationService.normalizeLanguage("ZH-hant"))
        assertEquals("pt-BR", GoogleTranslationService.normalizeLanguage("PT-BR"))
        assertEquals("pt-BR", GoogleTranslationService.normalizeLanguage("pt"))
        assertEquals("pt-PT", GoogleTranslationService.normalizeLanguage("pt-PT"))
    }

    @Test
    fun `blank lines and invalid input need no network or API key`() = runBlocking {
        assertEquals(listOf("", "", ""), GoogleTranslationService.translate("\n  \n", "id").getOrThrow())
        assertTrue(GoogleTranslationService.translate("Hello", " ").isFailure)
        assertTrue(GoogleTranslationService.translate("a".repeat(2001), "id").isFailure)
    }

    @Test
    fun `real free Google endpoint translates without credentials`() = runBlocking {
        assumeTrue("Opt-in live endpoint check", System.getenv("UTALOOM_GOOGLE_TRANSLATION_TEST") == "1")
        val translated = GoogleTranslationService.translate("The moon is bright\n\nGood night", "ID").getOrThrow()
        assertEquals(3, translated.size)
        assertTrue(translated[0].contains("bulan", ignoreCase = true))
        assertEquals("", translated[1])
        assertTrue(translated[2].contains("malam", ignoreCase = true))
    }
}
