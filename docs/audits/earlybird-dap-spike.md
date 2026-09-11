# Earlybird / JetBrains DAP compatibility spike

## Decision

Do not ship debugger integration yet. Keep debugging as an isolated compatibility spike until the JetBrains DAP module is documented as a stable third-party extension surface and Earlybird compatibility is proven end to end.

## IntelliJ Platform 2026.2 evidence

The pinned 2026.2.0.1 IDE distribution contains the `intellij.platform.dap` module and public-looking types such as `DebugAdapterSupportProvider`, `DebugAdapterDescriptor`, `DapLaunchArgumentsProvider`, and `CommandLineDebugAdapterHandle`. The module also contains its own XDebugger bridge.

That binary presence is not enough for a production dependency. The Plugin SDK bundled with this project does not establish a stable third-party registration contract, compatibility promise, or split-mode ownership model for those types. Most of the implementation is under `com.intellij.platform.dap.impl`, which the plugin must not bind to.

## Required proof before implementation

1. JetBrains documents the supported module dependency and extension points for third-party DAP adapters.
2. A minimal provider can launch Earlybird through the selected `OCamlEnvironment` without referencing any `.impl` package.
3. Breakpoints in `.ml` files, source mapping through Dune build paths, stack frames, variables, evaluation, stepping, termination, and adapter failure all work through standard XDebugger UI.
4. The same scenario works in local mode and Split Mode with backend-side process ownership.
5. Windows and Unix paths, local OPAM switches, named switches, and project relocation are covered.
6. Plugin Verifier accepts the dependency without internal/experimental API errors that would make Marketplace compatibility fragile.

## Rejected approaches

- A custom Swing debugger UI.
- Direct dependencies on `com.intellij.platform.dap.impl`.
- Parsing a human-oriented debugger console instead of using DAP.
- Advertising debugging before source mapping and breakpoint persistence are reliable.

Until these gates are satisfied, the native Run, Dune task, compiler hyperlink, and REPL workflows remain the supported execution experience.
