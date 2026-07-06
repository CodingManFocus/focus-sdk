package io.modelcontextprotocol.kotlin.sdk.client.auth

import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * JVM-supported JWT signing algorithms for OAuth `private_key_jwt` client assertions.
 */
public enum class McpOAuthJwtSigningAlgorithm(public val jwtHeaderValue: String) {
    /**
     * RSA PKCS#1 v1.5 with SHA-256.
     */
    RS256("RS256"),
}

/**
 * Creates short-lived signed JWT assertions for OAuth `private_key_jwt` token endpoint authentication.
 *
 * The generated assertion includes `iss`, `sub`, `aud`, `iat`, `exp`, and `jti` claims. Use the
 * authorization server token endpoint URL as [tokenEndpoint].
 */
public class McpOAuthPrivateKeyJwtAssertionProvider internal constructor(
    public val clientId: String,
    public val tokenEndpoint: String,
    private val privateKey: PrivateKey,
    public val algorithm: McpOAuthJwtSigningAlgorithm,
    public val keyId: String?,
    public val lifetimeSeconds: Long,
    private val nowEpochSeconds: () -> Long,
    private val jwtIdProvider: () -> String,
) : McpOAuthClientAssertionProvider {

    public constructor(
        clientId: String,
        tokenEndpoint: String,
        privateKey: PrivateKey,
        algorithm: McpOAuthJwtSigningAlgorithm = McpOAuthJwtSigningAlgorithm.RS256,
        keyId: String? = null,
        lifetimeSeconds: Long = 300,
    ) : this(
        clientId = clientId,
        tokenEndpoint = tokenEndpoint,
        privateKey = privateKey,
        algorithm = algorithm,
        keyId = keyId,
        lifetimeSeconds = lifetimeSeconds,
        nowEpochSeconds = { Instant.now().epochSecond },
        jwtIdProvider = ::randomMcpOAuthJwtId,
    )

    init {
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(tokenEndpoint.isNotBlank()) { "tokenEndpoint must not be blank" }
        require(lifetimeSeconds > 0) { "lifetimeSeconds must be positive" }
    }

    override suspend fun assertion(): String {
        val issuedAt = nowEpochSeconds()
        val header = buildJsonObject {
            put("alg", algorithm.jwtHeaderValue)
            put("typ", "JWT")
            keyId?.let { put("kid", it) }
        }
        val claims = buildJsonObject {
            put("iss", clientId)
            put("sub", clientId)
            put("aud", tokenEndpoint)
            put("iat", issuedAt)
            put("exp", issuedAt + lifetimeSeconds)
            put("jti", jwtIdProvider())
        }

        val signingInput = listOf(header, claims)
            .joinToString(".") { base64Url(McpJson.encodeToString(JsonObject.serializer(), it).encodeToByteArray()) }
        val signature = Signature.getInstance(algorithm.jcaSignatureAlgorithm())
        signature.initSign(privateKey)
        signature.update(signingInput.encodeToByteArray())
        return "$signingInput.${base64Url(signature.sign())}"
    }
}

/**
 * Decodes an unencrypted PKCS#8 private key PEM for use with [McpOAuthPrivateKeyJwtAssertionProvider].
 */
public fun mcpOAuthPkcs8PrivateKeyFromPem(pem: String, keyAlgorithm: String = "RSA"): PrivateKey {
    val encoded = pem.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("-----") }
        .joinToString("")
    return try {
        val bytes = Base64.getMimeDecoder().decode(encoded)
        KeyFactory.getInstance(keyAlgorithm).generatePrivate(PKCS8EncodedKeySpec(bytes))
    } catch (e: Exception) {
        throw McpOAuthException("Failed to decode PKCS#8 private key PEM", e)
    }
}

private fun McpOAuthJwtSigningAlgorithm.jcaSignatureAlgorithm(): String = when (this) {
    McpOAuthJwtSigningAlgorithm.RS256 -> "SHA256withRSA"
}

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun randomMcpOAuthJwtId(): String {
    val bytes = ByteArray(16)
    SECURE_RANDOM.nextBytes(bytes)
    return base64Url(bytes)
}

private val SECURE_RANDOM = SecureRandom()
