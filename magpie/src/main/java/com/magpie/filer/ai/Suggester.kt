package com.magpie.filer.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
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
    private fun clientFor(apiKey: String, baseUrl: String?): AnthropicClient {
        val cacheKey = "$baseUrl\u0000$apiKey"
        val existing = client
        if (existing != null && clientKey == cacheKey) return existing
        val builder = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(TIMEOUT)
            // The SDK retries twice by default with a backoff between. Someone
            // is standing there holding the phone waiting for this, so one
            // retry is the most that is worth their time; past that they would
            // rather be told and get on with filing it themselves. Under test
            // there is nothing transient to ride out, so none.
            .maxRetries(if (baseUrl == null) 1 else 0)
        if (baseUrl != null) builder.baseUrl(baseUrl)
        val fresh = builder.build()
        client = fresh
        clientKey = cacheKey
        return fresh
    }

    /**
     * Ask about one file. [folders] are the names Claude may choose between;
     * pass an empty list when there is no library folder set, and it will only
     * suggest a name.
     *
     * [baseUrl] exists so the tests can point the whole call at a local HTTP
     * server and check what actually goes on the wire — that the request is
     * shaped the way the API documents, and that every kind of reply and
     * failure is handled. The app never passes it, so production always talks
     * to Anthropic.
     */
    suspend fun suggest(
        file: SpottedFile,
        folders: List<String>,
        apiKey: String,
        baseUrl: String? = null,
    ): SuggestionResult = withContext(Dispatchers.IO) { ask(file, folders, apiKey, baseUrl) }

    private fun ask(
        file: SpottedFile,
        folders: List<String>,
        apiKey: String,
        baseUrl: String?,
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
            read(clientFor(apiKey, baseUrl).messages().create(params), file)
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

    /**
     * The most files worth putting in one request. Past this the reply gets
     * long enough that the wait stops feeling like a shortcut.
     */
    const val BATCH_LIMIT = 25

    private val BATCH_SCHEMA: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
        .putAdditionalProperty("type", JsonValue.from("object"))
        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
        .putAdditionalProperty("required", JsonValue.from(listOf("files")))
        .putAdditionalProperty(
            "properties",
            JsonValue.from(
                mapOf(
                    "files" to mapOf(
                        "type" to "array",
                        "description" to "One entry per file, in the order given.",
                        "items" to mapOf(
                            "type" to "object",
                            "additionalProperties" to false,
                            "required" to listOf("given", "name", "folder", "reason"),
                            "properties" to mapOf(
                                "given" to mapOf(
                                    "type" to "string",
                                    "description" to "The file's original name, copied back exactly.",
                                ),
                                "name" to mapOf("type" to "string"),
                                "folder" to mapOf("type" to "string"),
                                "reason" to mapOf("type" to "string"),
                            ),
                        ),
                    )
                )
            ),
        )
        .build()

    /**
     * Ask about several files at once.
     *
     * Switching suggestions on with a backlog waiting means asking about each
     * one in turn, which is slow and costs a request every time. One request
     * covering a batch is dramatically cheaper per file and answers in a single
     * wait. Nothing extra is sent: it is the same metadata per file as [suggest].
     *
     * The reply is matched back by the original name, so a missing or invented
     * entry simply means that file has no suggestion rather than the wrong one.
     */
    suspend fun suggestMany(
        files: List<SpottedFile>,
        folders: List<String>,
        apiKey: String,
        baseUrl: String? = null,
    ): Map<String, FilingSuggestion> = withContext(Dispatchers.IO) {
        askMany(files.take(BATCH_LIMIT), folders, apiKey, baseUrl)
    }

    private fun askMany(
        files: List<SpottedFile>,
        folders: List<String>,
        apiKey: String,
        baseUrl: String?,
    ): Map<String, FilingSuggestion> {
        if (apiKey.isBlank() || files.isEmpty()) return emptyMap()

        val question = buildString {
            append("Here are ").append(files.size).append(" files to file.\n\n")
            for (file in files) {
                append("- name: ").append(file.name)
                append(" | size: ").append(Formatting.fileSize(file.size))
                append(" | type: ").append(Formatting.typeLabel(file.name))
                append(" | landed in: ").append(file.source).append('\n')
            }
            append("\nFolders to choose from: ")
            append(if (folders.isEmpty()) "(none — suggest names only)" else folders.joinToString(", "))
            append("\n\nAnswer for every file, in the order given, copying each file's ")
            append("original name into \"given\" exactly as it appears above.")
        }

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS * 4)
            .system(SYSTEM_PROMPT)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(BATCH_SCHEMA).build())
                    .build()
            )
            .addUserMessage(question)
            .build()

        return try {
            readMany(clientFor(apiKey, baseUrl).messages().create(params), files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A batch is a convenience on top of a convenience. If it fails the
            // caller falls back to asking one at a time, so there is nothing
            // here worth interrupting the user for.
            emptyMap()
        }
    }

    private fun readMany(
        message: com.anthropic.models.messages.Message,
        files: List<SpottedFile>,
    ): Map<String, FilingSuggestion> {
        if (message.stopDetails().orElse(null) != null) return emptyMap()

        val text = message.content()
            .mapNotNull { it.text().orElse(null) }
            .joinToString(separator = "") { it.text() }
            .trim()
        if (text.isEmpty()) return emptyMap()

        val array = try {
            JSONObject(text).optJSONArray("files")
        } catch (e: JSONException) {
            null
        } ?: return emptyMap()

        val byName = files.associateBy { it.name }
        val found = HashMap<String, FilingSuggestion>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            // Matched back by the name that was sent, never by position, so a
            // short or reordered reply cannot put a suggestion on the wrong file.
            val file = byName[item.optString("given")] ?: continue
            val proposed = Naming.withExtensionOf(
                Naming.sanitise(item.optString("name")),
                file.name,
            )
            found[file.path] = FilingSuggestion(
                name = if (Naming.isUsable(proposed)) proposed else file.name,
                folder = item.optString("folder").trim(),
                reason = item.optString("reason").trim(),
            )
        }
        return found
    }

    /** One thing for the big sort to ask about. */
    sealed interface SortAsk {
        /** One file, keyed in the answer by its path. */
        data class One(val file: SpottedFile) : SortAsk

        /** A whole run sharing one decision, keyed in the answer by [id]. */
        data class Run(
            val id: String,
            val count: Int,
            val range: String,
            val samples: List<String>,
        ) : SortAsk
    }

    private const val SORT_PROMPT =
        "You are sorting years of accumulated phone files into folders — the big clean-up. " +
            "You are given a list of files (name, type, size, when it is from) and runs — " +
            "sets of files created together in one session, which share a single decision. " +
            "You never see contents, so everything must follow from names, types and dates.\n\n" +
            "For each file or run, reply with three things.\n\n" +
            "name: for a file, what it should be called, ending in exactly the extension it " +
            "already has; if the name is already clear, return it unchanged. For a run, one " +
            "short stem in capitals with underscores, at most three words, no extension, no " +
            "numbering — say what the run is. Never invent a date, a company, an author or a " +
            "subject that is not already there, and never name or identify a person.\n\n" +
            "folder: where it belongs. Prefer one of the existing folder names, copied " +
            "character for character. Only when none of them is a reasonable home, name a " +
            "new folder to create: short, in capitals with underscores, and general enough " +
            "to hold more than this one thing — SCREENSHOTS, INVOICES, PHOTOS_2019 — never " +
            "a folder for a single file, and never a person's name. Files of the same kind " +
            "and files from the same event must be given the same folder. An empty string " +
            "means you cannot tell, and the file will be left alone.\n\n" +
            "reason: one short sentence, at most fifteen words, saying why.\n\n" +
            "Answer for every entry, copying each entry's \"given\" key back exactly."

    /**
     * Ask about one batch of the big sort: loose files and whole runs together,
     * against the folders that exist so far. The one place Claude may propose a
     * folder that does not exist yet — the big sort is allowed to build the
     * organisation, not only fill it.
     *
     * Answers come back keyed by a file's path or a run's id, matched by the
     * "given" name sent, never by position. A missing or unusable answer means
     * that entry is skipped, not misfiled.
     */
    suspend fun sortMany(
        asks: List<SortAsk>,
        folders: List<String>,
        apiKey: String,
        baseUrl: String? = null,
    ): Map<String, FilingSuggestion> = withContext(Dispatchers.IO) {
        askSort(asks.take(BATCH_LIMIT), folders, apiKey, baseUrl)
    }

    private fun askSort(
        asks: List<SortAsk>,
        folders: List<String>,
        apiKey: String,
        baseUrl: String?,
    ): Map<String, FilingSuggestion> {
        if (apiKey.isBlank() || asks.isEmpty()) return emptyMap()

        val question = buildString {
            append("Here are ").append(asks.size).append(" entries to sort.\n\n")
            for (ask in asks) {
                when (ask) {
                    is SortAsk.One -> {
                        append("- given: ").append(ask.file.name)
                        append(" | file | size: ").append(Formatting.fileSize(ask.file.size))
                        append(" | type: ").append(Formatting.typeLabel(ask.file.name))
                        append(" | in: ").append(ask.file.source).append('\n')
                    }

                    is SortAsk.Run -> {
                        append("- given: ").append(ask.id)
                        append(" | run of ").append(ask.count).append(" files")
                        append(" | from: ").append(ask.range)
                        append(" | sample names: ").append(ask.samples.joinToString(", "))
                        append('\n')
                    }
                }
            }
            append("\nExisting folders: ")
            append(if (folders.isEmpty()) "(none yet — name new ones)" else folders.joinToString(", "))
            append("\n\nAnswer for every entry, copying each \"given\" back exactly.")
        }

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS * 4)
            .system(SORT_PROMPT)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(BATCH_SCHEMA).build())
                    .build()
            )
            .addUserMessage(question)
            .build()

        return try {
            readSort(clientFor(apiKey, baseUrl).messages().create(params), asks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The big sort carries on to the next batch and reports the ones it
            // could not decide; a failed request must cost one batch, not the run.
            emptyMap()
        }
    }

    private fun readSort(
        message: com.anthropic.models.messages.Message,
        asks: List<SortAsk>,
    ): Map<String, FilingSuggestion> {
        if (message.stopDetails().orElse(null) != null) return emptyMap()

        val text = message.content()
            .mapNotNull { it.text().orElse(null) }
            .joinToString(separator = "") { it.text() }
            .trim()
        if (text.isEmpty()) return emptyMap()

        val array = try {
            JSONObject(text).optJSONArray("files")
        } catch (e: JSONException) {
            null
        } ?: return emptyMap()

        val files = asks.filterIsInstance<SortAsk.One>().associateBy { it.file.name }
        val runs = asks.filterIsInstance<SortAsk.Run>().associateBy { it.id }

        val found = HashMap<String, FilingSuggestion>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val given = item.optString("given")
            val folder = item.optString("folder").trim()
            val reason = item.optString("reason").trim()

            val file = files[given]
            if (file != null) {
                val proposed = Naming.withExtensionOf(
                    Naming.sanitise(item.optString("name")),
                    file.file.name,
                )
                found[file.file.path] = FilingSuggestion(
                    name = if (Naming.isUsable(proposed)) proposed else file.file.name,
                    folder = folder,
                    reason = reason,
                )
                continue
            }

            val run = runs[given] ?: continue
            // A run's answer is a stem: cleaned like a typed one, any extension
            // cut off, because each file in the run keeps its own.
            val stem = Naming.stem(Naming.sanitise(item.optString("name"))).trim()
            if (stem.isBlank()) continue
            found[run.id] = FilingSuggestion(name = stem, folder = folder, reason = reason)
        }
        return found
    }

    private const val RUN_PROMPT =
        "You help someone name and file a run of files that were created together — " +
            "generated stills, video tests, photos from one session. You are shown a few " +
            "small snapshots from the run, how many files it holds, when it happened, and " +
            "the names of the folders they file things into.\n\n" +
            "Reply with three things.\n\n" +
            "name: one short stem for the whole run, in capitals with underscores, like " +
            "ARRIVAL_STILLS or TOWN_REFS — at most three words, no extension, no numbering. " +
            "Say what the run is, not what it looks like.\n\n" +
            "folder: exactly one of the folder names you were given, copied character for " +
            "character, or an empty string if none of them fits. Never invent one.\n\n" +
            "reason: one short sentence, at most fifteen words. Never name or identify a " +
            "person; describe what kind of thing the run is."

    /**
     * Ask about a whole run at once: a few [snapshots] (base64 JPEG), how many
     * files the run holds, and when it happened. One answer for the run is the
     * point — the files were created together, so they belong together, and a
     * single decision cannot scatter them.
     *
     * This is the one call that sends any of a file's contents, and only ever
     * these snapshots. It exists behind its own switch, off by default.
     */
    suspend fun suggestRun(
        snapshots: List<String>,
        count: Int,
        range: String,
        folders: List<String>,
        apiKey: String,
        baseUrl: String? = null,
    ): SuggestionResult = withContext(Dispatchers.IO) {
        askRun(snapshots, count, range, folders, apiKey, baseUrl)
    }

    private fun askRun(
        snapshots: List<String>,
        count: Int,
        range: String,
        folders: List<String>,
        apiKey: String,
        baseUrl: String?,
    ): SuggestionResult {
        if (apiKey.isBlank()) {
            return SuggestionResult.Failed(
                "There is no API key saved, so Magpie has nothing to ask with."
            )
        }
        if (snapshots.isEmpty()) {
            return SuggestionResult.Failed(
                "Nothing in this run could be turned into a snapshot to show Claude."
            )
        }

        val question = buildString {
            append("A run of ").append(count).append(" files, from ").append(range).append(". ")
            append("The snapshots above are the first, middle and last of it.\n")
            append("Folders to choose from: ")
            append(if (folders.isEmpty()) "(none — suggest a name only)" else folders.joinToString(", "))
        }

        val blocks = ArrayList<ContentBlockParam>(snapshots.size + 1)
        for (snapshot in snapshots.take(3)) {
            blocks += ContentBlockParam.ofImage(
                ImageBlockParam.builder()
                    .source(
                        Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .data(snapshot)
                            .build()
                    )
                    .build()
            )
        }
        blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(question).build())

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .system(RUN_PROMPT)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(SCHEMA).build())
                    .build()
            )
            .addUserMessageOfBlockParams(blocks)
            .build()

        return try {
            readRun(clientFor(apiKey, baseUrl).messages().create(params))
        } catch (e: UnauthorizedException) {
            SuggestionResult.Failed(
                "Anthropic rejected the API key. Check it in Magpie's settings."
            )
        } catch (e: RateLimitException) {
            SuggestionResult.Failed(
                "Anthropic is rate limiting this key at the moment. Wait a minute and try again."
            )
        } catch (e: AnthropicServiceException) {
            SuggestionResult.Failed(
                "Anthropic returned an error (${e.statusCode()}: ${e.message ?: "no detail"})."
            )
        } catch (e: AnthropicIoException) {
            SuggestionResult.Failed(
                "Could not reach Anthropic (${e.message ?: "no network"}). The run can still " +
                    "be named and filed by hand."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SuggestionResult.Failed(
                "Asking Claude failed unexpectedly (${e.javaClass.simpleName}: " +
                    "${e.message ?: "no detail"}). Everything else still works."
            )
        }
    }

    private fun readRun(message: com.anthropic.models.messages.Message): SuggestionResult {
        val refusal = message.stopDetails().orElse(null)
        if (refusal != null) {
            val category = refusal.category().map { it.asString() }.orElse("no reason given")
            return SuggestionResult.Failed(
                "Claude declined to look at this run ($category). Name it yourself."
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

        // The stem gets the same distrust a name does: cleaned like a typed
        // one, and any extension it tried to carry is cut off, because the
        // run's files each keep their own.
        val stem = Naming.stem(Naming.sanitise(json.optString("name"))).trim()
        if (stem.isBlank()) {
            return SuggestionResult.Failed(
                "Claude's suggested name had nothing usable left in it. Name the run yourself."
            )
        }

        return SuggestionResult.Ready(
            FilingSuggestion(
                name = stem,
                folder = json.optString("folder").trim(),
                reason = json.optString("reason").trim(),
            )
        )
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
