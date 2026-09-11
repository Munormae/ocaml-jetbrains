# OCaml for IntelliJ IDEA

OCaml language and Dune project support for IntelliJ IDEA Ultimate 2026.2. The plugin is built for Split Mode and uses IntelliJ's native LSP, SDK, Workspace Model, External System, execution, and editor notification surfaces.

## What the IDE understands

- **OCaml Environment** — a project-local `_opam`, named OPAM switch, explicitly selected prefix, or PATH toolchain with compiler, Dune, OCaml Language Server, and `ocamlformat` health.
- **OCaml Module** — a persistent IntelliJ module with a content root, OCaml source/test roots, project SDK, and excluded `_build` and `_opam` directories.
- **Dune Project** — one reactive model for executables, libraries, tests, packages, source roots, sync status, Run Configurations, and the Dune task tree.
- **OCaml Language Service** — `ocamllsp` shown through the built-in Language Services widget, including version, lifecycle controls, error output, and an OCaml Settings action.

The plugin also provides fallback lexical highlighting, nested comments, brace matching, Dune/OPAM file types, OCaml and Dune file templates, and Trusted Projects gates around every project-local process.

## Environment setup

Open or create an OCaml project. The plugin automatically checks, in order:

1. a project-local `_opam` switch;
2. the current and other installed OPAM switches;
3. tools available on `PATH`.

The wizard also offers **Select existing** for an environment prefix outside OPAM discovery. The normal path requires no executable configuration. Use **Settings | Languages & Frameworks | OCaml** to select an environment, inspect health, install missing tools, or reveal **Executable overrides** for an unusual setup. Machine-specific environment selection and paths are kept in workspace-local state rather than shareable `.idea/ocaml.xml`.

The expected tools are:

```shell
opam install ocaml-lsp-server dune ocamlformat
```

If a project has no usable environment, OCaml editors show a native banner with **Configure** and **Create local environment** actions. A missing language server gets an **Install** repair action. Nothing from an untrusted project is executed.

## Creating a project

Choose **File | New | Project | OCaml**, then select:

- **Application**
- **Library**
- **Application + Library**

The wizard shows the derived Dune package name, discovers OCaml environments, validates compiler/Dune/LSP health, and offers an explicit project-local OPAM repair plan when required. Dune language version remains under **Advanced**.

Creation runs with native progress and prepares the project before opening the entry file: generated files, IntelliJ module/content/source/excluded roots, selected OCaml SDK, initial Dune model, managed Run Configurations, language service, and managed `dune build --watch`.

Opening an existing directory containing `dune-project`, `dune-workspace`, `_opam`, or OCaml sources follows the same automatic module/environment/model setup without a separate setup wizard.

## Dune and execution

The linked Dune project appears as a build system with `build`, `test`, `clean`, and discovered executable tasks. Dune model changes refresh managed Run Configurations while preserving the existing ownership/detachment rules for user-edited configurations.

Run editors use the Dune model for executable selection. Program arguments and a browsable working directory remain primary; Dune arguments, a custom Dune executable, and environment variables live under **Modify options**. Compiler locations such as `File "test/foo.ml", line 42` are clickable in the Run console.

In a `dune` file, executable and test stanzas expose gutter run actions. **Tools | Open OCaml REPL** starts an interactive `dune utop` console for the current project directory. **Navigate | Related Symbol** switches between matching `.ml` and `.mli` files. Standard **Reformat Code** delegates to LSP/`ocamlformat` when formatting is enabled.

Build-specific lifecycle controls and model status are also available under **Settings | Build, Execution, Deployment | Build Tools | Dune**.

## Creating files

Use **New | OCaml Module** to create an implementation (`.ml`), interface (`.mli`), or both. The **New** menu also contains templates for `dune`, `dune-project`, `dune-workspace`, OPAM package files, and `.ocamlformat`.

## Debugger status

Debugger integration is intentionally not advertised yet. The compatibility decision and the gates for a future Earlybird integration through stable JetBrains DAP/XDebugger APIs are recorded in [the Earlybird/DAP spike](docs/audits/earlybird-dap-spike.md).

## Development

Build and validate the plugin:

```powershell
.\gradlew.bat buildPlugin verifyPluginStructure verifyPluginProjectConfiguration
```

Run the real frontend-to-backend smoke test with:

```powershell
$env:LICENSE_KEY = "<Base64-encoded IntelliJ IDEA Ultimate offline activation file>"
.\gradlew.bat testIdeUiSplitMode
```

Before publishing, complete the [release smoke checklist](docs/release-smoke-checklist.md). GitHub branch protection and Marketplace environment controls that cannot be expressed in repository files are documented in [repository settings](docs/repository-settings.md).

## Module layout

- `shared` — file types, lexers, highlighting, localized resources, settings contracts, and Split Mode DTOs
- `backend` — environment discovery, SDK/project model, `ocamllsp`, Dune model/processes/execution, project creation, navigation, and REPL
- `frontend` — OCaml environment and Dune settings UI
- root `src` — aggregate plugin descriptor and whole-plugin/integration tests
