<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Cmd Widgets Changelog

## [Unreleased]

- Windows command execution through `cmd.exe`, with UTF-8 console setup and automatic host shell selection.
- Windows PowerShell starter widgets for time, project name, and disk usage; existing saved commands are preserved.
- Portable shell strategy tests and native Windows execution/cleanup tests (Windows run pending).

## [1.0] - 2026-10-01

- Independent command-driven widgets with text, automatic percentage progress bars, global settings, and project-specific results.
- Frontend and backend execution on macOS and Linux in ordinary IDE and split mode, with configurable working directories.
- Tools → Cmd Widget settings with draft editing, ordering, enable/disable, and explicit command tests.
- Bounded output capture, timeout/cancellation, stale-state diagnostics, and split-mode settings synchronization.
- One factory-owned status bar container with independently updated command elements, avoiding internal dynamic widget APIs.
