package io.modelcontextprotocol.kotlin.sdk.client.auth

import java.awt.Desktop
import java.io.IOException
import java.net.URI

/**
 * Opens an MCP OAuth authorization request URL in the JVM system browser.
 *
 * Returns `false` when the current JVM or host does not support system-browser opening. Invalid
 * authorization URLs and browser launch failures are reported as [McpOAuthException] so callers can
 * fall back to copying the URL into their own UI.
 *
 * For safety, the URL must use `https`.
 */
public fun openMcpOAuthAuthorizationUrlInBrowser(authorizationUrl: String): Boolean =
    openMcpOAuthAuthorizationUrlInBrowser(
        authorizationUrl = authorizationUrl,
        isBrowserSupported = ::isSystemBrowserSupported,
        browse = { uri -> Desktop.getDesktop().browse(uri) },
    )

internal fun openMcpOAuthAuthorizationUrlInBrowser(
    authorizationUrl: String,
    isBrowserSupported: () -> Boolean,
    browse: (URI) -> Unit,
): Boolean {
    val uri = validatedMcpOAuthAuthorizationBrowserUri(authorizationUrl)
    if (!isBrowserSupported()) {
        return false
    }

    try {
        browse(uri)
        return true
    } catch (_: UnsupportedOperationException) {
        return false
    } catch (e: SecurityException) {
        throw McpOAuthException("Failed to open OAuth authorization URL in browser", e)
    } catch (e: IOException) {
        throw McpOAuthException("Failed to open OAuth authorization URL in browser", e)
    }
}

private fun validatedMcpOAuthAuthorizationBrowserUri(authorizationUrl: String): URI {
    val uri = try {
        URI(authorizationUrl)
    } catch (e: IllegalArgumentException) {
        throw McpOAuthException("OAuth authorization URL is not a valid URI", e)
    }

    val scheme = uri.scheme?.lowercase()
    val host = uri.host
    if (scheme != "https") {
        throw McpOAuthException("OAuth authorization URL must use https")
    }
    if (!uri.isAbsolute || host.isNullOrBlank()) {
        throw McpOAuthException("OAuth authorization URL must be absolute and include a host")
    }
    if (uri.userInfo != null) {
        throw McpOAuthException("OAuth authorization URL must not include user info")
    }
    return uri
}

private fun isSystemBrowserSupported(): Boolean =
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
