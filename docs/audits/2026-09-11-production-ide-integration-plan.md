# Production OCaml IDE Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Repository instructions prohibit subagent dispatch, test execution, commits, and CI monitoring.

**Goal:** Make OCaml environment, project structure, Dune state, and language-service state native project concepts shared by every plugin workflow.

**Architecture:** Backend project services own machine-local discovery, process execution, Workspace Model changes, SDK binding, and Dune loading. Shared DTOs and settings define the split-mode boundary; frontend UI only renders and edits those states.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.2 Workspace Model/SDK/LSP/Execution APIs, Kotlin UI DSL 2, Fleet RPC, JUnit 4 platform tests.

**Spec:** `docs/audits/2026-09-11-production-ide-integration-design.md`

## Global constraints

- Keep filesystem and process work on the backend in split mode.
- Never run project-local commands before Trusted Projects approval.
- Keep machine paths and environment selection in `StoragePathMacros.WORKSPACE_FILE`.
- Use the built-in Language Services and execution surfaces instead of custom status widgets or terminals.
- Preserve managed run-configuration ownership, detachment, and stale-result protection.
- Do not execute tests, builds, formatters, Plugin Verifier, IDE, or CI from this agent session.
- Do not create commits or push changes.

---

### Task 1: Environment domain and local persistence

**Files:**
- Create: `shared/src/main/kotlin/toolchain/OCamlEnvironment.kt`
- Create: `shared/src/main/kotlin/settings/OCamlWorkspaceSettings.kt`
- Modify: `shared/src/main/kotlin/settings/OCamlProjectSettings.kt`
- Modify: `shared/src/main/kotlin/settings/OCamlSettingsRemoteInfoProvider.kt`
- Modify: `shared/src/main/kotlin/toolchain/OCamlToolchainRpcApi.kt`
- Test: `src/test/kotlin/dev/munormae/OCamlToolchainStatusSnapshotTest.kt`

**Produces:** `OCamlEnvironmentDescriptor`, `OCamlToolStatus`, `OCamlEnvironmentKind`, workspace-local overrides, and RPC operations for refresh, selection, and repair.

- [x] Add tests proving readiness is derived from compiler/Dune/LSP health and that a selected descriptor is preserved by the RPC snapshot.
- [x] Add serializable environment/tool/model DTOs without exposing backend domain objects.
- [x] Split shareable semantic settings from workspace-local machine settings and migrate legacy fields.
- [x] Register both setting components for remote development.

### Task 2: Backend environment discovery and SDK binding

**Files:**
- Create: `backend/src/main/kotlin/toolchain/OCamlEnvironmentDiscovery.kt`
- Create: `backend/src/main/kotlin/toolchain/OCamlSdkType.kt`
- Create: `backend/src/main/kotlin/toolchain/OCamlEnvironmentSdkService.kt`
- Modify: `backend/src/main/kotlin/toolchain/OCamlToolchainDetectionService.kt`
- Modify: `backend/src/main/kotlin/toolchain/OCamlToolchainRpcApiProvider.kt`
- Test: `backend/src/test/kotlin/toolchain/OCamlToolchainDetectionServiceTest.kt`

**Produces:** deterministic OPAM/local/PATH candidate discovery, one selected environment, project SDK synchronization, and explicit `opam install` repair requests.

- [x] Add tests for local-switch precedence, OPAM switch parsing, PATH fallback, stable IDs, and tool command construction.
- [x] Discover candidates and health in background generations.
- [x] Bind a healthy selected environment to a project-level `OCamlSdkType` SDK.
- [x] Implement confirmed repair through the selected OPAM environment.

### Task 3: Workspace Model project foundation

**Files:**
- Create: `backend/src/main/kotlin/project/OCamlProjectModelService.kt`
- Test: `backend/src/test/kotlin/project/OCamlProjectModelServiceTest.kt`
- Modify: `backend/src/main/resources/ocaml.jetbrains.backend.xml`

**Produces:** `deriveOCamlProjectLayout(root)` and `ensureOCamlModule(root, name)` creating a persistent generic module, content root, source roots, and exclusions.

- [x] Add layout tests for minimal, application, library, and existing-project trees.
- [x] Implement idempotent Workspace Model entity creation.
- [x] Add startup auto-configuration for recognizable existing OCaml projects.

### Task 4: One Dune project model

**Files:**
- Create: `backend/src/main/kotlin/dune/model/DuneProjectModel.kt`
- Create: `backend/src/main/kotlin/dune/model/DuneProjectModelService.kt`
- Modify: `backend/src/main/kotlin/dune/run/DuneWorkspaceModel.kt`
- Modify: `backend/src/main/kotlin/dune/run/DuneRunConfigurationProvisioningService.kt`
- Modify: `backend/src/main/kotlin/dune/run/DuneRunConfigurationProvisioner.kt`
- Test: `backend/src/test/kotlin/dune/run/DuneWorkspaceModelTest.kt`
- Test: `backend/src/test/kotlin/dune/run/DuneRunConfigurationProvisioningServiceTest.kt`

**Produces:** a state flow with root, executable, library, test, package, and source-root data; provisioning consumes `Ready(model)` only.

- [x] Add tests for model states and source/describe model merging.
- [x] Move discovery ownership into `DuneProjectModelService`.
- [x] Publish model refreshes to run configuration provisioning and RPC health.

### Task 5: Settings and localization

**Files:**
- Create: `shared/src/main/kotlin/OCamlBundle.kt`
- Create: `shared/src/main/resources/messages/OCamlBundle.properties`
- Rewrite: `frontend/src/main/kotlin/settings/OCamlSettingsConfigurable.kt`
- Modify: `frontend/src/main/kotlin/toolchain/OCamlToolchainStatusService.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Produces:** Kotlin UI DSL settings with environment selector, four health rows, Dune project status, Refresh/repair actions, and collapsed native browse overrides.

- [x] Replace hardcoded settings strings with bundle keys.
- [x] Bind the semantic and workspace-local state through `DialogPanel`.
- [x] Keep backend refresh/install requests asynchronous and expose their failure state.

### Task 6: Transactional project wizard

**Files:**
- Rewrite: `backend/src/main/kotlin/project/OCamlNewProjectWizard.kt`
- Create: `backend/src/main/kotlin/project/OCamlProjectBootstrapper.kt`
- Test: `backend/src/test/kotlin/project/OCamlNewProjectWizardTest.kt`

**Produces:** PropertyGraph-backed project type/environment/repair state, a visible normalized package name, advanced Dune version, and a progress-backed setup pipeline that creates module → environment/SDK → Dune model → run configurations → editor.

- [x] Replace silent Dune name normalization with a visible derived package name.
- [x] Replace technical OPAM/watch controls with an environment selector and explicit repair plan.
- [x] Make managed Dune watch the default implementation behavior.

### Task 7: Native LSP and existing-project recovery

**Files:**
- Modify: `backend/src/main/kotlin/lsp/OCamlLspIntegrationProvider.kt`
- Create: `backend/src/main/kotlin/toolchain/OCamlProjectSdkSetupValidator.kt`
- Create: `backend/src/main/kotlin/toolchain/OCamlEditorNotificationProvider.kt`
- Modify: `backend/src/main/resources/ocaml.jetbrains.backend.xml`
- Test: `backend/src/test/kotlin/lsp/OCamlLspIntegrationProviderTest.kt`

**Produces:** `LspClientWidgetItem` with OCaml icon/settings link, SDK setup banner, and missing-tool Configure/Install recovery.

- [x] Add applicability tests for OCaml files, trust, selected environment, and missing LSP.
- [x] Register native widget presentation and editor recovery extensions.

### Task 8: Model-backed execution

**Files:**
- Rewrite: `backend/src/main/kotlin/dune/run/DuneRunConfigurationEditor.kt`
- Modify: `backend/src/main/kotlin/dune/run/DuneRunConfiguration.kt`
- Create: `backend/src/main/kotlin/dune/run/DuneConsoleFilter.kt`
- Test: `backend/src/test/kotlin/dune/run/DuneRunConfigurationTest.kt`

**Produces:** fragmented editor with a Dune-model target selector, program arguments and a browsable working directory as primary fields, Dune arguments and overrides in Modify options, and clickable OCaml file/line output.

- [x] Add tests for target presentation and OCaml compiler-location parsing.
- [x] Construct fragments from standard IntelliJ execution components.
- [x] Attach a file hyperlink filter to the execution console.

### Task 9: Native developer workflows

**Files:**
- Create: `backend/src/main/kotlin/navigation/OCamlRelatedFileProvider.kt`
- Create: `backend/src/main/kotlin/repl/OpenOCamlReplAction.kt`
- Modify: `backend/src/main/resources/ocaml.jetbrains.backend.xml`
- Test: `backend/src/test/kotlin/navigation/OCamlRelatedFileProviderTest.kt`

**Produces:** Navigate → Related Symbol for `.ml`/`.mli` and a standard interactive `dune utop` console using the selected environment.

- [x] Add counterpart-resolution tests.
- [x] Register navigation and Tools/OCaml REPL actions with bundled text.

### Task 10: Platform integration boundaries and documentation

**Files:**
- Modify: `src/test/kotlin/dev/munormae/PluginDescriptorSmokeTest.kt`
- Modify: `src/integrationTest/kotlin/dev/munormae/OCamlSplitModeRpcUiTest.kt`
- Create: `docs/audits/earlybird-dap-spike.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

**Produces:** descriptor coverage, split-mode environment UI coverage, an evidence-based debugger decision record, and user documentation for the zero-configuration flow.

- [x] Cover every new extension/service registration in descriptor tests.
- [x] Extend split-mode UI assertions to environment and Dune state.
- [x] Document stable debugger integration criteria and reject undocumented DAP dependencies.
- [x] Document the new project and existing-project flows.
