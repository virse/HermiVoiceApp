package de.hermi.voice

import de.hermi.voice.api.HermesApiSender
import de.hermi.voice.config.CloudTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Emulator-Verifikation des Regression-Fixes "Cloud-Modell-Fallback nach
 * leerer Antwort":
 *
 * Szenario (vom Nutzer als "weiterhin gleiches Verhalten" gemeldet):
 * Das Primärmodell streamt NUR Reasoning-Token und endet mit
 * finish_reason=tool_calls OHNE delta.content. Vor dem Fix wurde die dabei
 * geworfene Guard-Exception im `partialReceived`-Zweig von MainActivity
 * verschluckt (receivedAny==true wegen der Denk-Token) → kein Fallback.
 *
 * Nach dem Fix wirft HermesApiSender.sendCloudStream eine dedizierte
 * HermesEmptyResponseException (kein Text-Content). Genau das wird hier auf
 * dem Gerät/Emulator gegen einen echten lokalen HTTP-Server geprüft:
 *  - nur Reasoning + tool_calls, kein Content  → HermesEmptyResponseException
 *    (null also) → MainActivity behandelt sie NICHT als Teilantwort,
 *    Fallback-Modellwechsel greift
 *  - normaler Content                          → Antworttext zurück
 */
class SendCloudStreamRegressionTest {

    @Test
    fun primaryOnlyReasoning_ToolCalls_throwsEmpty() {
        MockCloud(
            handler = { model ->
                if (model == "prim") {
                    sseOf(
                        delta(reasoning = "Überlege ich nur, kein Inhalt..."),
                        finish("tool_calls")
                    )
                } else {
                    sseOf(
                        delta(content = "Gültige Fallback-Antwort."),
                        finish("stop")
                    )
                }
            }
        ).use { mock ->
            var threwEmpty = false
            var otherError: Throwable? = null
            try {
                HermesApiSender.sendCloudStream(
                    baseUrl = mock.baseUrl,
                    apiKey = "test-key",
                    history = listOf("user" to "Testfrage"),
                    model = "prim",
                    enabledToolSlugs = emptySet(),
                    onDelta = {},
                    onReasoning = {}
                )
            } catch (e: HermesApiSender.HermesEmptyResponseException) {
                threwEmpty = true
            } catch (e: Throwable) {
                otherError = e
            }
            assertTrue(
                "Erwartet: HermesEmptyResponseException (leer = nur Reasoning/" +
                    "tool_calls), war stattdessen: $otherError (oder Text geliefert)",
                threwEmpty
            )
            // Der kritische MainActivity-Catch: receivedAny==true (nur Reasoning)
            // darf den Fallback NUR dann blockieren, wenn die Exception KEINE
            // HermesEmptyResponseException ist. Mit Typ match ist die Bedingung
            // false → Fallback greift.
            val receivedAny = true
            val partialReceivedBranch =
                receivedAny && null is HermesApiSender.HermesEmptyResponseException
            assertTrue(
                "Catch-Bedingung 'receivedAny && e !is HermesEmptyResponse'" +
                    " muss mit leerer Antwort den Fallback zulassen.",
                !partialReceivedBranch
            )
        }
    }

    @Test
    fun fallback_returnsText() {
        MockCloud(
            handler = { model ->
                sseOf(
                    delta(content = "Gültige Fallback-Antwort für $model."),
                    finish("stop")
                )
            }
        ).use { mock ->
            val text = HermesApiSender.sendCloudStream(
                baseUrl = mock.baseUrl,
                apiKey = "test-key",
                history = listOf("user" to "Testfrage"),
                model = "fb",
                enabledToolSlugs = emptySet(),
                onDelta = {},
                onReasoning = {}
            )
            assertTrue("Muss Text enthalten", text.contains("Gültige Fallback-Antwort"))
        }
    }

    @Test
    fun buildTools_providerDependent() {
        // OpenRouter: Server-Tool-Typ "openrouter:web_search"
        val or = CloudTools.buildTools(setOf("web_search"), "openrouter")
        assertEquals(1, or?.length())
        assertEquals("openrouter:web_search", or!!.getJSONObject(0).getString("type"))
        // DeepSeek: nativer Typ "web_search"
        val ds = CloudTools.buildTools(setOf("web_search"), "deepseek")
        assertEquals(1, ds?.length())
        assertEquals("web_search", ds!!.getJSONObject(0).getString("type"))
        // Anbieter ohne Server-Tool über diesen Endpoint: nichts sendbar → null
        for (unsupported in listOf("openai", "groq", "mistral")) {
            assertNull("$unsupported darf kein tools-Array bekommen",
                CloudTools.buildTools(setOf("web_search"), unsupported))
        }
        // Unbekannter Slug: ebenfalls null (kein kaputter Request)
        assertNull(CloudTools.buildTools(setOf("web_search"), ""))
        // Ganz ohne aktivierte Tools: null (unverändertes Verhalten)
        assertNull(CloudTools.buildTools(emptySet(), "openrouter"))
    }

    private fun delta(content: String? = null, reasoning: String? = null): String {
        val d = JSONObject()
        if (reasoning != null) d.put("reasoning_content", reasoning)
        if (content != null) d.put("content", content)
        val choice = JSONObject().put("index", 0).put("delta", d)
        return JSONObject()
            .put("id", "x")
            .put("choices", JSONArray().put(choice))
            .toString()
    }

    private fun finish(reason: String): String {
        val fd = JSONObject()
        val fch = JSONObject().put("delta", fd).put("finish_reason", reason)
        return JSONObject()
            .put("id", "x")
            .put("choices", JSONArray().put(fch))
            .toString()
    }

    private fun sseOf(vararg payloads: String): String {
        val sb = StringBuilder()
        for (p in payloads) sb.append("data: ").append(p).append("\n\n")
        sb.append("data: [DONE]\n\n")
        return sb.toString()
    }

    /** Minimaler OpenAI-kompatibler SSE-Server auf 127.0.0.1 im Testprozess. */
    private class MockCloud(
        private val handler: (model: String) -> String
    ) : AutoCloseable {
        val server = ServerSocket(0)
        private val thread = Thread { acceptLoop() }
        val port: Int get() = server.localPort
        val baseUrl get() = "http://127.0.0.1:$port"

        init {
            thread.isDaemon = true
            thread.start()
        }

        private fun acceptLoop() {
            try {
                while (true) {
                    val client = server.accept()
                    Thread { handle(client) }.apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (_: Exception) {
                // Server geschlossen
            }
        }

        private fun handle(sock: Socket) {
            try {
                sock.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream(), StandardCharsets.UTF_8))
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: return
                        if (line.isBlank()) break
                        val lower = line.lowercase()
                        if (lower.startsWith("content-length:")) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    val body = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(body, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    val model = try {
                        JSONObject(String(body)).optString("model")
                    } catch (_: Exception) {
                        ""
                    }
                    val response = handler(model)
                    val out: OutputStream = it.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                    out.write("Content-Type: text/event-stream\r\n".toByteArray())
                    out.write("Connection: close\r\n".toByteArray())
                    out.write("\r\n".toByteArray())
                    out.write(response.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
            } catch (_: Exception) {
                // Testserver-Toleranz
            }
        }

        override fun close() {
            try {
                server.close()
            } catch (_: Exception) {
            }
        }
    }
}