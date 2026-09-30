package com.github.nizienko.cmdwidget.backend

import com.github.nizienko.cmdwidget.shared.ExecutionContext
import com.intellij.openapi.project.Project
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

internal object ExecutionContextResolver {
    fun resolve(project: Project): ExecutionContext = resolve(
        if (project.isDefault || project.isDisposed) null else project.basePath,
    )

    // Also used by process integration tests without an IDE project.
    fun resolve(
        root: String?,
        environment: Map<String, String> = System.getenv(),
        operatingSystem: String = System.getProperty("os.name"),
    ): ExecutionContext {
        val shell = environment["SHELL"]?.takeIf { value ->
            path(value)?.let { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) } == true
        } ?: "/bin/sh"
        val directory = root?.let(::path)
        val os = operatingSystem.lowercase(Locale.ROOT)
        val error = when {
            !os.contains("mac") && !os.contains("linux") -> "Execution is supported only on macOS and Linux backend hosts"
            directory == null || !directory.isAbsolute || !Files.isDirectory(directory) ||
                !Files.isReadable(directory) || !Files.isExecutable(directory) -> "No usable project root directory"
            else -> null
        }
        val host = environment["HOSTNAME"]?.takeIf { it.isNotBlank() } ?: runCatching {
            InetAddress.getLocalHost().hostName
        }.getOrDefault("unknown host")
        return ExecutionContext(host, directory?.normalize()?.toString(), shell, operatingSystem, error)
    }

    private fun path(value: String): Path? = try {
        Path.of(value)
    } catch (_: InvalidPathException) {
        null
    }
}
