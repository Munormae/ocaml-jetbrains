# Dune Scalability and Runtime Implementation Plan

## 1. Canonical parsing and model snapshots

- Add failing tests for comment-aware stanza locations, plural target forms, multi-root assignment, and incremental file invalidation.
- Extend the S-expression parser with source spans and reusable Dune stanza extraction.
- Introduce immutable root/workspace snapshots and a cached source index.
- Route run provisioning, gutter targets, External System tasks, and project roots through the canonical snapshot.

## 2. Asynchronous Dune supervision

- Add failing scheduler tests proving refresh returns before termination and replacements are serialized.
- Move watch start/stop/replacement/pause/disposal onto one serial background executor.
- Add bounded process-tree termination shared by watch, External System, and long-running environment operations.

## 3. Streaming External System execution

- Add tests proving output is delivered before process completion and cancellation kills descendants.
- Replace capturing execution with a streaming handler and listener.
- Remove resolver-side recursive discovery and consume canonical model data.

## 4. Exact coarse Workspace Model roots

- Add integration tests for removal of stale roots/excludes and bounded root counts.
- Replace additive updates with exact plugin-owned snapshots.
- Register and use OCaml-specific source/test root IDs.

## 5. Toolchain lifecycle

- Add tests for cheap discovery, lazy switch enumeration, O(1) selection, probe caching, and complete LSP runtime keys.
- Split discovery into cheap and lazy phases and remove synchronous discovery from accessors.
- Add UTop health/install/repair and pre-spawn trust checks.
- Make OPAM install/bootstrap cancellable with process-tree cleanup.

## 6. Dune Package Management and multiple roots

- Add capability tests for `dune tools`, environment command construction, and fallback behavior.
- Add `DUNE_PACKAGE_MANAGEMENT` descriptors and environment execution.
- Supervise watch/model state independently per discovered Dune root.

## 7. CI, wrapper, and API maintenance

- Add the Split Mode smoke job with secret-aware execution and release gating documentation.
- Add the official Gradle distribution checksum.
- Migrate deprecated APIs touched by this work and keep experimental Fleet RPC isolated.

## 8. Verification and handoff

- Record user-visible changes in the changelog.
- Do not run further tests, build tasks, IDE/UI smoke tests, CI checks, or Plugin Verifier while the active `AGENTS.md` instructions forbid checks; leave these to the user.
- Do not commit or push until the user gives a separate explicit command.
