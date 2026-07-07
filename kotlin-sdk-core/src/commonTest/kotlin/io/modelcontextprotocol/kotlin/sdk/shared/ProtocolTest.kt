package io.modelcontextprotocol.kotlin.sdk.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.types.CustomRequest
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolTest {
    private lateinit var protocol: TestProtocol
    private lateinit var transport: RecordingTransport

    @BeforeTest
    fun setUp() {
        protocol = TestProtocol()
        transport = RecordingTransport()
    }

    @Test
    fun `should preserve existing meta when adding progress token`() = runTest {
        protocol.connect(transport)
        val request = ReadResourceRequest(
            ReadResourceRequestParams(
                uri = "test://resource",
                meta = metaOf {
                    put("customField", JsonPrimitive("customValue"))
                    put("anotherField", JsonPrimitive(123))
                },
            ),
        )

        val inFlight = async {
            protocol.request<EmptyResult>(
                request = request,
                options = RequestOptions(onProgress = {}),
            )
        }

        val sent = transport.awaitRequest()
        val params = sent.params?.jsonObject.shouldNotBeNull()
        val meta = params["_meta"]?.jsonObject.shouldNotBeNull()

        params["uri"]?.jsonPrimitive?.content shouldBe "test://resource"
        meta["customField"]?.jsonPrimitive?.content shouldBe "customValue"
        meta["anotherField"]?.jsonPrimitive?.int shouldBe 123
        meta["progressToken"] shouldBe McpJson.encodeToJsonElement(sent.id)

        transport.deliver(JSONRPCResponse(sent.id, EmptyResult()))
        inFlight.await()
    }

    @Test
    fun `should create meta with progress token when none exists`() = runTest {
        protocol.connect(transport)
        val request = ReadResourceRequest(
            ReadResourceRequestParams(
                uri = "test://resource",
                meta = null,
            ),
        )

        val inFlight = async {
            protocol.request<EmptyResult>(
                request = request,
                options = RequestOptions(onProgress = {}),
            )
        }

        val sent = transport.awaitRequest()
        val params = sent.params?.jsonObject.shouldNotBeNull()
        val meta = params["_meta"]?.jsonObject.shouldNotBeNull()

        params["uri"]?.jsonPrimitive?.content shouldBe "test://resource"
        meta["progressToken"] shouldBe McpJson.encodeToJsonElement(sent.id)

        transport.deliver(JSONRPCResponse(sent.id, EmptyResult()))
        inFlight.await()
    }

    @Test
    fun `should not modify meta when onProgress is absent`() = runTest {
        protocol.connect(transport)
        val originalMeta = metaJson {
            put("customField", JsonPrimitive("customValue"))
        }
        val request = ReadResourceRequest(
            ReadResourceRequestParams(
                uri = "test://resource",
                meta = RequestMeta(originalMeta),
            ),
        )

        val inFlight = async {
            protocol.request<EmptyResult>(request)
        }

        val sent = transport.awaitRequest()
        val params = sent.params?.jsonObject.shouldNotBeNull()
        val meta = params["_meta"]?.jsonObject.shouldNotBeNull()

        meta shouldBe originalMeta
        params["uri"]?.jsonPrimitive?.content shouldBe "test://resource"

        transport.deliver(JSONRPCResponse(sent.id, EmptyResult()))
        inFlight.await()
    }

    @Test
    fun `should propagate CancellationException from notification handler without calling onError`() = runTest {
        protocol.connect(transport)

        protocol.fallbackNotificationHandler = {
            throw CancellationException("test cancellation")
        }

        shouldThrow<CancellationException> {
            transport.deliver(JSONRPCNotification(method = "test/notification"))
        }

        protocol.errors shouldHaveSize 0
    }

    @Test
    fun `should report non-cancellation exception from notification handler via onError`() = runTest {
        protocol.connect(transport)

        protocol.fallbackNotificationHandler = {
            throw IllegalStateException("handler failed")
        }

        // Non-CE exceptions are caught and reported, not propagated
        transport.deliver(JSONRPCNotification(method = "test/notification"))

        protocol.errors shouldHaveSize 1
        protocol.errors[0].message shouldBe "handler failed"
    }

    @Test
    fun `should create params object when request params are null`() = runTest {
        protocol.connect(transport)
        val request = CustomRequest(
            method = Method.Custom("example"),
            params = null,
        )

        val inFlight = async {
            protocol.request<EmptyResult>(
                request = request,
                options = RequestOptions(onProgress = {}),
            )
        }

        val sent = transport.awaitRequest()
        val params = sent.params?.jsonObject.shouldNotBeNull()
        val meta = params["_meta"]?.jsonObject.shouldNotBeNull()

        params.keys shouldContainExactly setOf("_meta")
        meta["progressToken"] shouldBe McpJson.encodeToJsonElement(sent.id)

        transport.deliver(JSONRPCResponse(sent.id, EmptyResult()))
        inFlight.await()
    }

    @Test
    fun `should timeout while awaiting response and clean up request state`() = runTest {
        protocol.connect(transport)

        val inFlight = async {
            shouldThrow<McpException> {
                protocol.request<EmptyResult>(
                    request = CustomRequest(
                        method = Method.Custom("example/slow"),
                        params = null,
                    ),
                    options = RequestOptions(
                        onProgress = {},
                        timeout = 100.milliseconds,
                    ),
                )
            }
        }

        val sent = transport.awaitRequest()
        protocol.responseHandlers.size shouldBe 1
        protocol.progressHandlers.size shouldBe 1

        advanceTimeBy(100.milliseconds)
        runCurrent()

        val cancellation = transport.awaitNotification()
        cancellation.method shouldBe Method.Defined.NotificationsCancelled.value
        val cancellationParams = cancellation.params?.jsonObject.shouldNotBeNull()
        cancellationParams["requestId"] shouldBe McpJson.encodeToJsonElement(sent.id)
        cancellationParams["reason"]?.jsonPrimitive?.content shouldBe "Request timed out"

        val error = inFlight.await()
        error.code shouldBe RPCError.ErrorCode.REQUEST_TIMEOUT
        error.data?.jsonObject?.get("timeout")?.jsonPrimitive?.int shouldBe 100
        protocol.responseHandlers.size shouldBe 0
        protocol.progressHandlers.size shouldBe 0
    }

    @Test
    fun `should use protocol default timeout when request options are absent`() = runTest {
        protocol = TestProtocol(ProtocolOptions(timeout = 100.milliseconds))
        protocol.connect(transport)

        val inFlight = async {
            shouldThrow<McpException> {
                protocol.request<EmptyResult>(
                    request = CustomRequest(
                        method = Method.Custom("example/default-timeout"),
                        params = null,
                    ),
                )
            }
        }

        val sent = transport.awaitRequest()
        protocol.responseHandlers.size shouldBe 1
        protocol.progressHandlers.size shouldBe 0

        advanceTimeBy(100.milliseconds)
        runCurrent()

        val cancellation = transport.awaitNotification()
        cancellation.method shouldBe Method.Defined.NotificationsCancelled.value
        val cancellationParams = cancellation.params?.jsonObject.shouldNotBeNull()
        cancellationParams["requestId"] shouldBe McpJson.encodeToJsonElement(sent.id)

        val error = inFlight.await()
        error.code shouldBe RPCError.ErrorCode.REQUEST_TIMEOUT
        error.data?.jsonObject?.get("timeout")?.jsonPrimitive?.int shouldBe 100
        protocol.responseHandlers.size shouldBe 0
        protocol.progressHandlers.size shouldBe 0
    }

    @Test
    fun `should not send cancellation notification when initialize request times out`() = runTest {
        protocol.connect(transport)

        val inFlight = async {
            shouldThrow<McpException> {
                protocol.request<EmptyResult>(
                    request = CustomRequest(
                        method = Method.Defined.Initialize,
                        params = null,
                    ),
                    options = RequestOptions(timeout = 100.milliseconds),
                )
            }
        }

        transport.awaitRequest()

        advanceTimeBy(100.milliseconds)
        runCurrent()

        val error = inFlight.await()
        error.code shouldBe RPCError.ErrorCode.REQUEST_TIMEOUT
        transport.hasPendingMessage() shouldBe false
        protocol.responseHandlers.size shouldBe 0
        protocol.progressHandlers.size shouldBe 0
    }

    @Test
    fun `should propagate caller timeout cancellation without converting to request timeout`() = runTest {
        protocol.connect(transport)

        val inFlight = async {
            shouldThrow<CancellationException> {
                withTimeout(100.milliseconds) {
                    protocol.request<EmptyResult>(
                        request = CustomRequest(
                            method = Method.Custom("example/caller-timeout"),
                            params = null,
                        ),
                        options = RequestOptions(
                            onProgress = {},
                            timeout = 10.seconds,
                        ),
                    )
                }
            }
        }

        transport.awaitRequest()
        protocol.responseHandlers.size shouldBe 1
        protocol.progressHandlers.size shouldBe 1

        advanceTimeBy(100.milliseconds)
        runCurrent()

        val error = inFlight.await()
        error::class shouldBe kotlinx.coroutines.TimeoutCancellationException::class
        transport.hasPendingMessage() shouldBe false
        protocol.responseHandlers.size shouldBe 0
        protocol.progressHandlers.size shouldBe 0
    }
}

private class TestProtocol(options: ProtocolOptions? = null) : Protocol(options) {
    val errors = mutableListOf<Throwable>()

    override fun onError(error: Throwable) {
        errors.add(error)
    }

    override fun assertCapabilityForMethod(method: Method) {
        // noop
    }
    override fun assertNotificationCapability(method: Method) {
        // noop
    }
    override fun assertRequestHandlerCapability(method: Method) {
        // noop
    }
}

private class RecordingTransport : Transport {
    private val sentMessages = Channel<JSONRPCMessage>(Channel.UNLIMITED)
    private var onMessageCallback: (suspend (JSONRPCMessage) -> Unit)? = null
    private var onCloseCallback: (() -> Unit)? = null

    override suspend fun start() {
        // noop
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        sentMessages.send(message)
    }

    override suspend fun close() {
        onCloseCallback?.invoke()
    }

    override fun onClose(block: () -> Unit) {
        onCloseCallback = block
    }

    override fun onError(block: (Throwable) -> Unit) {
        // noop
    }

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        onMessageCallback = block
    }

    suspend fun awaitRequest(): JSONRPCRequest {
        val message = sentMessages.receive()
        return message as? JSONRPCRequest
            ?: error("Expected JSONRPCRequest but received ${message::class.simpleName}")
    }

    suspend fun awaitNotification(): JSONRPCNotification {
        val message = sentMessages.receive()
        return message as? JSONRPCNotification
            ?: error("Expected JSONRPCNotification but received ${message::class.simpleName}")
    }

    fun hasPendingMessage(): Boolean = sentMessages.tryReceive().isSuccess

    suspend fun deliver(message: JSONRPCMessage) {
        val callback = onMessageCallback ?: error("onMessage callback not registered")
        callback(message)
    }
}

private fun metaOf(builderAction: JsonObjectBuilder.() -> Unit): RequestMeta = RequestMeta(metaJson(builderAction))

private fun metaJson(builderAction: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(builderAction)
