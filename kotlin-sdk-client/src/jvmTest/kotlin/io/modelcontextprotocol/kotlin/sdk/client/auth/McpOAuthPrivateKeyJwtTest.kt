package io.modelcontextprotocol.kotlin.sdk.client.auth

import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpOAuthPrivateKeyJwtTest {

    @Test
    fun `should create signed private key jwt assertion`() = runTest {
        val keyPair = rsaKeyPair()
        val provider = McpOAuthPrivateKeyJwtAssertionProvider(
            clientId = "my-service",
            tokenEndpoint = "https://auth.example.com/token",
            privateKey = keyPair.private,
            algorithm = McpOAuthJwtSigningAlgorithm.RS256,
            keyId = "key-1",
            lifetimeSeconds = 300,
            nowEpochSeconds = { 1_700_000_000 },
            jwtIdProvider = { "jwt-id-1" },
        )

        val assertion = provider.assertion()
        val parts = assertion.split(".")

        assertEquals(3, parts.size)
        assertEquals(
            mapOf("alg" to "RS256", "typ" to "JWT", "kid" to "key-1"),
            decodeJsonObject(parts[0]).mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(
            mapOf(
                "iss" to "my-service",
                "sub" to "my-service",
                "aud" to "https://auth.example.com/token",
                "iat" to "1700000000",
                "exp" to "1700000300",
                "jti" to "jwt-id-1",
            ),
            decodeJsonObject(parts[1]).mapValues { it.value.jsonPrimitive.content },
        )

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(keyPair.public)
        verifier.update("${parts[0]}.${parts[1]}".encodeToByteArray())
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])))
    }

    @Test
    fun `should decode pkcs8 private key pem`() = runTest {
        val keyPair = rsaKeyPair()
        val pem = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            appendLine(Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(keyPair.private.encoded))
            appendLine("-----END PRIVATE KEY-----")
        }
        val privateKey = mcpOAuthPkcs8PrivateKeyFromPem(pem)
        val provider = McpOAuthPrivateKeyJwtAssertionProvider(
            clientId = "my-service",
            tokenEndpoint = "https://auth.example.com/token",
            privateKey = privateKey,
            algorithm = McpOAuthJwtSigningAlgorithm.RS256,
            keyId = null,
            lifetimeSeconds = 300,
            nowEpochSeconds = { 1_700_000_000 },
            jwtIdProvider = { "jwt-id-1" },
        )

        val assertion = provider.assertion()
        val parts = assertion.split(".")
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(keyPair.public)
        verifier.update("${parts[0]}.${parts[1]}".encodeToByteArray())

        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])))
    }

    @Test
    fun `should reject invalid private key jwt parameters`() {
        val privateKey = rsaKeyPair().private

        assertFailsWith<IllegalArgumentException> {
            McpOAuthPrivateKeyJwtAssertionProvider(
                clientId = "",
                tokenEndpoint = "https://auth.example.com/token",
                privateKey = privateKey,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            McpOAuthPrivateKeyJwtAssertionProvider(
                clientId = "my-service",
                tokenEndpoint = "",
                privateKey = privateKey,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            McpOAuthPrivateKeyJwtAssertionProvider(
                clientId = "my-service",
                tokenEndpoint = "https://auth.example.com/token",
                privateKey = privateKey,
                lifetimeSeconds = 0,
            )
        }
    }

    private fun rsaKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    private fun decodeJsonObject(jwtPart: String): JsonObject {
        val json = Base64.getUrlDecoder().decode(jwtPart).decodeToString()
        return McpJson.parseToJsonElement(json).jsonObject
    }
}
