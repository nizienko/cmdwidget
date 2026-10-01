# Cmd Widgets

Cmd Widgets displays the output of your shell commands as independent widgets
in the IntelliJ IDEA status bar. Definitions are global; each open project runs
them and keeps its own results. Each widget can execute on the backend or frontend
host, including in split mode. Existing definitions default to backend execution.

Version 1.0 targets IntelliJ Platform builds 261 and newer, without an upper build
limit. The build and bytecode compatibility checks use IntelliJ IDEA 2026.1.5;
later builds have not been verified. Command execution supports macOS, Linux,
and Windows hosts. Native Windows execution tests are provided but have not yet
been run on Windows; see [verification status](docs/VERIFICATION.md).

[Source code](https://github.com/nizienko/cmdwidget) ·
[Report an issue](https://github.com/nizienko/cmdwidget/issues) · [MIT License](LICENSE)

## Install and create a widget

Build with Java 21:

```sh
./gradlew buildPlugin
```

Install `build/distributions/cmdwidget-1.0.zip` through Settings /
Preferences → Plugins → gear menu → Install Plugin from Disk. In remote
development, install the plugin on both frontend and backend.

Fresh installations include three enabled widgets. On macOS/Linux they use
standard utilities, with no additional CLI dependencies:

| Name | Command | Interval |
| --- | --- | --- |
| Time | `date '+%H:%M'` | 10 s |
| Project | `basename "$PWD"` | 60 s |
| Disk usage | `df -P . \| awk 'NR == 2 {print $5}'` | 60 s |

On Windows the same widgets use built-in Windows PowerShell (`powershell.exe`),
with profiles disabled and UTF-8 output: `Get-Date -Format 'HH:mm'`,
`Split-Path -Leaf (Get-Location).Path`, and the percentage of used space from
`Get-PSDrive`. Defaults follow the OS where settings are first created. In remote
development with different frontend/backend OSs, edit commands for the selected
execution host; saved command strings are synchronized verbatim.

They run on the backend in each project's root directory. Disk usage displays
as a percentage progress bar. Edit, disable, or remove them in settings.
Existing saved settings, including an empty list, are preserved.

Open Settings / Preferences → Tools → Cmd Widget. Click Add, enter Name, Command,
a positive whole-second Refresh interval, Enabled, and Run command on (BACKEND or
FRONTEND), and an optional Working directory. Choose the open project for command tests on the settings page. The editor displays the selected host,
resolved directory, and shell. Test command explicitly executes the current unsaved
command in the unsaved directory and shows stdout, stderr, exit code, duration, errors, and truncation.
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

These additional examples are not installed automatically.
See the [useful commands reference](docs/COMMANDS.md) for more commands grouped by
category, with descriptions and example output.
Global definitions, stable IDs, order, and enabled flags are saved in the IDE's
`options/cmd-widget.xml`. Output and execution state are not persisted.

## Execution and refresh

With an empty Working directory, backend commands execute in the backend project's
root directory. Frontend commands use
a usable local project root, falling back to the local user's home directory
when the project root is unavailable locally (for example in remote development).
Relative paths resolve against the project root on the selected host; absolute
paths refer to that host's filesystem and do not require a usable project root.
Explicit paths must exist and be readable and searchable directories. An invalid
explicit path reports an error without falling back to another directory.
Paths are literal: shell variables and `~` are not expanded. Spaces need no quotes.
The editor displays the actual directory before testing. Changing the saved
directory restarts the widget and clears results from its previous directory.
Disk space, CLI configuration, environment, and permissions belong
to the selected host. Two projects can therefore show different Git branches for
one global definition.

On macOS/Linux the selected host uses an absolute executable `SHELL` from its
environment, falling back to `/bin/sh`, and launches a non-interactive login shell
with `-lc`. On Windows it uses an absolute executable `ComSpec`, falling back to
`SystemRoot\System32\cmd.exe` (normally `C:\Windows\System32\cmd.exe`), with
`/d /s /a /c`. AutoRun is disabled. Each Windows command starts with
`chcp 65001 >nul &&` to select UTF-8, and capture is decoded as UTF-8. Programs that
force another encoding must be configured to emit UTF-8. For PowerShell commands,
invoke `powershell.exe -NoProfile -NonInteractive -Command "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(); ..."`
explicitly. Shell selection is automatic; there is no shell setting.
The process inherits that host's environment; Unix shell startup files may alter
it. This need not match an interactive IDE terminal. Use absolute executable paths or explicit
environment assignments when needed. Stdin is closed. Commands run with the
selected host user's permissions and can have side effects.

Enabled widgets run immediately, then wait the configured interval after each
attempt finishes. A three-second command with a ten-second interval starts about
every thirteen seconds. Each widget has at most one queued/running attempt.
Four executions can run per IDE process, including tests; others queue.
Each execution has a ten-second timeout including shell startup. Both streams
are drained concurrently, capturing at most 64 KiB each with explicit truncation.
Cancellation and timeout perform bounded descendant-process cleanup.

Rename and ordering edits preserve execution state. Command/interval/host changes
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
Frontend widgets keep updating when the backend disconnects.
The status bar shows only the command result, without a widget-name prefix.
Hover a widget to see its name on the first line and its latest output on the second.
Click the widget to open a balloon with its name,
shortened command preview, refresh interval, execution target, and execution status/context.
The settings icon in the balloon opens Cmd Widget settings.

## Troubleshooting and limitations

- No widgets: enable a definition or create and Apply one, and make sure the status
  bar is visible. Removed starter widgets are not added again to saved settings.
- Command not found or wrong environment: inspect the backend host/shell and use
  an absolute path. Login-shell setup can differ from the IDE terminal.
- Test command disabled: open/select a project with a usable backend root. After
  a failed backend context lookup, close and reopen the editor to retry.
- Stale/error value: hover for the last result and context. Test the command
  explicitly in that project; check exit status, stderr, timeout, and permissions.
- Split-mode definitions missing: ensure the plugin is installed on both sides.
  The frontend owns settings and sends them to the backend.
- Timeout/truncation: keep commands short and non-interactive. These limits are
  fixed in the MVP.
- Processes use best-effort bounded descendant cleanup; commands deliberately
  detaching children are outside the verified pipeline-cleanup scenarios.

Output matching a complete percentage (`42%`, `42.5%`, or `42,5 %`) in the range
0–100 displays a progress bar with its value. Other output keeps the text
presentation. The widget switches automatically as output changes.

Project-specific definitions, custom environment, a preset chooser, import/export, manual
refresh, and cross-project deduplication are deferred. System commands execute
once per project.

## Development and verification

The plugin has three modules: `shared` (DTOs, persistent definitions, RPC contract,
process execution, context resolution, scheduling), `backend` (backend runtime and
RPC provider), and `frontend` (local runtime, settings, explicit test adapter,
status bar lifecycle/presentation).

The frontend registers one factory-owned status bar widget containing separate
command elements. Each element keeps its own value, tooltip, click popup, and
progress presentation. Reordering preserves retained elements; disabling or
removing one releases only that element. No dynamic status-bar registration or
removal APIs are used.

```sh
./gradlew :backend:test :frontend:test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
./gradlew runIde
./gradlew runIdeSplitMode
```

On Windows use `gradlew.bat` instead of `./gradlew`. The backend test task includes
native Windows cases for Unicode, quotes and paths with spaces, starter commands,
timeout, and cancellation; these cases are skipped on macOS/Linux.

GitHub Actions [CI](.github/workflows/ci.yml) runs the same tests and plugin checks
on Windows, Linux, and macOS for pushes and pull requests. It can also be started
manually from Actions → CI → Run workflow. Each OS uploads HTML/XML test reports
(including on failure) and a plugin ZIP after successful checks; artifacts are
kept for 14 days. Download `test-reports-windows-latest` to inspect native Windows
test results, or `cmdwidget-windows-latest` to install the built plugin for manual
Windows IDE acceptance.

Run configurations provide ordinary and split-mode launches. Automated suites
cover execution limits/cleanup, runtime reconciliation, persistence, RPC,
presentation, widget lifecycle, and settings/test isolation.

The acceptance procedures are in [settings and testing](docs/SETTINGS.md),
[execution](docs/EXECUTION.md), [runtime](docs/RUNTIME.md),
[presentation](docs/PRESENTATION.md), and [persistence](docs/PERSISTENCE.md).
[PLAN.md](PLAN.md) records implementation history;
[verification status](docs/VERIFICATION.md) records automated checks and release
acceptance. The maintainer reports that most manual scenarios passed; remaining
scenarios will be followed up through user feedback. Report problems through
[GitHub Issues](https://github.com/nizienko/cmdwidget/issues), including the IDE
version, execution host OS, and ordinary or remote development mode.
Marketplace publication is a separate release step.
