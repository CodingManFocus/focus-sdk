package io.modelcontextprotocol.kotlin.sdk.conformance.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.net.URI

internal suspend fun followAuthorizationRedirect(
    httpClient: HttpClient,
    authUrl: String,
    expectedCallbackUrl: String,
    expectedState: String,
): String {
    val response = httpClient.get(authUrl)

    if (response.status == HttpStatusCode.Found ||
        response.status == HttpStatusCode.MovedPermanently ||
        response.status == HttpStatusCode.TemporaryRedirect ||
        response.status == HttpStatusCode.SeeOther
    ) {
        val location = response.headers[HttpHeaders.Location]
            ?: error("No Location header in redirect response")

        require(location.startsWith(expectedCallbackUrl)) {
            "Redirect location does not match expected callback URL"
        }

        val uri = URI(location)
        val queryParams = uri.query?.split("&")?.mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else null
        }?.toMap() ?: emptyMap()

        val returnedState = queryParams["state"]
        require(returnedState == expectedState) {
            "State parameter mismatch in authorization redirect"
        }

        return queryParams["code"] ?: error("No authorization code in redirect response")
    }

    error("Expected redirect from auth endpoint, got ${response.status}")
}
