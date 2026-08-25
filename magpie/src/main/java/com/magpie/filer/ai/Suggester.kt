package com.magpie.filer.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.magpie.filer.core.Formatting
import com.magpie.filer.core.Naming
import com.magpie.filer.watch.SpottedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.time.Duration
import java.util.concurrent.CancellationException

/**
 * Asks Claude what a freshly arrived file should be called and which of the
 * user's folders it belongs in.
 *
 * This is the one part of Magpie that touches the network, and it is entirely
 * optional: with no API key, or with suggestions switched off, nothing here
 * runs and the rest of the app behaves exactly as it did before.
 *
 * What goes up is metadata only — the filename, its size and type, the folder
 * it landed in, and the names of the folders the user files into. The file
 * itself is never opened, let alone sent.
 */
object Suggester {

    /**
     * The most capable model, because the whole point is judgement about what a
     * file is. Effort is held low: this is a short, well-specified task, and the
     * person is standing there waiting for the answer.
     */
    private const val MODEL = "claude-opus-5"

    private const val MAX_TOKENS = 2_048L

    /** Long enough for a considered answer, short enough not to hang the UI. */
    private val TIMEOUT: Duration = Duration.ofSeconds(45)

    private const val SYSTEM_PROMPT =
        "You help someone file a file that has just finished downloading onto their phone. " +
            "You are told the file's name, size and type, the folder it landed in, and the " +
            "names of the folders they file things into. You never see the file's contents, " +
            "so everything you say must follow from the name alone.\n\n" +
            "Reply with three things.\n\n" +
            "name: what the file should be called, ending in exactly the same extension it " +
            "already has. Aim for something the person would still recognise in a year. " +
            "Expand an abbreviation only when you are sure of it. Never invent a date, a " +
            "company, an author or a subject that is not already in the name. If the name is " +
            "already clear, return it unchanged.\n\n" +
            "folder: exactly one of the folder names you were given, copied character for " +
            "character. If none of them is a good home for this file, return an empty string. " +
            "Never invent a folder name, and never return one you were not given.\n\n" +
            "reason: one short sentence in plain English, at most fifteen words, saying why. " +
            "If you are unsure, say so here rather than guessing in the other two fields."

    /**
     * The reply's shape, so the three fields always come back and always come
     * back as strings. The schema is spelled out as free-form properties
     * because that is the only thing [JsonOutputFormat.Schema] accepts.
     */
    private val SCHEMA: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
        .putAdditionalProperty("type", JsonValue.from("object"))
        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
        .putAdditionalProperty(
            "required",
            JsonValue.from(listOf("name", "folder", "reason")),
        )
        .putAdditionalProperty(
            "properties",
            JsonValue.from(
                mapOf(
                    "name" to mapOf(
                        "type" to "string",
                        "description" to "The filename to use, including the original extension.",
                    ),
                    "folder" to mapOf(
                        "type" to "string",
                        "description" to "One of the offered folder names, or an empty string.",
                    ),
                    "reason" to mapOf(
                        "type" to "string",
                        "description" to "One short sentence, at most fifteen words.",
                    ),
                )
            ),
        )
        .build()

    // One client for the app, rebuilt only if the key changes. Each client holds
    // its own connection and thread pools, so making one per request would be
    // wasteful on a phone.
    @Volatile
    private var client: AnthropicClient? = null

    @Volatile
    private var clientKey: String? = null

    @Synchronized
    private fun clientFor(apiKey: String): AnthropicClient {
        val existing = client
        if (existing != null && clientKey == apiKey) return existing
        val fresh = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(TIMEOUT)
            .build()
        client = fresh
        clientKey = apiKey
        return fresh
    }

    /**
     * Ask about one file. [folders] are the names Claude may choose between;
     * pass an empty list when there is no library folder set, and it will only
     * suggest a name.
     */
    suspend fun suggest(
        file: SpottedFile,
        folders: List<String>,
        apiKey: String,
    ): SuggestionResult = withContext(Dispatchers.IO) { ask(file, folders, apiKey) }

    private fun ask(
        file: SpottedFile,
        folders: List<String>,
        apiKey: String,
    ): SuggestionResult {
        if (apiKey.isBlank()) {
            return SuggestionResult.Failed(
                "There is no API key saved, so Magpie has nothing to ask with."
            )
        }

        val question = buildString {
            append("File name: ").append(file.name).append('\n')
            append("Size: ").append(Formatting.fileSize(file.size)).append('\n')
            append("Type: ").append(Formatting.typeLabel(file.name)).append('\n')
            append("Landed in: ").append(file.source).append('\n')
            append("Folders to choose from: ")
            append(if (folders.isEmpty()) "(none — suggest a name only)" else folders.joinToString(", "))
        }

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .system(SYSTEM_PROMPT)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(SCHEMA).build())
                    .build()
            )
            .addUserMessage(question)
            .build()

        return try {
            read(clientFor(apiKey).messages().create(params), file)
        } catch (e: UnauthorizedException) {
            SuggestionResult.Failed(
                "Anthropic rejected the API key. Check it in Magpie's settings — it should " +
                    "start with \"sk-ant-\"."
            )
        } catch (e: RateLimitException) {
            SuggestionResult.Failed(
                "Anthropic is rate limiting this key at the moment. Wait a minute and try again."
            )
        } catch (e: BadRequestException) {
            SuggestionResult.Failed(
                "Anthropic refused the request (${e.statusCode()}: ${e.message ?: "bad request"}). " +
                    "This is a bug in Magpie rather than anything you did."
            )
        } catch (e: AnthropicServiceException) {
            SuggestionResult.Failed(
                "Anthropic returned an error (${e.statusCode()}: ${e.message ?: "no detail"})."
            )
        } catch (e: AnthropicIoException) {
            SuggestionResult.Failed(
                "Could not reach Anthropic (${e.message ?: "no network"}). Filing still works " +
                    "without a suggestion."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Deliberately broad, and deliberately not silent. This is the only
            // place Magpie loads a third-party networking library, and a
            // LinkageError here would otherwise take the whole app down instead
            // of costing one suggestion.
            SuggestionResult.Failed(
                "Asking Claude failed unexpectedly (${e.javaClass.simpleName}: " +
                    "${e.message ?: "no detail"}). Everything else still works."
            )
        }
    }

    private fun read(
        message: com.anthropic.models.messages.Message,
        file: SpottedFile,
    ): SuggestionResult {
        // stopDetails is populated only for a refusal, so its presence is the check.
        val refusal = message.stopDetails().orElse(null)
        if (refusal != null) {
            val category = refusal.category().map { it.asString() }.orElse("no reason given")
            return SuggestionResult.Failed(
                "Claude declined to answer for this file ($category). Name it yourself below."
            )
        }

        val text = message.content()
            .mapNotNull { it.text().orElse(null) }
            .joinToString(separator = "") { it.text() }
            .trim()

        if (text.isEmpty()) {
            return SuggestionResult.Failed("Claude replied with nothing at all. Try again.")
        }

        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return SuggestionResult.Failed(
                "Claude's reply was not in the shape Magpie expected " +
                    "(${e.message ?: "could not read it as JSON"})."
            )
        }

        // Trust nothing: the name is re-cleaned and the extension put back the
        // same way a hand-typed name would be, so a bad answer cannot produce a
        // path separator, a hidden dotfile, or a changed file type.
        val proposed = Naming.withExtensionOf(
            Naming.sanitise(json.optString("name")),
            file.name,
        )
        val name = if (Naming.isUsable(proposed)) proposed else file.name

        return SuggestionResult.Ready(
            FilingSuggestion(
                name = name,
                folder = json.optString("folder").trim(),
                reason = json.optString("reason").trim(),
            )
        )
    }
}
