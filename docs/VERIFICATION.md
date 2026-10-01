# MVP verification status — 2026-10-01

Version 1.0 is prepared for release. The maintainer reports that most manual
scenarios passed and accepts collecting feedback for remaining scenarios rather
than blocking release. Individual manual scenarios were not identified, so the
checklist below does not imply that every item passed. No Marketplace publication
was performed.

## Automated checks

```sh
./gradlew :frontend:test :backend:test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --offline
```

The release checks passed all requested tasks. There are 64 passing tests: 31 backend
and 33 frontend. Both suites were executed during release preparation; the
frontend suite reran after the container redesign. Searchable-options
generation finds `com.github.nizienko.cmdwidget.settings` with its Add/Edit/Remove,
enable/disable, ordering, and project-selector controls. This confirms settings
instantiation in a headless IDE, not visual layout acceptance.

The release ZIP is `build/distributions/cmdwidget-1.0.zip`. It contains
the root plugin JAR, searchable options, and backend/frontend/shared content
module JARs. Structure/configuration checks passed. The metadata describes the
settings page and macOS/Linux execution support.

## Target-build bytecode compatibility

The cached JetBrains Plugin Verifier 1.410 checked the release ZIP against the local
IntelliJ IDEA 2026.1.5 distribution (IU-261.27258.48):

```sh
java -Dplugin.verifier.home.dir="$PWD/build/pluginVerifier-offline-cache" \
  -jar /path/to/verifier-cli-1.410-all.jar \
  check-plugin build/distributions/cmdwidget-1.0.zip \
  .intellijPlatform/ides/IU-2026.1.5 -offline \
  -verification-reports-dir build/reports/pluginVerifier-1.0
```

Verdict: **Compatible**, with 2 deprecated, 13 experimental, and **0 internal API**
usages. Experimental usages are in generated RPC code. Deprecated usages come
from the inherited `StatusBarWidget.getPresentation(PlatformType)` bridge on the
factory-owned host. Dynamic status-bar addition and removal calls have been
eliminated. No new settings API problems were reported. The dynamic
plugin check says the plugin can probably be enabled/disabled without restart;
actual unload acceptance is still required.

Report: `build/reports/pluginVerifier-1.0/IU-261.27258.48/report.html`.
The IDE's product layout metadata contains nonexistent paths and produced
warnings. The verifier's online attempt failed fetching API-change data and
resolving an optional Java plugin dependency (`intellij.platform.frontend.split`)
because network/DNS was unavailable. Offline verification resolved the plugin's
direct dependencies from the target IDE and returned Compatible, but does not
establish optional-plugin combinations, cross-process runtime behavior, or
compatibility with other IDE builds.

The release investigation confirmed that both `StatusBar.removeWidget` and the
`addWidget` overloads accepting a parent disposable are marked internal in build
261. The maintainer approved a lifecycle redesign: one factory-owned
`CustomStatusBarWidget` now contains separate visual command elements. Elements
retain their own values, tooltips, click popups, and progress bars. Reordering
preserves instances, removal disposes only the removed element, and host cleanup
affects only its own Swing children. Tests cover order and non-overlapping layout,
empty-container hiding, disposal, and late delivery/replacement cleanup.

## Manual and feedback checklist

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

Earlier automated computer-control attempts were denied access to IntelliJ.
The maintainer subsequently reported passing most manual scenarios and accepted
following up the remainder through feedback. Linux runtime coverage and later
IDE builds remain verification limitations. The release declares since-build
261 without until-build at the maintainer's request.
