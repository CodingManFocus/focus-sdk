package io.modelcontextprotocol.kotlin.sdk.server

import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.kotlin.sdk.types.CompleteResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServerCompletionHandlerTest {

    @Test
    fun `setCompletionHandler throws when server has no completions capability`() {
        val server = Server(
            serverInfo = Implementation("test-server", "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities()),
        )

        val ex = assertFailsWith<IllegalStateException> {
            server.setCompletionHandler {
                CompleteResult(CompleteResult.Completion(values = emptyList()))
            }
        }
        ex.message.orEmpty() shouldContain "Server does not support completions capability"
    }

    @Test
    fun `setCompletionHandler succeeds when server declares completions capability`() {
        val server = Server(
            serverInfo = Implementation("test-server", "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(completions = ServerCapabilities.Completions),
            ),
        )

        server.setCompletionHandler { request ->
            CompleteResult(CompleteResult.Completion(values = listOf(request.argument.value)))
        }
    }
}
