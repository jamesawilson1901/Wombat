package com.magpie.filer.ai

import com.magpie.filer.watch.SpottedFile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The big sort's one request shape, run for real against a local HTTP server:
 * files and runs in one batch, answers matched by what was sent, and the one
 * new permission — a folder name that does not exist yet — with everything
 * else still distrusted.
 */
class SortManyTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    private var lastBody: String? = null

    private val photo = SpottedFile(
        path = "/storage/emulated/0/Download/IMG_20190312_0001.jpg",
        name = "IMG_20190312_0001.jpg",
        size = 2_000_000L,
        source = "Downloads",
        spottedAt = 0L,
    )

    private val document = SpottedFile(
        path = "/storage/emulated/0/Download/rental agreement final.pdf",
        name = "rental agreement final.pdf",
        size = 90_000L,
        source = "Downloads",
        spottedAt = 0L,
    )

    private val run = Suggester.SortAsk.Run(
        id = "RUN_1",
        count = 34,
        range = "12 March 2019, 14:02–15:41",
        samples = listOf("IMG_0301.jpg", "IMG_0317.jpg", "IMG_0334.jpg"),
    )

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun replyWith(json: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "msg_01",
                      "type": "message",
                      "role": "assistant",
                      "model": "claude-opus-5",
                      "content": [{"type": "text", "text": ${JSONObject.quote(json)}}],
                      "stop_reason": "end_turn",
                      "usage": {"input_tokens": 100, "output_tokens": 20}
                    }
                    """.trimIndent()
                )
        )
    }

    private fun ask(
        asks: List<Suggester.SortAsk>,
        folders: List<String> = listOf("Documents", "Photos"),
    ): Map<String, FilingSuggestion> {
        val result = runBlocking { Suggester.sortMany(asks, folders, "sk-ant-test-key", baseUrl) }
        lastBody = server.takeRequest(2, TimeUnit.SECONDS)?.body?.readUtf8()
        return result
    }

    // ---- what goes on the wire ---------------------------------------------

    @Test
    fun `files and runs travel together, as metadata only`() {
        replyWith("""{"files":[]}""")
        ask(listOf(Suggester.SortAsk.One(document), run))

        val body = requireNotNull(lastBody)
        assertTrue(body, body.contains("rental agreement final.pdf"))
        assertTrue(body, body.contains("RUN_1"))
        assertTrue(body, body.contains("run of 34 files"))
        assertTrue(body, body.contains("IMG_0317.jpg"))
        assertTrue(body, body.contains("Documents"))
        assertFalse("the path on disk must not be sent", body.contains("/storage/emulated/0"))
    }

    // ---- matching answers back ---------------------------------------------

    @Test
    fun `a file's answer comes back under its path, a run's under its id`() {
        replyWith(
            """{"files":[
                {"given":"rental agreement final.pdf","name":"Rental agreement.pdf","folder":"Documents","reason":"A contract."},
                {"given":"RUN_1","name":"SPRING_WALK","folder":"Photos","reason":"One outing."}
            ]}"""
        )

        val answers = ask(listOf(Suggester.SortAsk.One(document), run))

        assertEquals("Rental agreement.pdf", answers[document.path]?.name)
        assertEquals("Documents", answers[document.path]?.folder)
        assertEquals("SPRING_WALK", answers["RUN_1"]?.name)
        assertEquals("Photos", answers["RUN_1"]?.folder)
    }

    @Test
    fun `an entry Claude invented is ignored, never misfiled`() {
        replyWith(
            """{"files":[
                {"given":"something else entirely.pdf","name":"x.pdf","folder":"Documents","reason":"?"}
            ]}"""
        )

        val answers = ask(listOf(Suggester.SortAsk.One(document)))

        assertTrue(answers.isEmpty())
    }

    @Test
    fun `a new folder name passes through for the planner to vet`() {
        replyWith(
            """{"files":[
                {"given":"rental agreement final.pdf","name":"Rental agreement.pdf","folder":"CONTRACTS","reason":"Nothing fits."}
            ]}"""
        )

        val answers = ask(listOf(Suggester.SortAsk.One(document)))

        // Not one of the offered folders — and that is allowed here, unlike
        // everywhere else. Whether it is a folder worth making is decided by
        // the sort, not taken on trust.
        assertEquals("CONTRACTS", answers[document.path]?.folder)
    }

    // ---- the distrust that stays -------------------------------------------

    @Test
    fun `a file's answer keeps its extension whatever Claude said`() {
        replyWith(
            """{"files":[
                {"given":"IMG_20190312_0001.jpg","name":"Spring walk.exe","folder":"Photos","reason":"x"}
            ]}"""
        )

        val answers = ask(listOf(Suggester.SortAsk.One(photo)))

        assertEquals("Spring walk.exe.jpg", answers[photo.path]?.name)
    }

    @Test
    fun `a run's stem is cleaned and loses any extension`() {
        replyWith(
            """{"files":[
                {"given":"RUN_1","name":"SPRING/WALK.jpg","folder":"Photos","reason":"x"}
            ]}"""
        )

        val answers = ask(listOf(run))

        assertEquals("SPRING WALK", answers["RUN_1"]?.name)
    }

    @Test
    fun `a run's answer with nothing usable left is dropped`() {
        replyWith(
            """{"files":[
                {"given":"RUN_1","name":"///","folder":"Photos","reason":"x"}
            ]}"""
        )

        assertNull(ask(listOf(run))["RUN_1"])
    }

    @Test
    fun `garbage from the server is an empty map, not a crash`() {
        replyWith("this is not json")
        assertTrue(ask(listOf(Suggester.SortAsk.One(document))).isEmpty())
    }

    @Test
    fun `a server error is an empty map and the sort carries on`() {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("""{"error":{"message":"boom"}}""")
        )
        assertTrue(ask(listOf(Suggester.SortAsk.One(document))).isEmpty())
    }

    @Test
    fun `no key or nothing to ask means no request at all`() {
        val without = runBlocking {
            Suggester.sortMany(listOf(Suggester.SortAsk.One(document)), emptyList(), " ", baseUrl)
        }
        assertTrue(without.isEmpty())
        val empty = runBlocking {
            Suggester.sortMany(emptyList(), emptyList(), "sk-ant-test-key", baseUrl)
        }
        assertTrue(empty.isEmpty())
        assertNull("no request may have been made", server.takeRequest(500, TimeUnit.MILLISECONDS))
    }
}
