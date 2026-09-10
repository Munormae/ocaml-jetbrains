# OCaml for IntelliJ IDEA

An OCaml language plugin for IntelliJ IDEA Ultimate 2026.2. It is built for the IntelliJ split-mode architecture and uses the IDE's native LSP client with `ocamllsp`.

## Features

- OCaml file types for `.ml` and `.mli`, with file icons
- lexical syntax highlighting, nested comments, brace matching, and comment actions
- semantic highlighting and diagnostics through `ocamllsp`, gated by IntelliJ Trusted Projects
- completion, hover documentation, go to definition, references, rename, code actions, and formatting through LSP
- optional managed `dune build --watch` process for fresh build and Dune RPC diagnostics
- native Dune Build, Dune Exec, and Dune Test run configurations, refreshed automatically when Dune model files change
- Dune and OPAM file types and icons, with syntax highlighting and editing support for Dune files
- project-level toolchain settings synchronized between split-mode frontend and backend
- OCaml New Project Wizard with minimal, executable, library, and executable + library templates
- New-file actions for OCaml modules/interfaces and templates for Dune, OPAM, and `.ocamlformat`
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

An OPAM switch and explicit executable paths can be configured under **Settings | Languages & Frameworks | OCaml**. Leave a path empty to resolve the corresponding command from OPAM or `PATH`. The page shows transient detected versions and errors and refreshes them after settings changes, project Trust, and project opening. External tools associated with an opened project are not launched until that project is trusted. The New Project Wizard may probe the locally installed Dune version without executing anything from the new project.

Enable **Run dune build --watch for richer LSP diagnostics** for Dune projects when you want the plugin to keep Dune's RPC server and build information current. The managed process starts only for trusted projects containing `dune-project` or `dune-workspace` and stops with the project.

For a Dune project, the plugin creates **Dune Build**, **Dune Exec**, and (when test stanzas exist) **Dune Test** configurations. Discovery uses `dune describe workspace` when available, falls back to parsing source stanzas, and refreshes after changes to `dune`, `dune-project`, or `dune-workspace`. You can also add or customize configurations under **Run | Edit Configurations**. Editing a generated configuration detaches it from automatic model updates so the plugin will not overwrite or remove the customized entry. Each configuration supports Dune arguments, targets or executable arguments, and a custom working directory. Commands use the OPAM switch and executable paths configured in the OCaml project settings.

## Creating a project

Choose **File | New | Project | OCaml**, then select one of these templates:

- **Minimal**
- **Executable**
- **Library**
- **Executable + library**

**Minimal** is the default and creates only `dune-project`, `dune`, `main.ml`, `.ocamlformat`, and `.gitignore`. The wizard defaults to the broadly compatible Dune language version 3.0, displays the Dune version from the selected OPAM switch (or `PATH`), and lets you opt into it explicitly. It opens the main OCaml source file and adds ready-to-use **Dune Build** and **Dune Run main** configurations. Project names are normalized to valid Dune package names.

## Creating files

Right-click a project directory and choose **New | OCaml Module**. The dialog can create an implementation (`.ml`), an interface (`.mli`), or both with the same base name. The **New** menu also contains templates for `dune`, `dune-project`, `dune-workspace`, OPAM package files, and `.ocamlformat`; Dune/OPAM templates default their minimum Dune language requirement to 3.0.

OCaml derives the compiled module name by capitalizing the first character of the file base name: `user_profile.ml` becomes `User_profile`, while `userProfile.ml` becomes `UserProfile`. Camel case works, but lowercase `snake_case` filenames are the conventional and more portable choice. Matching `.ml` and `.mli` files must use the same base name.

## Development

Build and validate the plugin:

```powershell
.\gradlew.bat buildPlugin verifyPluginStructure verifyPluginProjectConfiguration
```

The installable archive is written to `build/distributions/`.

Use the **Run IDE (Split Mode)** run configuration to launch the sandbox. It invokes the single `runIde` task, which owns both the backend and frontend lifecycle and avoids inconsistent split-mode sandbox plugin paths.

Before publishing, complete the [release smoke checklist](docs/release-smoke-checklist.md), including the real split-mode toolchain RPC and asynchronous highlighting checks.

## Module layout

- `shared` — file types, languages, icons, syntax support, and synchronized settings state
- `backend` — `ocamllsp`, Dune process lifecycle, run configurations, project creation, and LSP integration
- `frontend` — settings UI
