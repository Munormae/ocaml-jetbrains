# Dune Scalability and Runtime Design

## Goal

Make the OCaml integration safe for large repositories by replacing repeated workspace scans and synchronous process ownership with one backend-owned, multi-root Dune model and asynchronous process supervision.

## Canonical model

`DuneProjectModelService` owns an immutable workspace snapshot keyed by Dune root paths. It publishes loading/ready/failed state and target metadata. External System and run-configuration provisioning consume the snapshot; gutter actions parse their current in-memory editor text through the same S-expression parser. Workspace Model uses coarse content roots.

Root discovery recognizes nested `dune-project` files and assigns every Dune file to its nearest root. Files outside nested roots get an implicit parent root. VFS events reparse only changed Dune files; adding or removing a root marker rescans the workspace once. The initial source index performs one traversal and caches parsed files by normalized path. Only the root touched by a regular Dune edit is re-described; a root-topology change or explicit refresh re-describes all roots.

## Dune RPC boundary

`dune rpc build .` sends managed build tasks to an already-running watch session when available. The source index and `dune describe workspace` provide target metadata; the plugin does **not yet** subscribe to Dune RPC build-progress or model events. Protocol subscriptions require a separate compatible RPC transport investigation.

The cached incremental source index and `dune describe workspace` remain the sources for target metadata. An External System resolver without an open IDE project can perform its own single source-model load; within an open project it uses the published snapshot.

## Process lifecycle

`DuneWatchService` owns desired watch state on a serial background executor. Calls from EDT only enqueue a refresh and return. Replacement is ordered as graceful termination, bounded wait, process-tree force termination, bounded wait, then start; failed termination prevents replacement. Pause leases, environment changes, trust changes, disposal, and multi-root lifecycle all use this path.

External System tasks use a streaming process handler. Stdout and stderr chunks are forwarded immediately and never retained as one unbounded result. Cancellation terminates the complete process tree and releases the watch pause lease exactly once.

Long-running OPAM operations use cancellable progress. Cancellation requests graceful termination and then a bounded process-tree kill. Tool discovery never runs synchronously from a snapshot accessor.

## Toolchain and language service

Environment discovery has a cheap first phase for project-local `_opam`, the current OPAM switch, the selected environment, and PATH. Other installed switches are enumerated and probed lazily. Probe results are cached by environment identity and executable fingerprints.

The selected environment accessor is O(1). Refresh is an explicit asynchronous operation. LSP lifecycle is keyed by environment identity, executable override, parsed arguments, and PATH fingerprint, so every runtime-affecting change restarts the server.

UTop is a first-class environment tool used by health, installation, repair, and REPL enablement. The REPL repeats trust and availability checks immediately before spawning.

`DUNE_PACKAGE_MANAGEMENT` is a capability-gated environment kind backed by `dune tools`. Because Dune package management is experimental, failures remain recoverable and do not replace OPAM/PATH discovery.

## Project and execution model

Workspace Model receives a small number of coarse OCaml-owned roots rather than one root for every Dune stanza directory. Updates replace the roots owned by the plugin so removed roots and excludes disappear. OCaml-specific root IDs replace Java source-root IDs.

The shared S-expression parser exposes stanza locations and comment-aware forms. Gutter markers consume those parsed forms and support `executable`, `executables`, `test`, `tests`, and `cram` without a second regex parser.

External System projects are projections of the canonical roots. Resolver tasks are built from a published snapshot or a single model-source load when no IDE project is open; they do not call the legacy run-configuration discovery scanner.

## CI and compatibility

The Split Mode Starter/Driver smoke test becomes a dedicated CI job. The job fails without the required JetBrains license secret and blocks release-draft creation. Fork pull requests cannot receive the repository secret and need a separate trusted validation path.

The Gradle distribution is pinned with `distributionSha256Sum`. Deprecated IntelliJ APIs touched by this work are migrated where a supported 2026.2 replacement is available. Experimental Fleet RPC usage remains confined to the existing RPC contract/provider boundary.

## Verification

Behavioral tests cover model invalidation without repeated traversal, exact Workspace Model convergence, serialized asynchronous watch replacement, live task output, lazy discovery, complete LSP runtime keys, UTop/DPM health, comment-aware gutter targets, and multi-root assignment. Project instructions currently prohibit further automated verification; the full project check, Plugin Verifier, and live Split Mode smoke must be run separately when authorized.
