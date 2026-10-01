# Stage 1: independent status bar widget prototype

Release 1.0 supersedes the historical prototype below: the maintainer approved
one factory-owned `CmdWidget.Host` with separate Swing command elements. Elements
retain their own values, tooltips, popups, and progress bars; reconciliation
preserves instances across updates and reordering. The host disposes removed
elements and clears its children on EDT during shutdown. Production code no
longer adds or removes sibling platform widgets through internal StatusBar APIs.
Current lifecycle tests cover single platform registration, ordering/layout,
empty-container visibility, element disposal, and late delivery after shutdown.

This is the accepted historical lifecycle milestone. The prototype menu action has
since been removed, and stage 4 displays live backend values. Use
[the current presentation checklist](PRESENTATION.md) for the latest build.

This is a lifecycle prototype, not the command-execution MVP. `Git: main` and
`Disk: 126G` are hardcoded. No shell commands, schedules, persistence, or RPC calls
were part of this stage. Backend execution is now implemented separately in stage 2;
the visible values remain hardcoded until frontend RPC integration.

## Implementation

The frontend registers `CmdWidgetFactory` as `com.intellij.statusBarWidgetFactory`.
Its invisible `CmdWidget.Host` anchor owns a separate widget per definition through
the public `StatusBar.addWidget(widget, anchor)` API and explicit
`Disposer.register(host, widget)` ownership. The two
visible widgets are `CmdWidget.prototype-git` and `CmdWidget.prototype-disk`.
All addition, reconciliation, and removal happens on the EDT. Values are never
combined into the factory-owned anchor.

Factory disposal removes the owned visible widgets. Each widget is also registered
under the anchor's disposable. Deferred installation checks disposal; deferred
cleanup compares widget identity so it cannot remove a replacement instance.
The platform's `addWidget` overload accepting a parent disposable queues removal
by ID during off-EDT disposal; a platform integration test exposed a replacement
race with that overload. Explicit ownership avoids its extra removal callback.
The two-argument dynamic `addWidget` API is public but deprecated in 2026.1.5 in
favor of factory registration. The factory remains the lifecycle entry point;
dynamic sibling insertion is still a prototype requiring acceptance, and no
compatibility beyond this target platform is claimed.
This covers project closure and extension/plugin unload. The user subsequently
confirmed the visual acceptance checks passed.

The target is IntelliJ IDEA 2026.1.5, build 261.27258.48. The scaffold already
applies the RPC and Kotlin serialization plugins to all content modules. Actual
cross-process contracts will be added in later stages.

Platform references:

- [Status bar widgets](https://plugins.jetbrains.com/docs/intellij/status-bar-widgets.html)
- [Split mode](https://plugins.jetbrains.com/docs/intellij/split-mode-and-remote-development.html)

## Automated checks

Run `./gradlew :frontend:test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure`.
The integration tests use the target platform's real `IdeStatusBarImpl`:

- Editing Disk retains both widget identities; removing and restoring it retains Git.
- Separate status bars have separate widget instances and independent lifetimes.
- Disposal before deferred installation does not create orphan widgets.
- Reconciliation and reopening do not duplicate widgets.
- Disposing the parent lifetime releases the host and its widgets.
- Off-EDT queued cleanup does not remove replacement widgets.

These checks do not replace visual verification in actual project windows.

## Manual acceptance checklist

1. Run `./gradlew runIde`, or **Run IDE with Plugin (Ordinary)**. This task explicitly
   disables split mode. Use disposable test projects; sandbox IDEs can restore
   previously opened projects from their own configuration.
2. Open two projects in separate windows. Confirm two independently rendered
   widgets, `Git: main` and `Disk: 126G`, in each status bar.
3. In the first window invoke **Tools → Cycle Cmd Widget Prototype** three times.
   Disk should become `Disk: updated`, disappear, then return as `Disk: 126G`.
   Git should remain unchanged throughout; the second window should stay unchanged.
4. Close and reopen the first project. Confirm exactly one widget of each ID and
   both default values, without duplicate displays.
5. Disable/unload the plugin where supported. Confirm both visible widgets
   disappear. Re-enable it and confirm one instance of each per project.
6. Repeat with `./gradlew runIdeSplitMode` or **Run IDE with Plugin (Split Mode)**,
   including frontend reconnection. Confirm correct widget placement and disposal.

On 2026-09-30 the ordinary IDE and split-mode pair were launched successfully.
Logs confirmed custom plugin loading, frontend-only loading of the frontend module,
backend-only loading of the backend module, and a successful thin-client connection.
UI automation reported `Computer Use permissions are not granted`; the user
performed the visual acceptance checks and confirmed that they passed.

All five lifecycle tests, `buildPlugin`, `verifyPluginProjectConfiguration`, and
`verifyPluginStructure` passed after correcting the off-EDT disposal race. The
distributable prototype is `build/distributions/cmdwidget-1.0.0-SNAPSHOT.zip`.

The lifecycle gate is accepted and stage 2 has proceeded.
