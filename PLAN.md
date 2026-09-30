# Cmd Widget implementation plan

This plan implements the MVP defined in [IDEA.md](IDEA.md). The repository already contains `frontend`, `backend`, and `shared` modules with split mode enabled. Extend that scaffold and keep the plugin runnable after each stage. These stages are planned work, not completed functionality.

## Implementation status (2026-10-01)

Stage 1 is accepted: the user confirmed that visual checks passed. The initial
scaffold builds against IntelliJ IDEA 2026.1.5.
The frontend now has a registered factory-owned lifecycle anchor and two independent,
dynamically added text widgets with stable IDs. A manual prototype action updates,
removes, and restores Disk without replacing Git. An ordinary-mode run configuration
has been added alongside the existing split-mode configurations.

Ordinary IDE startup and split-mode frontend/backend startup have been exercised;
logs confirm plugin loading on both sides and a successful split-mode connection.
Platform integration tests exercise real status bars for independent updates,
removal/re-addition, disposal, and duplicate prevention. All five tests, plugin
assembly, project-configuration verification, and plugin-structure verification
pass. RPC is configured by the
scaffold but no RPC contract or implementation exists yet, as expected at this stage.

Visual checks were performed by the user because computer-control permissions were
unavailable to automation. See [the lifecycle checklist](docs/LIFECYCLE.md).

Stage 2 is implemented. Shared serializable context/result DTOs, backend context
resolution, an application-scoped executor, four cancellable execution slots,
10-second timeouts, concurrent bounded output capture, and bounded descendant
cleanup are in place. A project-scoped startup probe executes only `pwd` and logs
the backend context; frontend values remain the stage-1 hardcoded prototype until
the RPC/presentation stage. All 13 process integration tests passed on macOS and
Linux (Java 21 container), including pipeline timeout/cancellation, forced cleanup,
service cancellation, and cancellation of the fifth queued request. The IDE startup
probe also executed successfully through the backend shell in the project root.
See [execution verification](docs/EXECUTION.md) for reproducible checks.

Stage 3 is implemented. Shared configuration and versioned transient-state DTOs
now feed a project-scoped runtime. Enabled definitions run immediately with fixed
delay after completion; rename/order edits preserve attempts, while command,
interval, and enabled edits cancel and await cleanup before replacement. Removed
entries are invalidated before cancellation, and obsolete results are rejected.
Project/plugin service-scope cancellation stops runtime jobs and executor requests.
Eight runtime tests cover scheduling, independent projects/widgets, reconciliation,
validation, stale-result rejection, and real-process closure with a fifth queued
widget. All 21 backend tests, five frontend tests, plugin assembly, and
configuration/structure checks pass on macOS. The Linux script includes the new
suite, but stage-3 Linux verification is pending because no Docker daemon is
currently available. Definitions are not yet persisted or connected to the UI;
the startup probe and hardcoded frontend values remain unchanged.
See [runtime verification](docs/RUNTIME.md).

Stage 4 is implemented. A generated RPC contract and registered backend provider
expose current project state, backend context, and explicit command testing.
Factory-owned frontend subscriptions reconnect through the platform's durable RPC
wrapper and only observe the existing runtime. EDT presentation rejects obsolete
snapshot versions, old connection sessions, and late responses after disconnect.
Text normalization strips terminal sequences/controls, collapses whitespace, and
bounds values to 80 Unicode characters. Pending, empty, error, refreshing, stale,
and disconnected states have bounded HTML-escaped diagnostic tooltips.
Git and Disk now use temporary live backend demo definitions; the old manual
prototype menu action has been removed. Persistence and editable settings remain
the next stages. All 35 tests, assembly, and configuration/structure checks pass
on macOS. Ordinary and split-mode startup logs confirm frontend receipt of backend results.
The split client subsequently disconnected with the platform's
`Unsupported Connection Code: CLIENT_DISCONNECTED` error; visual reconnect
acceptance remains pending, while state restoration/rejection are covered by tests.
Visual acceptance remains manual because computer-control access to IntelliJ was
denied. See [RPC/presentation verification](docs/PRESENTATION.md).

Stage 5 is implemented. An application-level persistent settings service stores
only ordered definitions, stable IDs, and enabled flags. The shared descriptor
registers settings synchronization with `InitialFromFrontend` and declares
`applicationSettings` for initial delivery without a manual edit. Each backend
project has one service-scope subscription that reconciles effective definitions
by ID; RPC reconnection never owns scheduling. Equal Apply/load states do not
restart work. Missing fields have defaults, absent state resets to an empty list,
invalid loaded entries are excluded, and duplicate IDs keep the first valid entry.
Invalid Apply is rejected atomically. The temporary backend Git/Disk startup demo
is removed; new installations start with an empty list until definitions are supplied.
The settings editor remains stage 6.

Four persistence tests cover XML round trips, stable generated IDs, defaults,
invalid/duplicate entries, validation, repeated Apply/load, and absent state.
Platform tests exercise settings delivery through RPC and two real project
services with independent directory results and preserved attempts on rename.
All 40 tests, plugin assembly, and project-configuration/structure checks pass on
macOS on 2026-10-01.
Actual IDE restart and cross-process initial settings synchronization remain
manual acceptance checks; XML and platform tests do not establish those outcomes.
See [persistence verification](docs/PERSISTENCE.md).

Stage 6 is implemented. Settings / Preferences → Tools → Cmd Widget provides
global draft Add/Edit/Remove, Enable / Disable, and ordering actions. The editor
validates Name, Command, and a positive whole-second interval, retaining stable
IDs. Editor OK changes only the draft; Settings Apply / OK commits it atomically,
while Cancel discards unapplied changes. An explicit project selector chooses
the backend context for command tests, including in multi-project sessions.
The editor shows backend host, root, shell, environment policy, and permissions.
Test command runs unsaved text only on a button click through the existing RPC
executor; it displays both streams, exit code, duration, errors, and truncation.
No usable context disables testing. Editor/project closure and Cancel test cancel
requests, and disposed/obsolete callbacks cannot overwrite the UI.

Five platform settings/editor tests and three test-session tests were added.
All 48 tests (27 backend, 21 frontend), assembly, searchable-options generation,
and project-configuration/structure checks pass on macOS. Visual settings and
cross-process acceptance remain pending: computer control again returned
`Computer Use permissions are not granted` on 2026-10-01.
See [settings verification](docs/SETTINGS.md).

Stage 7 is in progress. Scaffold README content has been replaced with installation,
usage, execution-host support, fixed-delay and shell/environment policy,
backend-host behavior, troubleshooting, and known limitations. A distributable
is built at `build/distributions/cmdwidget-1.0.0-SNAPSHOT.zip`.
Manual end-to-end acceptance, real restart/initial split-mode synchronization,
visual reconnect, and Linux runtime checks remain outstanding. Docker daemon is
still unavailable. Plugin Verifier 1.410 in offline mode reports Compatible
against local IU-261.27258.48, with 5 deprecated, 13 experimental (generated RPC),
and 2 internal API usages (dynamic status-bar removal). Its online attempt failed
resolving the optional Java plugin's `intellij.platform.frontend.split` dependency
because network/DNS access was unavailable. The offline report is a target-build
bytecode check, not full split-mode or cross-version acceptance; the verifier also
warns about nonexistent paths in the IDE's layout metadata.
See [verification and remaining gates](docs/VERIFICATION.md). Marketplace
publication remains outside this work.

## 1. Verify the scaffold and dynamic widgets

- Inspect module descriptors, target platform, RPC setup, and run configurations.
- Build and launch the existing plugin in ordinary IDE mode and split mode.
- Add two hardcoded, independent text widgets with stable IDs.
- Prototype dynamic addition, update, removal, project disposal, and plugin unload using supported platform APIs.
- Check lifecycle and placement with two project windows.

Acceptance:

- Two widgets appear in each project window in both modes.
- Updating or removing one leaves the other intact.
- Closing and reopening a project does not duplicate widgets.

Resolve the dynamic lifecycle integration before proceeding. A single combined widget changes the requirement and must be an explicit product decision.

## 2. Implement command execution and cleanup

- Add shared execution-context and command-result DTOs.
- Resolve the backend shell and project root according to IDEA.md.
- Execute a hardcoded command outside the EDT, using an argument list and closed stdin.
- Drain stdout/stderr concurrently, capturing at most 64 KiB per stream.
- Enforce a 10-second timeout including shell startup.
- Implement cancellable execution and bounded cleanup of the process and descendants.
- Limit active executions to four per backend process with cancellable queued requests.
- Return errors, exit codes, duration, completion time, and truncation flags.

Acceptance:

- Commands run in the expected directory while the UI remains responsive.
- Success, nonzero exit, startup failure, empty stdout, and stderr behave as specified.
- Large simultaneous stdout/stderr does not hang or grow captured data without bound.
- Timeout and cancellation clean up a shell pipeline with child processes on macOS and Linux.
- A fifth request queues; cancelling it prevents process startup.

## 3. Add configuration and independent project runtimes

- Introduce widget configuration with stable IDs and separate transient state.
- Run enabled widgets immediately, then refresh with fixed delay after completion.
- Allow at most one queued or running attempt per project/widget pair.
- Track revisions and discard obsolete results.
- Reconcile rename, command/interval edits, enable, disable, and removal by ID.
- Cancel jobs and processes on project closure and plugin unload.
- Keep results separate for projects sharing the same definitions.

Acceptance:

- Widgets with different intervals update independently without overlapping attempts or backlogs.
- Rename does not restart execution.
- Editing a running command cannot publish its old result into the new configuration.
- Disable, removal, closure, and unload stop owned work.
- Two repositories can report different branches for one shared definition.

## 4. Connect backend state to frontend presentation

- Define RPC contracts for observing project state and explicitly testing commands.
- Retrieve current state on connection and release subscriptions on disposal.
- Normalize text, remove terminal control sequences, and bound display length.
- Implement pending, empty, success, and error/stale states.
- Preserve the last successful value during refresh and mark it after failure.
- Add bounded diagnostic tooltips with timestamps, host, directory, and shell.
- Update UI on the EDT and reject obsolete state responses.
- Reconnect to existing backend runtime without creating a second scheduler.

Acceptance:

- Ordinary and split-mode widgets display backend results correctly.
- Multiline, ANSI-colored, empty, and long output render predictably.
- A successful `VPN: connected` followed by failure is visibly stale.
- Reconnect restores current state without duplicate execution.

## 5. Persist and synchronize global definitions

- Add application-level persistent configuration with stable IDs and order.
- Register split-mode synchronization metadata and initial direction as defined in IDEA.md.
- Deliver effective definitions to each backend project runtime.
- Reconcile changes by ID rather than rebuilding all widgets.
- Supply defaults for missing fields and validate loaded definitions.
- Keep transient execution state out of persistence.

Acceptance:

- IDE restart preserves definitions, ordering, and enabled flags.
- Another project receives the same definitions with independent runtime state.
- Initial split-mode connection receives saved definitions without a manual settings edit.
- Reconnect and Apply do not duplicate definitions or schedulers.
- Missing defaulted fields load predictably; invalid entries neither crash the plugin nor execute invalid commands.

## 6. Build settings and explicit command testing

- Register Settings / Preferences → Tools → Cmd Widget.
- Implement Add, Edit, Remove, and Enable / Disable actions.
- Edit Name, Command, Refresh interval, and Enabled; omit the single-choice presentation selector.
- Validate nonblank names/commands, name length, and positive whole-second intervals.
- Keep draft settings until Apply / OK; discard them on Cancel.
- Show host, root directory, shell, environment policy, and execution permissions.
- Test unsaved commands explicitly through the same backend executor.
- Display stdout, stderr, exit code, duration, and truncation indicators.
- Cancel tests when the editor closes and disable testing without usable project context.

Acceptance:

- A user creates, tests, and applies a widget without restarting the IDE.
- Typing and selecting commands do not execute them.
- Testing does not save definitions or change live widget values.
- Apply updates open projects; Cancel leaves saved definitions intact.
- Removing or disabling one widget leaves other executions intact.
- Validation errors are visible before saving.

## 7. Verify and prepare the MVP

Use focused automated checks for normalization, validation, scheduling, state transitions, and obsolete-result rejection. Use process integration checks for timeout/cancellation and platform checks for widget lifecycle. Avoid tests that merely mirror implementation details.

Manual end-to-end checks in ordinary mode and split mode:

1. Create Disk and project-dependent Git widgets, test, and apply.
2. Open two repositories and confirm separate results.
3. Restart the IDE and confirm configuration restoration.
4. Exercise failure after success, timeout, output truncation, and missing root context.
5. Edit, disable, and remove widgets during execution.
6. Close a project, reconnect the frontend, and unload the plugin where supported.
7. Verify process cleanup and absence of duplicate jobs or widgets.

Replace scaffold documentation with installation, usage, supported execution-host OSs, fixed-delay semantics, shell/environment policy, backend-host behavior, and troubleshooting. Build the distributable plugin and run applicable platform compatibility checks.

Acceptance:

- All MVP success criteria in IDEA.md are met.
- A distributable plugin is available with documented limitations.
- No known failure blocks the UI, conceals stale state, or leaks owned processes.
- Marketplace publication remains a separate release step after artifact review.

## Deferred work

Additional presentations, Windows execution, presets, import/export, project-specific definitions, custom directory/environment, manual refresh, and cross-project deduplication are outside this plan. Add them after the execution and text-widget workflow is stable.
