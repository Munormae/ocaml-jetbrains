# OCaml for IntelliJ IDEA

An OCaml language plugin for IntelliJ IDEA Ultimate 2026.2. It is built for the IntelliJ split-mode architecture and uses the IDE's native LSP client with `ocamllsp`.

## Features

- OCaml file types for `.ml` and `.mli`, with file icons
- lexical syntax highlighting, nested comments, brace matching, and comment actions
- semantic highlighting and diagnostics through `ocamllsp`
- completion, hover documentation, go to definition, references, rename, code actions, and formatting through LSP
- Dune and OPAM file types and icons
- project-level toolchain settings synchronized between split-mode frontend and backend
- OCaml New Project Wizard with executable, library, and executable + library templates
- generated Dune project files, `.ocamlformat`, `.gitignore`, sample code, and optional tests

## Requirements

- IntelliJ IDEA Ultimate 2026.2
- OCaml and OPAM
- `ocaml-lsp-server`
- Dune
- `ocamlformat` for formatting

Install the usual OCaml tools with:

```shell
opam install ocaml-lsp-server dune ocamlformat
```

The plugin runs the language server as:

```shell
opam exec -- ocamllsp
```

An OPAM switch and explicit executable paths can be configured under **Settings | Languages & Frameworks | OCaml**. Leave a path empty to resolve the corresponding command from `PATH`.

## Creating a project

Choose **File | New | Project | OCaml**, then select one of these templates:

- **Executable**
- **Library**
- **Executable + library**

The wizard creates a valid Dune structure and opens the main OCaml source file. Project names are normalized to valid Dune package names.

## Development

Build and validate the plugin:

```powershell
.\gradlew.bat buildPlugin verifyPluginStructure verifyPluginProjectConfiguration
```

The installable archive is written to `build/distributions/`.

Use the **Run IDE with Plugin (Split Mode)** run configuration to launch the sandbox. It invokes the single `runIde` task, which owns both the backend and frontend lifecycle and avoids inconsistent split-mode sandbox plugin paths.

## Module layout

- `shared` — file types, languages, icons, syntax support, and synchronized settings state
- `backend` — `ocamllsp` process lifecycle and LSP integration
- `frontend` — settings UI and the New Project Wizard
