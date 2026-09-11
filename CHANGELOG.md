<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Ocaml-jetbrains Changelog

## [Unreleased]

### Added

- Windows CI coverage for the Gradle `check` task.
- A JetBrains Starter/Driver Split Mode smoke test covering the settings UI, Fleet RPC toolchain refresh, backend detection state, and frontend status rendering.
- First-class OCaml environments with local OPAM, named-switch, selected-prefix, and PATH discovery; compiler/tool health; project SDK binding; and explicit install/create repair actions.
- Persistent OCaml modules and content/source/test/excluded roots through Workspace Model for generated and existing projects.
- A canonical reactive Dune project model plus linked External System tasks for build, test, clean, and discovered executables.
- Native Language Services presentation, missing-environment/tool editor banners, `.ml`/`.mli` related-file navigation, Dune gutter run actions, compiler hyperlinks, and an interactive `dune utop` console.
- A documented Earlybird/DAP compatibility decision and production gates.

### Changed

- GitHub Actions now use immutable full commit SHAs, and release drafts are updated only for the version declared in `gradle.properties`.
- Marketplace publication now targets the dedicated `marketplace` GitHub Environment, where secrets and approval rules can be scoped.
- Durable audit documents now live under `docs/audits` instead of tool-specific directories.
- OCaml Settings now use Kotlin UI DSL 2, an environment selector, actionable health, native executable pickers, and collapsed advanced overrides; Dune build settings have their own Build Tools page.
- The New Project Wizard now uses PropertyGraph state, user-facing project types, environment validation/repair, visible Dune package naming, native progress, and a staged project bootstrap.
- Dune Run Configurations now use a fragmented editor with model-backed executable selection, program arguments, and a browsable working directory, with Dune arguments, environment, and executable overrides under Modify options.
- Machine-specific toolchain selection and executable overrides now live in workspace-local state instead of shareable project settings.

### Fixed

- OCaml raw identifiers now require the `\#` prefix, while object method `#` remains a separate token.
- OCaml PPX quoted-string shorthand, exact identifier letter ranges, octal character bounds, and line directives are recognized by the fallback lexer.
- Dune end-of-line strings no longer corrupt syntax highlighting or fallback run-configuration discovery.
- Replacing, pausing, or disposing Dune Watch now waits for bounded graceful termination and falls back to force kill before continuing.

## [0.1.0] - 2026-09-10

### Added

- Dune Build, Dune Exec, and Dune Test run configurations with OPAM toolchain support.
- OCaml module/interface creation, Dune/OPAM file templates, and tested project scaffolding.
- A minimal OCaml project template and orange camel icons.
- Automatic Dune Build, Run, and Test configuration discovery for generated and existing projects.
- Dune syntax highlighting, line comments, and parenthesis matching.
- Trusted Projects enforcement and trust-state lifecycle handling for `ocamllsp` and Dune watch.
- Live Dune run-configuration discovery backed by `dune describe workspace`, with a source-model fallback.
- OPAM/PATH toolchain detection with event-driven, transient version and status feedback in OCaml settings.
- Editable Dune language-version selection in the project wizard, with selected-switch version detection and an explicit opt-in to the installed version.
- A default Dune 3.0 language requirement for standalone Dune and OPAM file templates.
- A release smoke checklist for real split-mode RPC, editor lifecycle, Dune reconciliation, and packaging checks.
- Separate stable and beta Marketplace publication channels for GitHub releases and prereleases.

### Fixed

- OCaml project creation is registered on the Split Mode backend and appears with the built-in languages.
- Bundled file templates are explicitly registered, avoiding template-usage errors in the IDE log.
- Dune run configurations use local executable targets and temporarily pause managed watch mode, so execution works reliably on Windows.
- Multiline Dune strings preserve lexer state during incremental relexing.
- CI now runs for both `main` and `master` pushes.
- Release publishing no longer invokes the unconfigured `patchChangelog` task, and CI validates the publishing task used by the release workflow.
- Dune Exec separates program arguments with `--`, so flags are passed to the executable instead of Dune.
- Concurrent Run and Describe operations hold reference-counted pause leases and resume Dune Watch only after the final operation completes.
- Automatically provisioned Dune configurations carry model ownership, reconcile changes, remove stale managed entries, and leave user configurations untouched.
- Toolchain probe status is no longer persisted or synchronized as project settings; OPAM failures short-circuit dependent probes and independent checks run concurrently.
- The project wizard probes Dune through the selected OPAM switch without automatically raising the generated project's compatibility requirement.
- Dune discovery parsing and OCaml lexical highlighting have expanded regression and fixture coverage.
- Obsolete concurrent Dune model refresh results can no longer overwrite a newer model.
- Legacy generated Dune run configurations are adopted conservatively, while customized managed configurations detach from plugin ownership.
- Untouched managed run configurations follow generated display-name changes without overwriting manual renames or arguments.
- The OCaml fallback lexer preserves incremental state for multiline strings, quoted strings, and nested comments.
- OCaml comments ignore delimiters inside string and character literals, and numeric character escapes are tokenized completely.
- Blocking toolchain probes use the IntelliJ application executor instead of the Java common pool.
