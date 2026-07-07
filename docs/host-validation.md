# Host Validation Guide

This guide covers host-side validation patterns for security-sensitive MCP
operations in Kotlin applications.

Official specifications:

- https://modelcontextprotocol.io/specification/2025-11-25/client/roots
- https://modelcontextprotocol.io/specification/2025-11-25/server/resources

MCP message types can represent filesystem roots, resource URIs, and tool
arguments, but the host application owns permission checks. Validate at the
boundary where protocol values become local filesystem paths, network requests,
database ids, or other privileged operations.

## Responsibilities

For roots, clients MUST only expose roots with appropriate permissions and MUST
validate root URIs to prevent path traversal. Servers SHOULD respect root
boundaries and validate paths against the roots the client provides.

For resources, servers MUST validate all resource URIs. Access controls SHOULD
be implemented for sensitive resources, and resource permissions SHOULD be
checked before operations.

The Kotlin SDK validates protocol shapes and capability use. It does not know
which local paths, remote hosts, database rows, or secret stores a host
application is allowed to expose.

## Root Grants

Register only roots the user explicitly granted to the connected server. Keep
the grant narrow and revoke it when the project closes or permissions change.

```kotlin
client.addRoot(
    uri = "file:///Users/alice/work/project",
    name = "project",
)
```

Do not register a home directory, credential store, build-cache directory, or
temporary directory unless that exact access is intended.

## JVM Path Guard

On JVM, convert `file://` roots to normalized `Path` values before authorizing
filesystem work. The example below rejects non-file roots, relative roots,
traversal, and paths outside every granted root.

```kotlin
import java.net.URI
import java.nio.file.Path

class WorkspacePathGuard(rootUris: List<String>) {
    private val roots: List<Path> = rootUris.map { rootUri ->
        val uri = URI(rootUri)
        require(uri.scheme == "file") { "Root URI must use file://: $rootUri" }

        Path.of(uri).toAbsolutePath().normalize()
    }

    fun resolveAllowed(requestedPath: String): Path {
        val candidate = Path.of(requestedPath).toAbsolutePath().normalize()
        require(roots.any { root -> candidate.startsWith(root) }) {
            "Path is outside granted roots"
        }
        return candidate
    }
}
```

Use the equivalent platform path APIs on JS, Native, and Wasm hosts. If the host
follows symlinks, compare the resolved target path against granted roots before
opening the file.

## Resource URI Guard

Resource URIs may use `file://`, `https://`, `git://`, or a custom scheme. The
resources specification requires servers to validate every URI. Keep scheme
handling explicit:

```kotlin
import java.net.URI
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError

fun validateResourceUri(rawUri: String): URI {
    val uri = runCatching { URI(rawUri) }.getOrElse {
        throw McpException(
            code = RPCError.ErrorCode.INVALID_PARAMS,
            message = "Invalid resource URI",
        )
    }

    when (uri.scheme) {
        "file" -> if (uri.host != null && uri.host.isNotEmpty()) {
            rejectInvalidResourceUri("Unexpected file URI host")
        }
        "https" -> if (uri.host !in setOf("docs.example.com", "api.example.com")) {
            rejectInvalidResourceUri("Host is not allowed")
        }
        "app" -> if (uri.schemeSpecificPart.isBlank()) {
            rejectInvalidResourceUri("Missing app resource id")
        }
        else -> rejectInvalidResourceUri("Unsupported resource URI scheme")
    }

    return uri
}

private fun rejectInvalidResourceUri(message: String): Nothing =
    throw McpException(
        code = RPCError.ErrorCode.INVALID_PARAMS,
        message = message,
    )
```

Validate again in `resources/read`, `resources/subscribe`, and
`resources/unsubscribe`; do not rely only on values previously returned from
`resources/list`.

## Resource Template Guard

Resource templates are convenient but easy to overexpose. Validate expanded
parameters before reading local data.

```kotlin
import java.nio.file.Path
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

fun readProjectFile(
    request: ReadResourceRequest,
    pathGuard: WorkspacePathGuard,
): ReadResourceResult {
    val uri = validateResourceUri(request.params.uri)
    require(uri.scheme == "file") { "Expected a file resource" }

    val path = pathGuard.resolveAllowed(Path.of(uri).toString())
    val text = path.toFile().readText()

    return ReadResourceResult(
        contents = listOf(
            TextResourceContents(
                text = text,
                uri = request.params.uri,
                mimeType = "text/plain",
            ),
        ),
    )
}
```

For non-JVM targets, replace `Path.of(uri)` and file reads with host platform
APIs while keeping the same authorization checks.

## Tool Argument Guard

Tools that accept paths, URLs, package names, database ids, shell commands, or
other host-controlled selectors need the same validation. For example, a tool
that reads a project file should accept a relative path, join it to a granted
root, normalize it, and reject traversal before opening the file.

```kotlin
import java.nio.file.Path

fun resolveRelativeProjectPath(root: Path, relativePath: String): Path {
    require(!Path.of(relativePath).isAbsolute) {
        "Tool path must be relative"
    }

    val candidate = root.resolve(relativePath).normalize()
    require(candidate.startsWith(root.normalize())) {
        "Tool path is outside the project root"
    }
    return candidate
}
```

Avoid passing unvalidated tool arguments into shell commands, file APIs, SQL
queries, HTTP clients, package managers, or source-control commands.

## Checklist

- Validate every URI or path at the operation that uses it.
- Normalize paths before comparing them to roots.
- Re-check root grants after `notifications/roots/list_changed`.
- Treat removed roots as immediately revoked.
- Reject unexpected URI schemes and unexpected remote hosts.
- Keep allowlists narrow and explicit.
- Do not trust display names, annotations, MIME types, or descriptions as
  authorization data.
- Avoid logging raw paths, secrets, tokens, or user content.
- Return `Invalid params` for malformed or unauthorized selectors when the
  request shape is valid but the value is not allowed.
- Keep validation code close to the host API call that performs privileged work.
