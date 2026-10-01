# Stage 4: backend RPC and live text widgets

This documents the stage-4 milestone. Stage 5 removed the temporary startup demo
and supplies persisted definitions instead; see [persistence checks](PERSISTENCE.md).
The RPC and presentation behavior below still applies.

`CmdWidgetRpcApi` exposes a project state flow, backend execution context, and an
explicit `testCommand` request. Its backend provider resolves the platform
`ProjectId` and delegates to the existing project service. Observation sends the
current state immediately and never reconciles definitions or starts schedulers.
Explicit tests use the application executor and belong to both the caller and
project service lifetime; their results do not change live widget state.

Each frontend factory-owned host subscribes in its coroutine scope. The platform's
`durable` wrapper retries RPC connection failures. A normally completed stream also
reconnects with a one-second delay. Each subscription attempt has a frontend
session number. The host applies snapshots on the EDT, ignores older versions
within a session, and ignores older or disconnected sessions. A new session can
restore the same backend state or accept a restarted backend's lower version.
Disposing the host cancels its subscription and rejects queued UI updates.

Each enabled definition keeps its own status bar widget. Changes update label and
tooltip in place; disabled or removed definitions remove only their widget.
The label shows only the command value, without a widget-name prefix. Hovering
shows the widget name on the first line and the latest command stdout on the
second, normalized to one line (or an empty/pending placeholder). Clicking the widget hides the tooltip and
opens a balloon above the status bar, with the name, command preview (up to 120
Unicode code points), execution target, refresh interval, status, and available
host/directory/shell and exit/duration information. Its settings icon opens Cmd
Widget settings. Clicking outside dismisses the balloon.
The latest successful stdout remains visible during refresh. A failed latest
attempt or a disconnected frontend adds `[stale]` to retained successful values.
Without a successful result, states show `…`, `(error)`, or `(disconnected)`.
Successful empty normalized output displays `(empty)`.

The formatter removes CSI, OSC, DCS, SOS, PM, and APC sequences, their C1 forms,
and other non-display control characters. It collapses whitespace to one line and
bounds the value to 80 Unicode code points, including an ellipsis. Tooltip names
use the same normalization and display as plain text. The interactive platform
tooltip is suppressed while the details balloon is open. Output updates and
renaming refresh the tooltip, and widget disposal releases its listeners
and closes the balloon. Each click opens details from the latest received state.

## Temporary runnable demo

Backend project startup supplies two definitions once per project:

- Git: `git branch --show-current`, five-second fixed delay.
- Disk: `df -h . | awk 'NR==2 {print $4}'`, ten-second fixed delay.

Commands execute in the backend project root. Disk measures the filesystem holding
that root; Git may return empty stdout for a detached HEAD or a failure outside a
repository. These definitions replace the earlier one-shot `pwd` probe. The
prototype menu action is removed. User settings and persistence come next.

## Automated verification

```sh
./gradlew :backend:test :frontend:test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

All 35 tests passed on macOS on 2026-09-30: 13 executor tests, eight runtime tests,
one platform RPC test, eight platform widget lifecycle/state-delivery tests, and
five formatter tests. Plugin assembly and configuration/structure checks passed.
The backend module's test sandbox omits the assembled root plugin descriptor, so
its RPC fixture registers the real provider in the extension point. It resolves
the generated descriptor through the real platform registry and checks initial
state, live execution, re-observation, and test-command isolation. This ordinary
RPC test is supplemented by sandbox launches for actual plugin registration.

An ordinary-mode sandbox log confirms successful backend execution and
`Cmd Widget frontend received backend results: session=1, version=4, widgets=2`.
An actual split-mode client also received the serialized state/results, logging
`session=1, version=5, widgets=2`; both processes loaded the plugin. The client
subsequently disconnected and logged the platform error
`Unsupported Connection Code: CLIENT_DISCONNECTED`. This run confirms cross-process
delivery, but does not establish visual reconnection acceptance. Session/version
restoration and obsolete-response rejection are covered by the platform widget
tests; a full visual reconnect check remains pending.
Computer Use access to IntelliJ was denied, so visual acceptance remains manual.
Linux process/runtime verification remains pending until a Docker daemon is
available; `scripts/test-backend-linux.sh` runs those two suites without IDE UI.

## Manual acceptance

1. Run `./gradlew runIde`. Open a repository and confirm separate live Git and
   Disk widgets. Hover each for its name, click the widget for its details, and
   click the settings icon in the balloon to open Cmd Widget settings.
2. Open another repository in a separate window and confirm independent branch
   values. Close and reopen one project; check that widgets are not duplicated.
3. Open a directory without a Git repository. Git should show `(error)` and explain
   the nonzero exit when testing the command in settings; Disk should continue refreshing.
4. Once editable definitions arrive, exercise multiline/color output, empty
   stdout, long output, timeout, and a success followed by failure. Formatter and
   state-delivery tests already cover these presentation transitions.
5. Run `./gradlew runIdeSplitMode` (or the existing compound run configuration).
   Verify the same live values and backend context, then disconnect and reconnect
   the frontend. Retained values should be stale while disconnected and restore
   without duplicate widgets or backend schedulers.
6. Close the project and unload/reload the plugin where supported. Confirm widget
   disposal and cancellation of owned backend work.

The INFO log above reports only session/version/widget count, never command output.
For split mode, check the backend's `log_runIdeBackend/idea.log` and the client's
log for successful loading and frontend receipt of results.
