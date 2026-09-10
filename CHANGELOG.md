<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Ocaml-jetbrains Changelog

## [Unreleased]

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
