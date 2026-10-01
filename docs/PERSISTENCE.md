# Stage 5: global definitions and split-mode synchronization

`CmdWidgetSettingsService` is an application-level persistent component in the
shared module, available on both sides. Its XML state contains only definition
fields, stable IDs, enabled flags, and list order. The frontend will edit this
service through the settings page in stage 6. Backend projects only subscribe
to its effective definitions; RPC observation does not create subscriptions or
schedulers. Each project has one service-owned subscription whose lifetime is
the project's coroutine scope.

The shared descriptor registers both `rdct.remoteSettingProvider`, with
`InitialFromFrontend`, and `applicationSettings`. The latter declaration enables
initial synchronization without a manual edit. This follows the
[platform synchronization documentation](https://plugins.jetbrains.com/docs/intellij/persistent-state-in-split-mode.html).
Backend receipt calls `loadState`, publishing the same effective flow that
ordinary-mode Apply uses. Repeated equal states do not emit or restart execution.
The existing runtime reconciles by ID, preserving results and attempts on
rename/order edits and cancelling changed execution definitions before replacement.

When no saved state exists, installations receive three enabled starter widgets:
Time (`date '+%H:%M'`, 10 seconds), Project (`basename "$PWD"`, 60 seconds), and
Disk usage (`df -P . | awk 'NR == 2 {print $5}'`, 60 seconds). They use standard
macOS/Linux utilities. Windows installations seed the same widget names and
intervals using Windows PowerShell, with explicit UTF-8 output. All execute on
the backend in the default directory. Defaults follow the settings host OS;
in mixed-OS split mode, edit commands for the execution host.
Starter IDs are deterministic; frontend settings replace backend defaults during synchronization.
Loading saved state replaces the starter list; an explicitly saved empty list
remains empty after restart.
The saved `initialized` flag keeps the XML state non-default even with no widgets,
so removing all starters does not turn the next launch into a first installation.
Missing interval, enabled, and presentation fields default to 10 seconds, true,
and TEXT. Missing IDs receive UUIDs which are
then saved; explicit blank IDs are invalid. Invalid names, commands, intervals,
and unknown presentations are excluded. The first valid entry for each ID wins.
Invalid entries are omitted from subsequent saved state. Apply rejects an invalid
list atomically. Returned XML beans are copies and cannot mutate effective state.

## Automated verification

```sh
./gradlew :backend:test :frontend:test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

Settings tests exercise XML serialization/restoration, stable generated IDs,
defaulted fields, invalid entries, duplicate IDs, atomic validation, repeated
Apply/load, first-install defaults, saved empty lists, and synchronization direction.
Executor tests run the starter commands in a directory with spaces. Platform RPC tests
exercise saved configuration delivery, updates, result preservation on rename,
removal, and independent results in two actual projects. Existing runtime tests
cover cancellation, revisions, independent scheduling, and disposal.

All 40 tests (27 backend and 13 frontend), plugin assembly, and configuration/
structure checks passed on macOS on 2026-10-01.

These tests do not establish actual cross-process initial synchronization or
disk restoration after an IDE restart. Those acceptance checks remain pending.

## Manual acceptance before the settings editor exists

With the sandbox IDE stopped, place this state in its configuration directory's
`options/cmd-widget.xml`. In split mode use the frontend configuration directory;
keep the backend state empty or different to check the initial direction.
Use only disposable sandbox configuration, not your main IDE configuration.

```xml
<application>
  <component name="CmdWidgetSettings">
    <option name="widgets">
      <list>
        <Definition>
          <option name="id" value="acceptance-root" />
          <option name="name" value="Root" />
          <option name="command" value="pwd" />
          <option name="refreshIntervalSeconds" value="60" />
        </Definition>
      </list>
    </option>
  </component>
</application>
```

1. Launch ordinary mode, open two project directories, and confirm each displays
   its own Root result. Restart the sandbox and confirm the definition returns.
2. Launch split mode with saved frontend state. Confirm Root appears without a
   settings edit, with the backend directory in the value and tooltip.
3. Disconnect/reconnect and confirm one widget and one runtime per project.
4. After stage 6, edit order, names, commands, intervals, and enabled flags through
   Apply. Confirm updates reach both projects and Cancel leaves saved state intact.

The stage-4 visual reconnect acceptance and Linux runtime verification also remain
pending as recorded in PLAN.md.
