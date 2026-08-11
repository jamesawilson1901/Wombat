package com.magpie.api

import android.content.Context
import com.magpie.data.ApiKeyCrypto
import com.magpie.data.Decision
import com.magpie.data.PresetFolder
import com.magpie.data.SettingsRepository
import java.io.IOException
import java.net.URLConnection
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class FileMeta(val name: String, val sizeBytes: Long) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val mime: String get() = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
}

@Serializable
private data class ApiMessage(val role: String, val content: String)

@Serializable
private data class ApiRequest(
    val model: String,
    val max_tokens: Int,
    val messages: List<ApiMessage>,
    val system: String? = null,
)

/**
 * Raw Messages API over OkHttp, per the build spec. The key never leaves this
 * class decrypted, is never logged, and never appears in error messages.
 */
class ClaudeClient(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    // Popup suggestions must land in ~1s and give up at 3s (§6.3); wizard and
    // key-test calls can take their time.
    private val fastClient = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .build()
    private val slowClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val VERSION = "2023-06-01"
        const val MAX_INPUT_CHARS = 16_000 // ≈4k tokens; §8.2 budget ceiling
    }

    sealed class Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>()
        data class Failed(val reason: String) : Outcome<Nothing>()
        /** No key, no network, cap reached — silently fall back to local. */
        data object Skipped : Outcome<Nothing>()
    }

    /** One call per popup group (§8.2). Null means: keep the local suggestions. */
    suspend fun suggestForGroup(
        files: List<FileMeta>,
        presets: List<PresetFolder>,
        decisions: List<Decision>,
    ): AiSuggestion? {
        val snapshot = settings.snapshot()
        if (!snapshot.hasApiKey || snapshot.capReached) return null
        val key = ApiKeyCrypto.decrypt(context, snapshot.apiKeyCipher) ?: return null

        val prompt = buildSuggestionPrompt(files, presets, decisions)
        return when (val out = call(fastClient, key, snapshot.model, maxTokens = 300, system = SUGGESTION_SYSTEM, user = prompt)) {
            is Outcome.Ok -> ClaudeParsing.parseSuggestion(out.value)
            is Outcome.Failed -> {
                settings.recordApiError(out.reason)
                null
            }
            Outcome.Skipped -> null
        }
    }

    /** First-run wizard folder descriptions; the wizard works without this (§7.1). */
    suspend fun describeFolders(paths: List<String>): List<FolderDescription>? {
        val snapshot = settings.snapshot()
        if (!snapshot.hasApiKey || snapshot.capReached) return null
        val key = ApiKeyCrypto.decrypt(context, snapshot.apiKeyCipher) ?: return null

        val prompt = buildString {
            appendLine("Here are folder paths from an Android phone's shared storage:")
            paths.take(120).forEach { appendLine(it) }
            appendLine()
            appendLine(
                "For each, give a one-line description of what it likely holds and whether it is a plausible " +
                    "destination for downloaded files. JSON only, no prose, no markdown fences:"
            )
            appendLine("""{"folders": [{"path": "<path>", "description": "<one line>", "recommended": true}]}""")
        }
        return when (val out = call(slowClient, key, snapshot.model, maxTokens = 2000, system = null, user = prompt)) {
            is Outcome.Ok -> ClaudeParsing.parseFolderDescriptions(out.value)
            is Outcome.Failed -> {
                settings.recordApiError(out.reason)
                null
            }
            Outcome.Skipped -> null
        }
    }

    /** "Test key" in settings: a 1-token request; returns success or the exact API error. */
    suspend fun testKey(plainKey: String): Outcome<String> {
        val snapshot = settings.snapshot()
        return call(slowClient, plainKey.trim(), snapshot.model, maxTokens = 1, system = null, user = "Hi", track = false)
    }

    private suspend fun call(
        client: OkHttpClient,
        key: String,
        model: String,
        maxTokens: Int,
        system: String?,
        user: String,
        track: Boolean = true,
    ): Outcome<String> = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            ApiRequest(model = model, max_tokens = maxTokens, messages = listOf(ApiMessage("user", user)), system = system)
        )
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-api-key", key)
            .header("anthropic-version", VERSION)
            .header("content-type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Outcome.Failed(apiErrorMessage(response.code, text))
                }
                val root = json.parseToJsonElement(text).jsonObject
                if (track) {
                    val usage = root["usage"]?.jsonObject
                    val inTok = usage?.get("input_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val outTok = usage?.get("output_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val snap = settings.snapshot()
                    settings.addSpend(
                        (inTok * snap.inputRatePerMTok + outTok * snap.outputRatePerMTok) / 1_000_000.0
                    )
                }
                val answer = root["content"]?.let { content ->
                    (content as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { block ->
                            val obj = block.jsonObject
                            if (obj["type"]?.jsonPrimitive?.content == "text") obj["text"]?.jsonPrimitive?.content else null
                        }
                        ?.joinToString("")
                }.orEmpty()
                Outcome.Ok(answer)
            }
        } catch (e: IOException) {
            Outcome.Failed("Network: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            Outcome.Failed("Unexpected: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun apiErrorMessage(code: Int, body: String): String = try {
        val err = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        val type = err?.get("type")?.jsonPrimitive?.content ?: "http_$code"
        val message = err?.get("message")?.jsonPrimitive?.content ?: "HTTP $code"
        "$type: $message"
    } catch (_: Exception) {
        "HTTP $code"
    }

    private fun buildSuggestionPrompt(
        files: List<FileMeta>,
        presets: List<PresetFolder>,
        decisions: List<Decision>,
    ): String {
        fun render(exampleCount: Int, withDescriptions: Boolean): String = buildString {
            appendLine("Files just downloaded:")
            files.forEach { appendLine("- ${it.name} | ext=${it.extension} | ${it.sizeBytes} bytes | ${it.mime}") }
            appendLine()
            appendLine("My preset folders:")
            presets.forEach { p ->
                val desc = if (withDescriptions && p.description.isNotBlank()) " | ${p.description}" else ""
                appendLine("- ${p.path} | ${p.name}$desc")
            }
            if (exampleCount > 0 && decisions.isNotEmpty()) {
                appendLine()
                appendLine("My recent filing decisions (filename tokens -> chosen folder):")
                decisions.take(exampleCount).forEach { d ->
                    appendLine("- .${d.extension} [${d.tokens}] -> ${d.chosenFolder}")
                }
            }
            appendLine()
            appendLine("Pick the 3 best destination folders from my presets for this group.")
        }

        // Trim examples first, then preset descriptions, to stay in budget (§8.2).
        var prompt = render(20, withDescriptions = true)
        if (prompt.length > MAX_INPUT_CHARS) prompt = render(8, withDescriptions = true)
        if (prompt.length > MAX_INPUT_CHARS) prompt = render(0, withDescriptions = true)
        if (prompt.length > MAX_INPUT_CHARS) prompt = render(0, withDescriptions = false)
        return prompt
    }

    private val SUGGESTION_SYSTEM = """
        You choose destination folders for downloaded files on a personal Android phone.
        Respond with JSON only — no prose, no markdown fences:
        {"suggestions": ["<folder path>", "<folder path>", "<folder path>"],
         "new_folder_name": "<short name if a new folder would fit better, else null>",
         "rename": {"<original filename>": "<suggested filename or null>"}}
        Suggestions must be exact paths from the preset list.
        Only suggest a rename for obviously junk names (random hex, bare timestamps,
        url-encoded gibberish, generic stubs); never change the extension; use null otherwise.
    """.trimIndent()
}
