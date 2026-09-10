# Audit Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining Dune provisioning, OCaml lexer, toolchain execution, split-mode release-validation, and release-metadata gaps from the September 2026 audit.

**Architecture:** Add explicit freshness and ownership metadata at the boundaries where asynchronous or generated state enters the IDE. Preserve the existing single-root services and RPC contract, strengthen their behavior with focused regression coverage, and document the one cross-process path that cannot be represented by the current test harness.

**Tech Stack:** Kotlin 2.4, IntelliJ Platform 2026.2 APIs, JUnit 4, Gradle Kotlin DSL, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-10-audit-follow-up-design.md`

## Global Constraints

- Keep one Dune root and one managed Dune watch per IntelliJ project.
- Keep the safe default Dune language version at `3.0`.
- Release version is `0.1.0`.
- A GitHub prerelease targets Marketplace channel `beta`; a normal release targets `default`.
- Do not run tests, builds, linters, formatters, Plugin Verifier, IDEs, CI jobs, or publication commands.
- Do not create commits or push changes.

---

### Task 1: Reject obsolete Dune model refresh results

**Files:**

- Modify: `backend/src/test/kotlin/dune/run/DuneRunConfigurationProvisioningServiceTest.kt`
- Modify: `backend/src/main/kotlin/dune/run/DuneRunConfigurationProvisioningService.kt`

**Interfaces:**

- Produce `LatestRefreshGeneration.next(): Long`, `isCurrent(Long): Boolean`, and `invalidate()` for the provisioning service.

- [x] Add a regression test that requests two generations and proves an apply guarded by the first generation is rejected while the second remains current.
- [x] Add an atomic generation helper and capture its value in every scheduled refresh.
- [x] Check freshness before discovery and again inside the EDT callback before calling `provisionDuneRunConfigurations`.
- [x] Invalidate outstanding work during disposal.

### Task 2: Migrate, update, and detach generated Dune configurations safely

**Files:**

- Modify: `src/test/kotlin/dev/munormae/DuneRunConfigurationProvisioningIntegrationTest.kt`
- Modify: `backend/src/main/kotlin/dune/run/DuneRunConfiguration.kt`
- Modify: `backend/src/main/kotlin/dune/run/DuneRunConfigurationProvisioner.kt`

**Interfaces:**

- Persist `lastGeneratedName`, `lastGeneratedModelName`, `lastGeneratedTarget`, and `lastGeneratedWorkingDirectory` on `DuneRunConfigurationOptions`.
- Produce ownership helpers that initialize generated values, detect customization, detach safely, and conservatively recognize pre-ownership configurations.

- [x] Add an integration test for adopting an unmarked `Dune Run camel-app` configuration with target `camel-app`, empty arguments, and the expected working directory.
- [x] Add an integration test proving program-argument customization detaches a configuration and prevents stale removal.
- [x] Add an integration test proving manual display-name customization detaches a configuration.
- [x] Add an integration test proving an untouched configuration follows a changed generated display name when model identity is unchanged.
- [x] Add generated-baseline properties to the persisted run-configuration options.
- [x] Reconcile old managed entries before matching desired entries: upgrade exact untouched matches, detach customized matches, and preserve ambiguous stale entries by detaching.
- [x] Adopt pre-ownership entries only when command, default name, empty arguments, legacy target, and canonical working directory all match.
- [x] Update untouched managed entries through one helper that also refreshes their display name and baseline.
- [x] Detach user-modified configurations by clearing all ownership metadata before stale removal.

### Task 3: Make the OCaml fallback lexer incrementally restartable

**Files:**

- Modify: `src/test/kotlin/dev/munormae/OCamlLexerGoldenTest.kt`
- Modify: `shared/src/main/kotlin/lang/highlighting/OCamlLexer.kt`

**Interfaces:**

- Expose stable lexer state constants for default, normal string, escaped string, quoted string, and encoded nested-comment depth/mode.

- [x] Add tests that restart lexing on the second line of a normal string, quoted string, and nested comment using the preceding token's state.
- [x] Add a regression test proving `*)` inside a string within a comment does not close the comment.
- [x] Add literal expectations for decimal `\169`, hexadecimal `\xA9`, and octal `\o251` character escapes.
- [x] Track token-start and next-token state following the existing `DuneLexer` contract.
- [x] Split multiline constructs at line boundaries and resume them from `initialState`.
- [x] Encode comment nesting depth and string escape mode in the integer state.
- [x] Recover an active quoted-string delimiter from the preceding buffer on incremental restart.
- [x] Consume complete numeric character escape payloads before checking the closing quote.

### Task 4: Keep blocking tool probes off the common pool

**Files:**

- Modify: `backend/src/test/kotlin/toolchain/OCamlToolchainDetectionServiceTest.kt`
- Modify: `backend/src/main/kotlin/toolchain/OCamlToolchainDetectionService.kt`

**Interfaces:**

- Extend `detectToolchain` with an internal `Executor` parameter defaulted to `AppExecutorUtil.getAppExecutorService()`.

- [x] Add a test with a named executor that records probe thread names and proves each independent probe ran there.
- [x] Pass the executor explicitly to every `CompletableFuture.supplyAsync` call.
- [x] Preserve the existing OPAM short-circuit and result ordering.

### Task 5: Make split-mode RPC validation explicit

**Files:**

- Modify: `src/test/kotlin/dev/munormae/OCamlToolchainStatusSnapshotTest.kt`
- Create: `docs/release-smoke-checklist.md`
- Modify: `README.md`

**Interfaces:**

- The smoke checklist is the release gate for frontend-to-backend status delivery until an automated two-process harness exists.

- [x] Rename the DTO test so it makes only the value-object contract it actually checks.
- [x] Document exact split-mode checks for refresh, backend resolution, success/failure status delivery, reconnection, and semantic highlighting startup.
- [x] Link the checklist from the README development section.

### Task 6: Prepare stable and prerelease publication metadata

**Files:**

- Modify: `gradle.properties`
- Modify: `build.gradle.kts`
- Modify: `.github/workflows/release.yml`
- Modify: `CHANGELOG.md`

**Interfaces:**

- Consume Gradle property `pluginChannel`, defaulting to `default`, in `intellijPlatform.publishing.channels`.

- [x] Change the project version from `1.0.0-SNAPSHOT` to `0.1.0`.
- [x] Configure publishing channels from the `pluginChannel` Gradle property.
- [x] Select `beta` for GitHub prereleases and `default` for normal releases before invoking `publishPlugin`.
- [x] Date the `0.1.0` changelog section and include the refresh ordering, configuration migration/ownership, lexer, executor, RPC checklist, and release-channel changes.
- [x] Leave publication itself entirely to the user.
