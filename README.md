# Cmd Widget

Cmd Widget displays the output of your shell commands as independent text widgets
in the IntelliJ IDEA status bar. Definitions are global; each open project runs
them in its own root directory and keeps its own results. In split mode commands
run on the backend host.

This development build targets IntelliJ IDEA 2026.1.5. Command execution supports
macOS and Linux backend hosts. Windows execution and compatibility with other IDE
versions are not claimed. Manual end-to-end acceptance is still pending; see
[PLAN.md](PLAN.md).

## Install and create a widget

Build with Java 21:

```sh
./gradlew buildPlugin
```

Install `build/distributions/cmdwidget-1.0.0-SNAPSHOT.zip` through Settings /
Preferences → Plugins → gear menu → Install Plugin from Disk. In remote
development, install the plugin on both frontend and backend.

Open Settings / Preferences → Tools → Cmd Widget. Click Add, enter Name, Command,
a positive whole-second Refresh interval, and Enabled. Choose the open project
for command tests on the settings page. The editor displays its backend host,
root directory, and shell. Test command explicitly executes the current unsaved
command and shows stdout, stderr, exit code, duration, errors, and truncation.
Typing and selecting entries never run a test.

Click editor OK to update the settings draft, then Settings Apply / OK to save
and update open projects. Settings Cancel discards unapplied changes. Changes
already committed with Apply remain saved. Edit, Remove, Enable / Disable, and
Move up / Move down operate on the draft.

Examples:

| Name | Command | Interval |
| --- | --- | --- |
| Disk | `df -h / \| awk 'NR==2 {print $4}'` | 10 s |
| Git | `git branch --show-current` | 5 s |
| Kubernetes | `kubectl config current-context` | 30 s |

These are examples, not presets; new installations start with an empty list.
Global definitions, stable IDs, order, and enabled flags are saved in the IDE's
`options/cmd-widget.xml`. Output and execution state are not persisted.

## Execution and refresh

Commands execute in the backend project's root directory. Without an open project
and a usable root, execution is unavailable; there is no fallback directory.
In split mode, disk space, CLI configuration, environment, and permissions belong
to the backend host. Two projects can therefore show different Git branches for
one global definition.

The backend uses an absolute executable `SHELL` from its environment, falling
back to `/bin/sh`, and launches a non-interactive login shell with `-lc`. It
inherits the backend environment; shell startup files may alter it. This need not
match an interactive IDE terminal. Use absolute executable paths or explicit
environment assignments when needed. Stdin is closed. Commands run with the
backend user's permissions and can have side effects.

Enabled widgets run immediately, then wait the configured interval after each
attempt finishes. A three-second command with a ten-second interval starts about
every thirteen seconds. Each widget has at most one queued/running attempt.
Four executions can run per backend process, including tests; others queue.
Each execution has a ten-second timeout including shell startup. Both streams
are drained concurrently, capturing at most 64 KiB each with explicit truncation.
Cancellation and timeout perform bounded descendant-process cleanup.

Rename and ordering edits preserve execution state. Command/interval changes
cancel the old attempt before replacement; disabling/removing stops that widget.
Project closure and plugin unload cancel owned work. Closing the editor or
clicking Cancel test cancels its test. Tests do not save definitions or change
live widget values and are not replayed automatically after a connection failure.

## Values and diagnostics

Successful stdout becomes a single line with terminal sequences and controls
removed, whitespace collapsed, and display length limited to 80 Unicode
characters. Empty successful output has an explicit empty placeholder. Exit 0
is success even with stderr; nonzero exit, timeout, or execution/context errors
are failures.

During refresh, the last successful value remains visible. Failure or disconnect
marks a retained value as stale; failure before the first success shows an error.
Tooltips include bounded diagnostics, host/directory/shell, exit/error, duration,
completion time, and last successful update time.

## Troubleshooting and limitations

- No widgets: create and Apply an enabled definition and make sure the status bar
  is visible. New installations contain no demo commands.
- Command not found or wrong environment: inspect the backend host/shell and use
  an absolute path. Login-shell setup can differ from the IDE terminal.
- Test command disabled: open/select a project with a usable backend root. After
  a failed backend context lookup, close and reopen the editor to retry.
- Stale/error value: hover for the last result and context. Test the command
  explicitly in that project; check exit status, stderr, timeout, and permissions.
- Split-mode definitions missing: ensure the plugin is installed on both sides.
  The frontend owns settings and sends them to the backend. Actual initial
  synchronization after restart and visual reconnect acceptance remain pending.
- Timeout/truncation: keep commands short and non-interactive. These limits are
  fixed in the MVP.
- Processes use best-effort bounded descendant cleanup; commands deliberately
  detaching children are outside the verified pipeline-cleanup scenarios.

Only text presentation is available. Windows execution, project-specific
definitions, custom directory/environment, presets, import/export, manual
refresh, and cross-project deduplication are deferred. System commands execute
once per project.

## Development and verification

The plugin has three modules: `shared` (DTOs, persistent definitions, RPC
contract), `backend` (execution, scheduling, RPC provider), and `frontend`
(settings, explicit test adapter, status bar lifecycle/presentation).

```sh
./gradlew :backend:test :frontend:test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
./gradlew runIde
./gradlew runIdeSplitMode
```

Run configurations provide ordinary and split-mode launches. Automated suites
cover execution limits/cleanup, runtime reconciliation, persistence, RPC,
presentation, widget lifecycle, and settings/test isolation.

The acceptance procedures are in [settings and testing](docs/SETTINGS.md),
[execution](docs/EXECUTION.md), [runtime](docs/RUNTIME.md),
[presentation](docs/PRESENTATION.md), and [persistence](docs/PERSISTENCE.md).
[PLAN.md](PLAN.md) records completed implementation and pending manual checks;
[verification status](docs/VERIFICATION.md) records the passing target-build
offline Plugin Verifier result and API portability warnings.
Marketplace publication is a separate release step.
