package com.magpie.filer.ai

import com.magpie.filer.watch.SpottedFile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * The Anthropic call, run for real against a local HTTP server.
 *
 * Everything except Anthropic's own servers is exercised here: the request the
 * SDK actually puts on the wire, the JSON that comes back, every failure the
 * user can hit, and the re-cleaning of a reply that cannot be trusted.
 *
 * The most important case is [only metadata is ever sent]: it reads the request
 * body and proves the file's contents are not in it.
 */
class SuggesterTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** The last request body the server received, for inspection. */
    @Volatile
    private var lastBody: String? = null

    /** The last Authorization/x-api-key headers seen. */
    @Volatile
    private var lastApiKey: String? = null

    /** What the next request should be answered with. */
    @Volatile
    private var status: Int = 200

    @Volatile
    private var reply: String = ""

    private val file = SpottedFile(
        path = "/storage/emulated/0/Download/inv_2024_q3_acme__FINAL(2).pdf",
        name = "inv_2024_q3_acme__FINAL(2).pdf",
        size = 248_000L,
        source = "Downloads",
        spottedAt = 0L,
    )

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            lastApiKey = exchange.requestHeaders.getFirst("x-api-key")
                ?: exchange.requestHeaders.getFirst("authorization")
            val bytes = reply.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun ask(
        folders: List<String> = listOf("Invoices", "Contracts", "Photos"),
        key: String = "sk-ant-test-key",
        url: String? = baseUrl,
    ): SuggestionResult = runBlocking { Suggester.suggest(file, folders, key, url) }

    /** A well-formed Messages API reply carrying [json] as the text block. */
    private fun replyWith(json: String, stopDetails: String? = null) {
        status = 200
        reply = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-5",
              "content": [{"type": "text", "text": ${JSONObject.quote(json)}}],
              "stop_reason": ${if (stopDetails == null) "\"end_turn\"" else "\"refusal\""},
              ${if (stopDetails == null) "" else "\"stop_details\": $stopDetails,"}
              "usage": {"input_tokens": 100, "output_tokens": 20}
            }
        """.trimIndent()
    }

    // ---- what actually goes on the wire ------------------------------------

    @Test
    fun `only metadata is ever sent, never the file`() {
        replyWith("""{"name":"Acme invoice Q3 2024.pdf","folder":"Invoices","reason":"An invoice."}""")
        ask()

        val body = requireNotNull(lastBody) { "no request reached the server" }

        // The things that are allowed to be there.
        assertTrue(body, body.contains("inv_2024_q3_acme__FINAL(2).pdf"))
        assertTrue(body, body.contains("Downloads"))
        assertTrue(body, body.contains("Invoices"))

        // The path on disk is not metadata anyone needs, and the file's bytes
        // are never read at all — so neither may appear.
        assertFalse("the full path must not be sent", body.contains("/storage/emulated/0"))
        assertFalse(body, body.contains("\"path\""))
    }

    @Test
    fun `the request is shaped the way the API documents`() {
        replyWith("""{"name":"a.pdf","folder":"","reason":"x"}""")
        ask()

        val body = JSONObject(requireNotNull(lastBody))
        assertEquals("claude-opus-5", body.getString("model"))
        assertEquals(2048, body.getInt("max_tokens"))
        assertTrue(body.has("system"))

        val output = body.getJSONObject("output_config")
        assertEquals("low", output.getString("effort"))

        val schema = output.getJSONObject("format").getJSONObject("schema")
        assertEquals("object", schema.getString("type"))
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(
            setOf("name", "folder", "reason"),
            schema.getJSONArray("required").let { array ->
                (0 until array.length()).map { array.getString(it) }.toSet()
            },
        )
        assertTrue(schema.getJSONObject("properties").has("name"))
    }

    @Test
    fun `the api key is sent as the header Anthropic expects`() {
        replyWith("""{"name":"a.pdf","folder":"","reason":"x"}""")
        ask(key = "sk-ant-particular-key")
        assertEquals("sk-ant-particular-key", lastApiKey)
    }

    @Test
    fun `the folders offered are the ones passed, and nothing else`() {
        replyWith("""{"name":"a.pdf","folder":"","reason":"x"}""")
        ask(folders = listOf("Only One"))
        val body = requireNotNull(lastBody)
        assertTrue(body, body.contains("Only One"))
        assertFalse(body, body.contains("Invoices"))
    }

    @Test
    fun `with no folders it is told to suggest a name only`() {
        replyWith("""{"name":"a.pdf","folder":"","reason":"x"}""")
        ask(folders = emptyList())
        assertTrue(requireNotNull(lastBody).contains("suggest a name only"))
    }

    // ---- a good reply ------------------------------------------------------

    @Test
    fun `a well formed reply is read back`() {
        replyWith(
            """{"name":"Acme invoice Q3 2024.pdf","folder":"Invoices","reason":"It is an invoice."}"""
        )

        val result = ask()

        assertTrue(result.toString(), result is SuggestionResult.Ready)
        val suggestion = (result as SuggestionResult.Ready).suggestion
        assertEquals("Acme invoice Q3 2024.pdf", suggestion.name)
        assertEquals("Invoices", suggestion.folder)
        assertEquals("It is an invoice.", suggestion.reason)
    }

    // ---- a reply that cannot be trusted ------------------------------------

    @Test
    fun `a name with a path separator in it cannot escape the folder`() {
        replyWith("""{"name":"../../etc/passwd.pdf","folder":"Invoices","reason":"x"}""")

        val name = (ask() as SuggestionResult.Ready).suggestion.name

        assertFalse(name, name.contains("/"))
        assertFalse(name, name.contains("\\"))
    }

    @Test
    fun `a name that would become a hidden dotfile falls back to the original`() {
        replyWith("""{"name":".pdf","folder":"Invoices","reason":"x"}""")

        val name = (ask() as SuggestionResult.Ready).suggestion.name

        assertFalse(name, name.startsWith("."))
        assertEquals(file.name, name)
    }

    @Test
    fun `a name that changes the extension has the original put back`() {
        replyWith("""{"name":"invoice.exe","folder":"Invoices","reason":"x"}""")

        val name = (ask() as SuggestionResult.Ready).suggestion.name

        assertTrue(name, name.endsWith(".pdf"))
    }

    @Test
    fun `an empty name falls back to the original`() {
        replyWith("""{"name":"","folder":"","reason":"x"}""")
        assertEquals(file.name, (ask() as SuggestionResult.Ready).suggestion.name)
    }

    @Test
    fun `a missing field does not throw`() {
        replyWith("""{"name":"fine.pdf"}""")

        val result = ask()

        assertTrue(result.toString(), result is SuggestionResult.Ready)
        assertEquals("", (result as SuggestionResult.Ready).suggestion.folder)
    }

    // ---- every way it can fail ---------------------------------------------

    @Test
    fun `a refusal is reported with its category`() {
        replyWith(
            """{"name":"a.pdf","folder":"","reason":"x"}""",
            stopDetails = """{"type":"refusal","category":"other"}""",
        )

        val result = ask()

        assertTrue(result.toString(), result is SuggestionResult.Failed)
        val reason = (result as SuggestionResult.Failed).reason
        assertTrue(reason, reason.contains("declined"))
        // The Optional must have been unwrapped, not printed.
        assertFalse(reason, reason.contains("Optional"))
    }

    @Test
    fun `a rejected key says so in words the user can act on`() {
        status = 401
        reply = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""

        val result = ask()

        assertTrue(result.toString(), result is SuggestionResult.Failed)
        val reason = (result as SuggestionResult.Failed).reason
        assertTrue(reason, reason.contains("rejected the API key"))
        assertTrue(reason, reason.contains("sk-ant-"))
    }

    @Test
    fun `a rate limit says to wait`() {
        status = 429
        reply = """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""

        val result = ask()

        assertTrue(result.toString(), result is SuggestionResult.Failed)
        assertTrue((result as SuggestionResult.Failed).reason.contains("rate limiting"))
    }

    @Test
    fun `a bad request is owned as Magpie's fault, not the user's`() {
        status = 400
        reply = """{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}"""

        val result = ask()

        assertTrue(result.toString(), result is SuggestionResult.Failed)
        assertTrue((result as SuggestionResult.Failed).reason.contains("bug in Magpie"))
    }

    @Test
    fun `a server error is reported with its status`() {
        status = 500
        reply = """{"type":"error","error":{"type":"api_error","message":"oh dear"}}"""

        val result = ask()

        assertTrue(result.toString(), result is SuggestionResult.Failed)
        assertTrue((result as SuggestionResult.Failed).reason.contains("500"))
    }

    @Test
    fun `nothing reachable at all is reported as a network problem`() {
        // A port with nothing listening on it.
        val dead = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = dead.address.port
        dead.stop(0)

        val result = ask(url = "http://127.0.0.1:$port")

        assertTrue(result.toString(), result is SuggestionResult.Failed)
        val reason = (result as SuggestionResult.Failed).reason
        assertTrue(reason, reason.contains("Filing still works"))
    }

    @Test
    fun `a reply that is not JSON is reported rather than crashing`() {
        replyWith("this is not json at all")

        val result = ask()

        assertTrue(result.toString(), result is SuggestionResult.Failed)
        assertTrue((result as SuggestionResult.Failed).reason.contains("not in the shape"))
    }

    @Test
    fun `an empty reply is reported`() {
        status = 200
        reply = """
            {"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5",
             "content":[],"stop_reason":"end_turn",
             "usage":{"input_tokens":1,"output_tokens":1}}
        """.trimIndent()

        val result = ask()

        assertTrue(result.toString(), result is SuggestionResult.Failed)
        assertTrue((result as SuggestionResult.Failed).reason.contains("nothing at all"))
    }

    @Test
    fun `no api key means no request is made at all`() {
        replyWith("""{"name":"a.pdf","folder":"","reason":"x"}""")
        lastBody = null

        val result = ask(key = "   ")

        assertTrue(result.toString(), result is SuggestionResult.Failed)
        assertTrue((result as SuggestionResult.Failed).reason.contains("no API key saved"))
        assertEquals("nothing should have been sent", null, lastBody)
    }

    @Test
    fun `a failure never throws out of the suggester`() {
        // Whatever happens, the caller gets a sentence, because filing carries
        // on without a suggestion and must not be taken down by one.
        status = 503
        reply = "<html>gateway</html>"
        assertNotNull(ask())
    }
}
