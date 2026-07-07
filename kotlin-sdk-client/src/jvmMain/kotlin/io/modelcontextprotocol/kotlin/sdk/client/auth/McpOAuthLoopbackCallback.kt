package io.modelcontextprotocol.kotlin.sdk.client.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Localhost OAuth authorization callback receiver for JVM clients.
 *
 * Start this receiver before preparing an authorization-code flow, pass [redirectUri] as the
 * flow request redirect URI, open the prepared authorization URL in a browser, then call
 * [awaitCallback] to receive and validate the redirect.
 *
 * The receiver binds only to loopback hosts and never persists authorization codes or tokens.
 */
public class McpOAuthLoopbackCallbackReceiver internal constructor(
    private val server: HttpServer,
    private val host: String,
    private val path: String,
    private val successResponseHtml: String,
    private val duplicateResponseHtml: String,
) : AutoCloseable {
    private val callbackUrl = CompletableDeferred<String>()
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mcp-oauth-loopback-callback").apply {
            isDaemon = true
        }
    }
    private val uriHost: String = if (':' in host) "[$host]" else host

    /** Redirect URI to register with the authorization server and use in the authorization request. */
    public val redirectUri: String = "http://$uriHost:${server.address.port}$path"

    init {
        server.executor = executor
        server.createContext(path) { exchange ->
            handle(exchange)
        }
        server.start()
    }

    /**
     * Waits for the redirect callback and validates it with [expectedState].
     *
     * The receiver is stopped after this call returns or fails. A timeout throws
     * [TimeoutCancellationException].
     */
    public suspend fun awaitCallback(
        expectedState: String? = null,
        timeoutMillis: Long = 120_000,
    ): McpOAuthAuthorizationCallback {
        try {
            val receivedUrl = withTimeout(timeoutMillis) {
                callbackUrl.await()
            }
            return parseMcpOAuthAuthorizationCallback(
                callbackUrl = receivedUrl,
                expectedState = expectedState,
            )
        } finally {
            close()
        }
    }

    /**
     * Waits for the redirect callback and validates it against a prepared authorization flow.
     */
    public suspend fun awaitCallback(
        preparedFlow: McpOAuthPreparedAuthorizationCodeFlow,
        timeoutMillis: Long = 120_000,
    ): McpOAuthAuthorizationCallback = awaitCallback(
        expectedState = preparedFlow.state,
        timeoutMillis = timeoutMillis,
    )

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.respond(405, "")
            return
        }
        if (exchange.requestURI.path != path) {
            exchange.respond(404, "")
            return
        }

        val receivedUrl = "http://$uriHost:${server.address.port}${exchange.requestURI}"
        if (callbackUrl.complete(receivedUrl)) {
            exchange.respond(200, successResponseHtml)
        } else {
            exchange.respond(409, duplicateResponseHtml)
        }
    }
}

/**
 * Starts a JVM localhost callback receiver for OAuth authorization-code redirects.
 *
 * Use port `0` to let the operating system choose a free ephemeral port. [host] must be a loopback
 * host, and [path] must be an absolute path without query or fragment components.
 */
public fun startMcpOAuthLoopbackCallbackReceiver(
    host: String = "127.0.0.1",
    port: Int = 0,
    path: String = "/callback",
    successResponseHtml: String = DEFAULT_MCP_OAUTH_LOOPBACK_SUCCESS_HTML,
    duplicateResponseHtml: String = DEFAULT_MCP_OAUTH_LOOPBACK_DUPLICATE_HTML,
): McpOAuthLoopbackCallbackReceiver {
    require(host == "127.0.0.1" || host == "localhost" || host == "::1") {
        "OAuth loopback callback host must be localhost, 127.0.0.1, or ::1"
    }
    require(port in 0..65535) {
        "OAuth loopback callback port must be between 0 and 65535"
    }
    require(path.startsWith("/") && '?' !in path && '#' !in path && path.isNotBlank()) {
        "OAuth loopback callback path must be an absolute path without query or fragment"
    }

    val server = HttpServer.create(InetSocketAddress(host, port), 1)
    return McpOAuthLoopbackCallbackReceiver(
        server = server,
        host = host,
        path = path,
        successResponseHtml = successResponseHtml,
        duplicateResponseHtml = duplicateResponseHtml,
    )
}

private fun HttpExchange.respond(statusCode: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    responseHeaders.add("Content-Type", "text/html; charset=utf-8")
    sendResponseHeaders(statusCode, bytes.size.toLong())
    responseBody.use { output ->
        output.write(bytes)
    }
}

private const val DEFAULT_MCP_OAUTH_LOOPBACK_SUCCESS_HTML: String =
    "<!doctype html><html><body><h1>Authorization complete</h1><p>You may close this window.</p></body></html>"

private const val DEFAULT_MCP_OAUTH_LOOPBACK_DUPLICATE_HTML: String =
    "<!doctype html><html><body><h1>Authorization already received</h1><p>You may close this window.</p></body></html>"
