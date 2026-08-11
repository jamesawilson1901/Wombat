package com.magpie.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AiSuggestion(
    val suggestions: List<String> = emptyList(),
    val newFolderName: String? = null,
    val renames: Map<String, String?> = emptyMap(),
)

@Serializable
data class FolderDescription(
    val path: String,
    val description: String = "",
    val recommended: Boolean = false,
)

/** Pure parsing helpers, unit-tested separately from the HTTP layer. */
object ClaudeParsing {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * §8.2: parse defensively — strip markdown fences if present, tolerate
     * prose around the JSON, and return null (never throw) when it can't be
     * rescued so the local suggestions silently stay.
     */
    fun extractJsonObject(text: String): String? {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            t = t.substringBeforeLast("```")
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return t.substring(start, end + 1)
    }

    fun parseSuggestion(text: String): AiSuggestion? = runCatching {
        val body = extractJsonObject(text) ?: return null
        val obj = json.parseToJsonElement(body).jsonObject
        val suggestions = obj["suggestions"]?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val newFolder = (obj["new_folder_name"] as? JsonPrimitive)?.contentOrNullSafe()
        val renames = obj["rename"]?.jsonObject?.mapValues { (_, v) ->
            if (v is JsonNull) null else (v as? JsonPrimitive)?.contentOrNullSafe()
        } ?: emptyMap()
        AiSuggestion(suggestions, newFolder?.takeIf { it.isNotBlank() && it != "null" }, renames)
    }.getOrNull()

    fun parseFolderDescriptions(text: String): List<FolderDescription>? = runCatching {
        val body = extractJsonObject(text) ?: return null
        val obj = json.parseToJsonElement(body).jsonObject
        obj["folders"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val path = (o["path"] as? JsonPrimitive)?.contentOrNullSafe() ?: return@mapNotNull null
            FolderDescription(
                path = path,
                description = (o["description"] as? JsonPrimitive)?.contentOrNullSafe() ?: "",
                recommended = (o["recommended"] as? JsonPrimitive)?.booleanOrFalse() ?: false,
            )
        }
    }.getOrNull()

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    private fun JsonPrimitive.booleanOrFalse(): Boolean =
        this !is JsonNull && (content.equals("true", ignoreCase = true))
}
