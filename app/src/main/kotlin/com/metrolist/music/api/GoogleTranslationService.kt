package com.metrolist.music.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.coroutineContext

object GoogleTranslationService {
    const val PROVIDER = "Google"
    const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    private val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
            install(UserAgent) {
                agent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }
        }
    }

    suspend fun translate(text: String, targetLanguage: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            require(targetLanguage.isNotBlank()) { "A target language is required" }
            // ponytail: one request per line preserves lyric positions; batch only if latency becomes a problem.
            val translated = text.lines().map { line ->
                coroutineContext.ensureActive()
                val source = line.trim()
                if (source.isBlank()) {
                    ""
                } else {
                    require(source.length <= 2000) { "Google Translate supports up to 2000 characters per lyrics line" }
                    val response = client.get(ENDPOINT) {
                        parameter("client", "gtx")
                        parameter("sl", "auto")
                        parameter("dt", "t")
                        parameter("tl", normalizeLanguage(targetLanguage))
                        parameter("q", source)
                    }
                    check(response.status.isSuccess()) { "Google Translate failed: HTTP ${response.status.value}" }
                    parseResponse(response.bodyAsText()).also {
                        check(it.isNotBlank()) { "Google Translate returned no translation" }
                    }
                }
            }
            coroutineContext.ensureActive()
            Result.success(translated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    internal fun parseResponse(body: String): String {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return ""
        val segments = root.firstOrNull() as? JsonArray ?: return ""
        // Saved lyrics use newlines as separators, so one response must stay one line.
        return segments.joinToString("") {
            ((it as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull.orEmpty()
        }.replace(Regex("[\\r\\n]+"), " ").trim()
    }

    internal fun normalizeLanguage(language: String): String = when (val code = language.trim().lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        "en-us" -> "en"
        "pt", "pt-br" -> "pt-BR"
        "pt-pt" -> "pt-PT"
        else -> code
    }
}
