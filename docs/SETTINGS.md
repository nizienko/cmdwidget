# Stage 6: settings and explicit command testing

Settings / Preferences → Tools → Cmd Widget is an application configurable in
the frontend module. Definitions are global. Add/Edit opens a modal editor with
Name, Command, a positive whole-second refresh interval, and Enabled. Stable IDs
are retained on edit; Add assigns a UUID. Remove, Enable / Disable, and Move up /
Move down operate on the settings draft. Editor OK only updates that draft;
Settings Apply / OK commits the full validated list atomically. Editor Cancel
discards that edit; Settings Cancel discards unapplied list changes. Apply already
committed before Cancel remains saved, following normal IDE settings behavior.

The page explicitly selects an open project for testing, showing its name and
root to disambiguate multiple windows. Backend context is fetched without running
a command. The editor shows backend host, OS, directory, shell, inherited/login
environment policy, permissions, timeout, and output limits. No open project,
context lookup failure, or invalid backend root disables testing. Close and
reopen the editor to retry a failed context lookup.

Only Test command invokes the backend test RPC, capturing the command text at
click time. Typing, selection, context lookup, and editor OK never test a command.
Testing neither persists the draft nor publishes live widget state. Output is
plain text with separate stdout/stderr, exit code, duration, timeout/errors, and
per-stream truncation flags. The backend executor supplies the same four shared
execution slots, 10-second timeout, 64 KiB per stream, and cleanup policy as live
widgets. Test requests are not automatically replayed after a connection failure,
since commands can have side effects.

The editor owns a child coroutine job of a project service scope. Closing either
the editor or project cancels its test. Cancel test cancels the current request;
generation checks reject already queued results. UI callbacks run on the EDT and
check editor/project lifetime. Backend cleanup may finish after frontend closure
or cancellation; it remains bounded by the executor's policy.

Implementation follows the platform's [settings lifecycle](https://plugins.jetbrains.com/docs/intellij/settings-guide.html)
and [DialogWrapper validation and disposal](https://plugins.jetbrains.com/docs/intellij/dialog-wrapper.html).

## Automated verification

```sh
./gradlew :frontend:test :backend:test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

Platform UI tests cover draft isolation, Apply, reset, Cancel, removal, ordering,
enable/disable, stable edited IDs, field validation, unavailable context, typing
without execution, explicit unsaved testing, unchanged persistence, and diagnostic
output. Session tests cover context-only requests, explicit commands, editor and
project cancellation, and rejection of queued responses after cancellation.
Backend RPC tests separately verify that testing does not publish live state.

All 48 tests (27 backend and 21 frontend), plugin assembly, searchable-options
generation, and configuration/structure checks pass on macOS on 2026-10-01.
Closing the project while its editor remains open disables testing and clears
the running state. [Stage 7 verification](VERIFICATION.md) records the separate
compatibility check and remaining acceptance gates.

## Manual ordinary/split-mode acceptance

Use disposable sandbox projects. Repeat in ordinary mode (`./gradlew runIde`)
and split mode (`./gradlew runIdeSplitMode`).

1. Open Tools → Cmd Widget. Add Disk with
   `df -h / | awk 'NR==2 {print $4}'`, interval 10. Inspect the backend context,
   explicitly test it, and confirm stdout/exit/duration. Click editor OK, then
   Settings Cancel: no new widget should appear.
2. Add Disk again and Git with `git branch --show-current`, interval 5. Apply;
   confirm separate widgets appear without an IDE restart. Open a second repository
   and confirm Git is project-specific. Test an unsaved changed command; the live
   value must remain driven by the saved definition.
3. Rename, reorder, enable/disable, and remove individual definitions. Apply and
   confirm the other widgets retain results. Edit, then Cancel and reopen settings:
   unapplied changes should disappear. A prior Apply must remain committed.
4. Try blank Name/Command, names above 40 characters, and intervals 0, fractional,
   negative, or above the Int range. Editor OK should show a validation error.
5. Test `printf stdout; printf stderr >&2; exit 7`, then `sleep 30`. Inspect the
   nonzero result and timeout. Cancel a running test and close the editor during
   another test; check process cleanup and continued live widget refresh.
6. Test output above 64 KiB for each stream and confirm truncation indicators.
7. In a split session confirm the displayed host/directory/shell belong to the
   backend. Reconnect and confirm saved definitions and results are restored with
   no duplicate schedulers. Repeat with saved frontend settings after restart.

Visual acceptance remains pending: computer control returned
`Computer Use permissions are not granted` on 2026-10-01. Automated checks and
searchable-options generation do not establish layout or cross-process acceptance.
