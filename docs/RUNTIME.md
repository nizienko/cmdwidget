# Stage 3: independent project runtimes

This documents the runtime milestone. Stage 4 supplies live Git/Disk demo
definitions and frontend RPC observation; see [current checks](PRESENTATION.md).

`CmdWidgetProjectService` owns one `ProjectWidgetRuntime` per backend project.
Consumers observe its versioned state flow; observing or reconnecting does not
start work. The service accepts effective definitions through `reconcile`.
Persistence, settings synchronization, RPC, and frontend rendering are subsequent
stages. The existing one-shot `pwd` startup probe is still separate, and the
frontend still displays the stage-1 hardcoded values.

Configuration carries a stable ID, name, command, positive whole-second interval,
TEXT presentation, and enabled flag. Invalid definitions and duplicate IDs reject
the entire reconciliation before any running configuration changes. State and
command results are transient. Each project resolves its own backend context for
each attempt and uses the shared application executor's four-process budget.

Enabled definitions execute immediately. Each widget has one coroutine, which
awaits its executor request and cleanup before delaying for its configured
interval. Queued time and command duration therefore cannot generate backlogs.
Results from different projects remain separate even for identical definitions.

Rename, reorder, and unchanged definitions preserve their current attempt and
execution revision. Command, interval, or enabled edits replace the runtime entry
and advance its revision. Removed and replaced entries are invalidated first;
their jobs are all cancelled, and cleanup is awaited before replacements start.
The commit/cleanup phase completes even if the reconciliation caller is cancelled.
Results publish only if their originating entry is still current. State snapshot
versions advance for both configuration and execution changes.

Successful refreshes replace the last successful result, including empty stdout.
Failures retain that result alongside the latest failed attempt. Changing a
command clears previous results; interval and enabled edits retain them. Disabled
definitions remain in state with no owned job, so presentation can filter them.
Cancelling the project's injected service scope or unloading the plugin cancels
its runtime and queued/running executor requests. Runtime replacement awaits the
executor's bounded process cleanup.

## Verification

```sh
./gradlew :backend:test :frontend:test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

Eight runtime tests verify fixed delay after a slow completion, independent
refreshes, rename/order preservation, idempotent reconciliation, interval edits,
late-result rejection, disable/remove/project-scope cancellation, separate project
results, preservation of successful values after failure, and validation.
The real-process runtime test starts five widgets, observes four running shells,
closes the runtime, and checks that the shells died and the fifth never started.
The existing 13 executor tests cover descendant cleanup and application-scope
cancellation; five platform tests cover frontend widget lifecycle.

On 2026-09-30 all 26 tests and plugin assembly/configuration/structure checks
passed on macOS. Stage-3 Linux verification is pending: the current Docker endpoint
has no running daemon. With an existing Java 21 image and running Docker runtime,
`bash scripts/test-backend-linux.sh` runs both backend suites. Select a Docker
context using `CMDWIDGET_DOCKER_CONTEXT` if needed.
