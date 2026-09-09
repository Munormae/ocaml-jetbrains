<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Ocaml-jetbrains Changelog

## [Unreleased]

### Added

- Dune Build, Dune Exec, and Dune Test run configurations with OPAM toolchain support.
- OCaml module/interface creation, Dune/OPAM file templates, and tested project scaffolding.
- A minimal OCaml project template and orange camel icons.
- Automatic Dune Build, Run, and Test configuration discovery for generated and existing projects.
- Dune syntax highlighting, line comments, and parenthesis matching.

### Fixed

- OCaml project creation is registered on the Split Mode backend and appears with the built-in languages.
- Bundled file templates are explicitly registered, avoiding template-usage errors in the IDE log.
