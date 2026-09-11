# Production OCaml IDE Integration Design

## Goal

Turn the plugin from a collection of executable overrides into a project-aware OCaml integration. The IDE must own four first-class concepts: an OCaml environment, an IntelliJ module, a Dune project model, and an OCaml language service.

## Architectural decisions

### Project model

Every generated OCaml project receives a persistent IntelliJ `ModuleEntity`, one content root, source roots for `lib`, `bin`, and `test` when those directories exist, and excluded `_build` and `_opam` roots. Existing OCaml projects opened as plain directories receive the same model automatically when no existing module already owns their root.

The module uses the generic module type. A custom `ModuleType` is deliberately not introduced because IntelliJ Platform 2026.2 documents module types as an outdated technology. The module and its roots are written through Workspace Model entities and persisted through the platform JPS entity source.

### OCaml environment

`OCamlSdkType` represents the compiler prefix to IntelliJ. The user-facing name remains “OCaml environment.” Environment discovery runs on the backend and returns immutable DTOs for:

- a project-local `_opam` switch;
- the active OPAM switch and other installed switches;
- a PATH fallback.

Each environment reports compiler, Dune, OCaml Language Server, and `ocamlformat` health independently. The selected environment is bound to the project SDK and is the single source used to construct LSP, Dune, formatting, and REPL command lines.

### Persistence

Shareable semantics remain in `.idea/ocaml.xml`: whether language services and standard formatting are enabled. Machine-specific selection, executable overrides, switch identity, custom LSP arguments, and managed-watch opt-out move to `StoragePathMacros.WORKSPACE_FILE`. Legacy path settings are migrated once and removed from shareable state.

### Dune model

`DuneProjectModelService` owns a state machine (`NotLoaded`, `Loading`, `Ready`, `Failed`) and the complete current model: root, executables, libraries, tests, packages, and source roots. It is the only component allowed to invoke `dune describe` or use the source fallback. Run-configuration provisioning, Settings status, target selectors, Dune watch, and project setup consume this state.

The first implementation exposes this first-class model through services and standard execution surfaces. External System support is layered on top of the same model, so it does not introduce a second importer or second target representation.

### UI and split mode

Settings and the project wizard use Kotlin UI DSL 2, state bindings, native browse fields, inline validation, and localized strings. The main Settings page exposes environment and health; executable paths live in a collapsed advanced section.

All filesystem access, process execution, project model mutation, SDK selection, Dune loading, and tool installation remain backend-side. The frontend receives small serializable health/environment/model DTOs through Fleet RPC.

### Project creation

Creation is a staged pipeline:

1. validate the project name and selected environment or explicit local-environment repair plan;
2. create the generated files;
3. create the module/content/source/excluded roots;
4. create or select the environment and bind the SDK;
5. load the Dune model;
6. provision managed run configurations;
7. start managed Dune watch and language services;
8. open the entry file.

Long operations run under native progress. A global OPAM switch is never mutated without explicit confirmation. Dune watch is managed by default for healthy Dune projects and remains an advanced opt-out.

### Native IDE surfaces

- The built-in Language Services widget receives the OCaml icon and Settings link.
- `ProjectSdkSetupValidator` and an editor notification provide actionable missing-environment and missing-tool recovery.
- Dune run configurations use model-backed target selection, native path browsing, and fragmented optional fields.
- `.ml` and `.mli` counterparts are exposed through Navigate → Related Symbol.
- REPL runs `dune utop` in a standard interactive execution console.
- Formatting remains attached to the standard Reformat Code action through the LSP formatting capability.

The first External System layer publishes Dune build/test/clean/executable tasks while the canonical service remains the source of truth for IDE state and managed Run Configurations. Gutter actions map source stanza locations back to that same target representation. A future structural Dune PSI can replace the lightweight source-offset mapping without changing the model or execution contracts. No human-output-based fake test tree is provided.

### Debugger

Debugger work is an isolated compatibility spike. It may proceed only through stable JetBrains XDebugger/DAP APIs available to third-party plugins and an Earlybird protocol probe. The plugin will not ship a custom debugger UI or bind production code to an undocumented internal DAP implementation.

## Error handling

Every long-lived service exposes explicit loading and failure states. Failures preserve the last usable environment/model where safe, log technical details, and present a short recovery action. Untrusted projects never execute tools. Async generations prevent stale discovery or sync results from replacing newer selections.

## Testing contract

Pure discovery, selection, layout, command-line, and model conversion functions receive deterministic unit tests. Workspace Model, SDK registration, descriptor registration, run configuration provisioning, related-file navigation, and notification applicability receive platform integration tests. The existing split-mode UI smoke test is extended to cover the environment selector and backend-originated state.

Repository instructions prohibit the agent from executing tests, builds, formatters, IDEs, Plugin Verifier, or CI. Tests are still written before their production changes; execution is left to the maintainer.
