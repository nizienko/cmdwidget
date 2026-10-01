package com.github.nizienko.cmdwidget.shared

import com.intellij.openapi.project.Project
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

object ExecutionContextResolver {
    fun resolve(project: Project, workingDirectory: String = ""): ExecutionContext = resolve(
        if (project.isDefault || project.isDisposed) null else project.basePath,
        workingDirectory = workingDirectory,
    )

    // Also used by process integration tests without an IDE project.
    fun resolve(
        root: String?,
        environment: Map<String, String> = System.getenv(),
        operatingSystem: String = System.getProperty("os.name"),
        workingDirectory: String = "",
    ): ExecutionContext {
        val strategy = HostShell.forOperatingSystem(operatingSystem)
        fun environmentValue(name: String) = environment.entries.firstOrNull {
            it.key.equals(name, ignoreCase = strategy == HostShell.WINDOWS)
        }?.value
        val candidate = if (strategy == HostShell.WINDOWS) environmentValue("ComSpec") else environment["SHELL"]
        val shell = candidate?.takeIf { value ->
            path(value)?.let { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) } == true
        } ?: if (strategy == HostShell.WINDOWS) {
            val systemRoot = environmentValue("SystemRoot")?.let(::path)?.takeIf { it.isAbsolute }
            (systemRoot ?: Path.of("C:\\Windows")).resolve("System32").resolve("cmd.exe").toString()
        } else "/bin/sh"
        val projectRoot = root?.let(::path)?.takeIf { it.isAbsolute }
        val custom = workingDirectory.takeUnless { it.isBlank() }
        val requested = custom?.let(::path)
        val directory = if (custom == null) projectRoot else requested?.let {
            if (it.isAbsolute) it else projectRoot?.resolve(it)
        }?.normalize()
        val error = when {
            strategy == null -> "Execution is supported only on macOS, Linux and Windows hosts"
            directory == null || !directory.isAbsolute || !Files.isDirectory(directory) ||
                !Files.isReadable(directory) || !Files.isExecutable(directory) -> when {
                    custom == null -> "No usable project root directory"
                    requested == null -> "Invalid working directory path"
                    !requested.isAbsolute && projectRoot == null -> "Relative working directory requires a project root"
                    else -> "Working directory is unavailable: $directory"
                }
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
