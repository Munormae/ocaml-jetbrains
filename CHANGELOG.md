<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Ocaml-jetbrains Changelog

## [Unreleased]

### Added

- Dune Build, Dune Exec, and Dune Test run configurations with OPAM toolchain support.
- OCaml module/interface creation, Dune/OPAM file templates, and tested project scaffolding.
- A minimal OCaml project template and orange camel icons.
- Automatic Dune Build, Run, and Test configuration discovery for generated and existing projects.
- Dune syntax highlighting, line comments, and parenthesis matching.
- Trusted Projects enforcement and trust-state lifecycle handling for `ocamllsp` and Dune watch.
- Live Dune run-configuration discovery backed by `dune describe workspace`, with a source-model fallback.
- OPAM/PATH toolchain detection with version and status feedback in OCaml settings.
- Automatic, editable Dune language-version selection in project and file templates.

### Fixed

- OCaml project creation is registered on the Split Mode backend and appears with the built-in languages.
- Bundled file templates are explicitly registered, avoiding template-usage errors in the IDE log.
- Dune run configurations use local executable targets and temporarily pause managed watch mode, so execution works reliably on Windows.
- Multiline Dune strings preserve lexer state during incremental relexing.
- CI now runs for both `main` and `master` pushes.
