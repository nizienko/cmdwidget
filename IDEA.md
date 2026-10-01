# Cmd Widgets

Cmd Widget is a JetBrains IDE plugin that allows users to create configurable widgets whose values are produced by executing terminal commands.

The plugin should be generic: disk space, Git information, Docker status, Kubernetes context, VPN status, system information, or any other command-line output should all be possible without writing additional plugin code.

The main product value is keeping important development context visible: the current Kubernetes context, cloud profile, VPN status, or service health. Git branch and disk space are useful examples, but custom information that the IDE does not already expose is the stronger use case.

The initial audience is developers who already use CLI tools and can write a command that returns a short value. Demand and competing plugins still need validation.

## Core concept

A user can create multiple widgets.

Each widget has:

- `name` — human-readable widget name
- `command` — terminal command to execute
- `refreshInterval` — how often the command should be executed
- `presentation` — defines how the result is displayed

For the initial version, only one presentation type is supported:

- `TEXT`

The command's standard output is displayed as text in the IDE status bar.

Example configuration:

```text
Name: Disk
Command: df -h / | awk 'NR==2 {print $4}'
Refresh interval: 10 seconds
Presentation: TEXT
```

Possible result in the status bar:

```text
Disk: 126G
```

Another example:

```text
Name: Git
Command: git branch --show-current
Refresh interval: 5 seconds
Presentation: TEXT
```

Result:

```text
Git: main
```

## MVP

The first version should support:

1. Adding a widget.
2. Editing an existing widget.
3. Removing a widget.
4. Enabling/disabling a widget.
5. Executing its configured command.
6. Periodically refreshing the command according to `refreshInterval`.
7. Displaying the latest successful stdout result in the IDE status bar.
8. Persisting widget configuration between IDE restarts.
9. Supporting multiple widgets simultaneously.
10. Explicitly testing an unsaved command and inspecting stdout, stderr, exit code, and duration.
11. Showing execution details and the last successful update time in a tooltip.
12. Visibly distinguishing stale values after failure from successful current values.
13. Supporting separate runtime state for each project and backend execution in split mode.

The MVP targets macOS and Linux execution hosts. Windows execution is deferred until a shell and environment policy is defined and tested.

## Execution context and scope

Definitions are application-level and shared across open projects. Execution and results are project-level, and each project window displays its own state. Two projects can therefore show different branches for the same Git widget. System-level commands also execute once per project in the MVP; cross-project deduplication is deferred.

Commands run in the current project's root directory. If no usable root exists, show a context error and do not execute; never silently fall back to the IDE process directory. Commands do not run without an open project.

In split mode, commands execute on the backend host. Shell, environment, disk space, VPN status, and CLI configuration refer to that host rather than the frontend computer. Display the execution host and working directory in the settings editor and tooltip.

## Widget model

Suggested model:

```kotlin
data class CmdWidgetConfiguration(
    val id: String,
    val name: String,
    val command: String,
    val refreshIntervalSeconds: Int,
    val presentation: PresentationType = PresentationType.TEXT,
    val enabled: Boolean = true,
)

enum class PresentationType {
    TEXT
}
```

IDs remain stable when editing widgets. Name and command must not be blank; names are limited to 40 characters and refresh intervals must be whole seconds greater than or equal to 1.

Keep configuration separate from execution context, command results, and transient widget state. Runtime state is not persisted.

The model should be designed so that more presentation types can be added later without changing the core widget execution logic.

Possible future presentation types:

```text
TEXT
ICON
PROGRESS
BADGE
COLOR
GRAPH
```

They are explicitly out of scope for the MVP.

## Command execution

Resolve the backend user's shell from an absolute, executable `SHELL` path, falling back to `/bin/sh`. Launch it as a non-interactive login shell with `-lc`, passing the complete command as one process argument. Do not interpolate the command into an additional quoted shell invocation.

The process inherits the backend environment. Login-shell startup files may adjust it and add startup time. This does not guarantee the same environment as an interactive IDE terminal. Show the selected shell and explain this policy in the editor; users can use absolute executable paths or explicit environment assignments in their commands.

Close stdin immediately. Commands execute with the backend user's permissions and may have side effects; explain this in settings. Typing or selecting a command must never execute it automatically.

The implementation should keep command execution isolated from UI code.

Create a component/service responsible for:

```text
configuration
    ↓
CommandExecutor
    ↓
stdout / stderr / exit code
    ↓
widget state
    ↓
presentation
```

Command execution must happen outside the EDT.

The IDE UI must never block while a command is running.

Commands should have a timeout to prevent a broken command from running forever.

A fixed MVP timeout, including shell startup, is:

```text
10 seconds
```

Read stdout and stderr concurrently. Capture at most 64 KiB per stream and keep draining excess output to avoid pipe deadlocks. Report truncation explicitly.

On timeout or cancellation, terminate the process and its descendants with bounded graceful termination followed by forced termination where supported. Verify cleanup with a child-process pipeline on each supported OS.

Bound active executions to four per backend process, including command tests. Queued work must be cancellable; execution timeout starts when the process starts.

`CommandResult` contains bounded stdout/stderr, nullable exit code, timeout information, completion time, duration, truncation flags, and startup-error details. Lifecycle cancellation is not a command failure.

## Refresh and lifecycle

Run enabled widgets immediately when a project's runtime starts or saved execution configuration changes. Wait `refreshIntervalSeconds` after each attempt finishes before starting the next attempt. This fixed-delay policy prevents overlap and backlogs: a 3-second command with a 10-second interval starts approximately every 13 seconds.

Only one execution may be queued or running per project/widget pair. Name-only changes update presentation without restarting execution. Command or interval changes cancel the previous attempt and schedule a new run. Disable and removal cancel work and remove the display.

Project closure and plugin unload cancel owned jobs, release widgets, and clean up processes. Track configuration/context revisions and ignore obsolete results, including results arriving during cancellation races.

## Output processing

For TEXT presentation:

- use stdout as the widget value
- trim leading/trailing whitespace
- remove ANSI escape sequences and non-display control characters
- collapse multiline output and repeated whitespace into a single-line representation
- limit the displayed value to 80 characters, including an ellipsis when shortened
- keep the last successful value while the next refresh is running
- visibly mark a retained value as stale after a failure

Exit code 0 indicates success; stderr alone does not indicate failure. Empty successful stdout is valid and displays an explicit empty-value placeholder. Nonzero exit codes, timeout, startup failure, and invalid context are failures. Before the first result, show a pending placeholder; on failure without any previous success, show an error placeholder.

Widget state stores execution status, last successful value and timestamp, and latest execution details separately. A tooltip shows bounded original output, truncation indicators, status, exit code/error, duration, last successful update time, and execution context. An old `VPN: connected` must not appear healthy after a failed refresh.

Examples:

```text
stdout:
126G

status bar:
Disk: 126G
```

Multiline:

```text
foo
bar
baz
```

can initially become:

```text
foo bar baz
```

## Status bar

Each enabled configuration appears as an independent visual command element in
each project window. One factory-owned `CustomStatusBarWidget` contains these
elements; commands still execute, update, and display diagnostics independently.
This architecture was explicitly approved for release 1.0 to avoid internal
dynamic status-bar registration/removal APIs.

Example:

```text
Git: main | Disk: 126G | Docker: 4
```

Widgets should update independently.

Changing one widget should not require recreating or refreshing all other widgets unless necessary.

Elements use stable configuration IDs. Preserve configuration order inside the
container without recreating retained elements.

Use standard factory registration for the single platform widget. Reconcile its
Swing children on EDT and release their tooltips/popups on removal, project
closure, and plugin unload. Do not dynamically register sibling platform widgets.

## Settings UI

Add a settings page under:

```text
Settings / Preferences
    → Tools
        → Cmd Widget
```

The page should contain a list of configured widgets.

Basic actions:

```text
+ Add
Edit
Remove
Enable / Disable
```

The widget editor should contain:

```text
Name
Command
Refresh interval
Enabled
Test command
```

Keep `TEXT` in the model, but omit a presentation selector until a second presentation type exists.

Test command runs the unsaved command explicitly in the current project's backend context using the same executor, limits, timeout, and cancellation policy as normal execution. Show stdout, stderr, exit code, duration, and truncation indicators. Testing must not save definitions or overwrite live widget values. Disable testing without a usable project context. Closing the editor cancels its test.

Show host, working directory, selected shell, and environment policy in the editor. Apply / OK commits draft changes; Cancel discards them.

## Persistence

Widget configuration should be stored using standard IntelliJ Platform persistent state APIs.

The configuration must survive:

- IDE restart
- project restart
- plugin update when possible

For the initial version, definitions are application-level and global to the IDE installation; runtime state remains project-level.

Only definitions, stable IDs, enabled flags, and ordering are persisted. Do not persist output, errors, process handles, or scheduling state. Supply defaults for new fields and validate loaded definitions.

In split mode, the frontend owns global definitions and the backend receives effective configuration through the platform's application-settings synchronization mechanism. Register synchronization metadata with `InitialFromFrontend` for the initial synchronization direction; avoid two independently editable copies.

## Architecture

Keep the implementation separated into roughly these concepts:

```text
CmdWidgetConfiguration
CmdWidgetSettingsService

CommandExecutor
CommandResult

CmdWidgetManager
CmdStatusBarWidget

Presentation
TextPresentation
```

The exact class names are not mandatory.

The important architectural rule is:

```text
command execution != presentation != persistence
```

A widget should produce a value independently of how that value is rendered.

This will allow future presentation types to reuse the same command execution infrastructure.

Use the existing repository modules:

- `shared`: configuration/result/state DTOs, serialization, and RPC contracts.
- `backend`: executor, bounded scheduling, project runtime, process cleanup, and RPC implementation.
- `frontend`: global settings UI and persistence ownership, status bar lifecycle, text presentation, and backend adapter.

Register settings synchronization metadata where both sides can access it. Backend state is project-scoped and exposed through RPC. Frontend reconnect retrieves current state without creating another scheduler; version updates so obsolete responses cannot overwrite newer state.

Start with a simple text formatter. A hierarchy of presentation implementations is optional until another presentation type requires it.

## Future extensions

Do not implement these yet, but avoid architectural decisions that would make them difficult:

- different presentation types
- custom icons
- colors based on command result
- progress bars
- click actions
- command execution on click
- project-specific widgets
- environment variables
- configurable working directory
- JSON parsing
- regex extraction
- templates
- shared widget presets
- import/export
- Marketplace presets such as Disk Space, Git Branch, Docker Containers, Kubernetes Context
- Windows command execution
- cross-project deduplication of system-level commands

## Non-goals for MVP

Do not implement:

- a custom terminal
- interactive commands
- stdin handling
- terminal emulation
- shell history
- charts
- scripting language
- arbitrary remote-host execution or a separate SSH client; execution on the IDE backend in split mode is in scope
- command pipelines implemented inside the plugin

The shell itself should handle normal shell syntax and pipelines.

## Development priorities

Implement the project incrementally.

The repository already contains a modular plugin scaffold with split mode enabled. Extend it instead of generating another project.

Suggested order:

1. Verify ordinary/split-mode launch and two dynamically managed hardcoded status bar widgets.
2. Implement asynchronous execution with timeout, bounded output, and process cancellation.
3. Add configuration, independent project runtimes, fixed-delay refresh, and obsolete-result protection.
4. Connect backend state to frontend widgets through RPC; add text normalization and stale/error tooltips.
5. Add application-level persistence and split-mode settings synchronization.
6. Add settings CRUD, validation, and explicit command testing.
7. Verify multi-project behavior, reconnect, restart, and cleanup; prepare the distributable plugin.

Detailed tasks and acceptance criteria are in [PLAN.md](PLAN.md).

At every step, keep the plugin runnable.

Avoid introducing unnecessary abstractions before they are needed.

## MVP success criteria

The MVP is complete when a user can open the IDE settings, create:

```text
Name: Disk
Command: df -h / | awk 'NR==2 {print $4}'
Refresh: 10 seconds
```

and immediately see something similar to:

```text
Disk: 126G
```

in the IDE status bar.

The value should refresh automatically using the documented 10-second fixed delay after each execution and remain configured after restarting the IDE.

Multiple independently refreshing widgets must be supported. A user can explicitly test a command before applying settings.

Two projects receive results from their own directories. Slow commands, large output, editing, removal, project closure, and plugin unloading do not block the UI or leave owned processes running. Failed refreshes visibly distinguish stale values from successful current values.

The workflow also works in split mode, with identified backend-host context, synchronized definitions, and no duplicate execution after frontend reconnect.

## Platform references

- [Status bar widgets](https://plugins.jetbrains.com/docs/intellij/status-bar-widgets.html)
- [Split mode and remote development](https://plugins.jetbrains.com/docs/intellij/split-mode-and-remote-development.html)
- [Persistent state in split mode](https://plugins.jetbrains.com/docs/intellij/persistent-state-in-split-mode.html)
