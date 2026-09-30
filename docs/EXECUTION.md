# Stage 2: backend command execution

This documents the executor milestone. Stage 4 replaces the one-shot startup probe
with live Git/Disk demo schedules and RPC presentation; see [current checks](PRESENTATION.md).

The application-scoped `CommandExecutor` provides one budget of four active
executions per backend process. Queued requests are cancellable. Calls and output
draining run on IO dispatchers; cancelling a caller or the plugin service scope
cancels its request and awaits cleanup. Cancellation is not returned as a failed
command result.

The context resolver uses the project's absolute, readable, searchable root. A
missing/invalid root or unsupported OS produces a context error without starting a
process. An absolute executable `SHELL` is selected, falling back to `/bin/sh`.
The executor starts `[shell, "-lc", command]` with inherited backend environment
and immediately closes stdin. Login-shell startup is included in the fixed
10-second timeout; time spent waiting for a slot is excluded.

Stdout and stderr are drained separately and concurrently. Each captures at most
65,536 bytes, continues draining excess, and reports truncation. Capture bytes are
decoded as UTF-8; a truncated multibyte character may be shown as a replacement
character. The shared result includes context, output, exit code, timeout, duration,
completion time, truncation flags, and startup/context/I/O/cleanup errors. Exit 0
with stderr or empty stdout is successful.

While the shell is running, descendants are tracked using Java `ProcessHandle`.
Cleanup sends graceful termination to descendants first so the shell can reap
them, then terminates the shell and forcibly terminates surviving tracked
processes. Both cleanup phases have fixed 500-ms deadlines with short bounded
reaping waits. Duration includes cleanup. Deliberately detached daemons that escape
the observable process tree are outside this mechanism; this is not a process
sandbox. Use finite noninteractive commands, rather than daemon launchers.

A temporary project startup probe executes the harmless command `pwd` once and
logs host, root, shell, exit code, and duration. The result is transient and
project-scoped. Project closure cancels the probe through its injected coroutine
scope. Scheduling and frontend RPC are subsequent stages; the status bar still
shows the hardcoded stage-1 values.

## Verification

Run:

```sh
./gradlew :backend:test :frontend:test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

Thirteen real-process tests cover directory/argument handling, EOF on stdin,
success/empty/stderr/nonzero results, invalid context, startup failure, large
simultaneous output, timeout including shell startup, timeout and cancellation of
a pipeline, forced termination, four execution slots, queued cancellation, and
service-scope cancellation. The suite takes approximately 22 seconds because it
exercises two actual 10-second timeouts.

For Linux verification from macOS, with a Docker runtime already running and a
Java 21 image available:

```sh
CMDWIDGET_DOCKER_CONTEXT=colima bash scripts/test-backend-linux.sh
```

The script compiles the tests locally and runs the same classes in a Linux
container with read-only mounts, no network, and temporary writable storage.
Override `CMDWIDGET_TEST_IMAGE` to select another compatible Java 21 image.
It does not start/stop a VM or change the active Docker context.

On 2026-09-30 all 13 tests passed on macOS and on Linux using the existing
`eclipse-temurin:21-jre` image. The five frontend lifecycle tests and the plugin
build/configuration/structure checks also passed. An ordinary sandbox IDE executed
the startup probe from the project root through `/bin/zsh`, exiting 0.
