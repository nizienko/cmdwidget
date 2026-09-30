# MVP verification status — 2026-10-01

Stage 6 implementation is complete. Stage 7 remains open until the manual and
cross-process checks below are accepted. No Marketplace publication was performed.

## Automated checks

```sh
./gradlew :frontend:test :backend:test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --offline
```

The final run passed all requested tasks. There are 48 passing tests: 27 backend
and 21 frontend. Unchanged backend results were reused from the successful stage-5
run; frontend tests reran for the settings/test implementation. Searchable-options
generation finds `com.github.nizienko.cmdwidget.settings` with its Add/Edit/Remove,
enable/disable, ordering, and project-selector controls. This confirms settings
instantiation in a headless IDE, not visual layout acceptance.

The final ZIP is `build/distributions/cmdwidget-1.0.0-SNAPSHOT.zip`. It contains
the root plugin JAR, searchable options, and backend/frontend/shared content
module JARs. Structure/configuration checks passed. The metadata describes the
settings page and macOS/Linux execution support.

## Target-build bytecode compatibility

The cached JetBrains Plugin Verifier 1.410 checked the final ZIP against the local
IntelliJ IDEA 2026.1.5 distribution (IU-261.27258.48):

```sh
java -Dplugin.verifier.home.dir="$PWD/build/pluginVerifier-offline-cache" \
  -jar /path/to/verifier-cli-1.410-all.jar \
  check-plugin build/distributions/cmdwidget-1.0.0-SNAPSHOT.zip \
  .intellijPlatform/ides/IU-2026.1.5 -offline \
  -verification-reports-dir build/reports/pluginVerifier-offline
```

Verdict: **Compatible**, with 5 deprecated, 13 experimental, and 2 internal API
usages. Experimental usages are in generated RPC code. Deprecated usages are
status-bar methods; internal usages are `StatusBar.removeWidget` in reconciliation
and disposal. They are part of the existing dynamic widget integration and remain
a portability risk. No new settings API problems were reported. The dynamic
plugin check says the plugin can probably be enabled/disabled without restart;
actual unload acceptance is still required.

Report: `build/reports/pluginVerifier-offline/IU-261.27258.48/report.html`.
The IDE's product layout metadata contains nonexistent paths and produced
warnings. The verifier's online attempt failed fetching API-change data and
resolving an optional Java plugin dependency (`intellij.platform.frontend.split`)
because network/DNS was unavailable. Offline verification resolved the plugin's
direct dependencies from the target IDE and returned Compatible, but does not
establish optional-plugin combinations, cross-process runtime behavior, or
compatibility with other IDE builds.

## Remaining acceptance gates

1. Settings/editor layout and creating, testing, applying, and cancelling widgets
   in ordinary and split mode. Follow [SETTINGS.md](SETTINGS.md).
2. Two repository windows showing independent results, followed by an actual IDE
   restart restoring ordered definitions and enabled flags.
3. Saved frontend definitions arriving on the initial split connection without
   manual Apply; reconnect restoring state without duplicate execution.
4. Failure after success, timeout, truncation, edits/disable/removal during
   execution, missing root, project closure, and unload in actual IDE sessions.
5. Linux runtime/pipeline-cleanup suite. The Docker daemon is unavailable; the
   stage-2 Linux executor suite passed previously, but later runtime checks have
   not been verified on Linux. Follow [RUNTIME.md](RUNTIME.md) and the existing
   `scripts/test-backend-linux.sh`.

Computer control returned `Computer Use permissions are not granted` when
accessing IntelliJ on 2026-10-01. Automated fixtures and previous startup logs
do not replace these manual gates; the MVP is not marked fully accepted.
