# Release Smoke Checklist

Use this checklist before publishing every plugin release. It supplements automated tests with the frontend/backend paths that require a real IntelliJ split-mode session.

## Prerequisites

- Install OCaml, OPAM, `ocamllsp`, Dune, `ocamlformat`, and UTop in a test switch.
- Have a small Dune project with one executable and at least one test stanza.
- For the first JetBrains Marketplace release, upload the signed plugin archive manually before enabling token-based Gradle publication for later versions.

## Automated Split Mode baseline

- [ ] Set `LICENSE_KEY` to the Base64-encoded contents of an IntelliJ IDEA Ultimate offline activation file accepted by the Starter test instance.
- [ ] Confirm the `Split Mode Smoke` CI job actually ran and passed; missing `LICENSE_KEY` now fails the job instead of reporting success.
- [ ] Run `./gradlew testIdeUiSplitMode` (or `.\gradlew.bat testIdeUiSplitMode` on Windows).
- [ ] Confirm the test opens OCaml Settings, triggers a refresh, receives non-default toolchain status through Fleet RPC, and observes that status in the frontend UI.

## Split-mode RPC and editor lifecycle

- [ ] Launch **Run IDE (Split Mode)** from the repository run configurations.
- [ ] Open the Dune project and mark it trusted.
- [ ] Open **Settings | Languages & Frameworks | OCaml** and trigger a toolchain refresh with the real release toolchain.
- [ ] Confirm OPAM, `ocamllsp`, Dune, `ocamlformat`, and UTop show the expected installed versions; the automated baseline covers the RPC path, while this check validates the release environment itself.
- [ ] Set the OPAM executable to a deliberately missing path and apply the settings.
- [ ] Confirm the OPAM failure reaches the settings page and the OPAM-dependent tools report that OPAM could not be started.
- [ ] Restore the valid OPAM executable and selected switch, apply the settings, and confirm successful statuses return without reopening Settings.
- [ ] Close and reopen the project in the same split-mode IDE session, then confirm status delivery is re-established.
- [ ] Open an `.ml` file immediately after project startup and confirm lexical highlighting is present while `ocamllsp` starts.
- [ ] Confirm semantic highlighting and diagnostics appear after `ocamllsp` becomes ready.

## Dune model and run configurations

- [ ] Confirm Dune Build, Run, and Test configurations are provisioned once and remain idempotent after saving a `dune` file repeatedly.
- [ ] Change an executable `public_name` without changing its local name and confirm an untouched managed configuration is renamed.
- [ ] Add program arguments to a generated Run configuration, change the executable stanza, and confirm the customized configuration remains user-owned instead of being removed.
- [ ] Upgrade a sandbox containing a legacy default-named Run configuration whose target is the old public name and confirm it is adopted without a duplicate.
- [ ] Make two Dune model edits while an earlier `dune describe workspace` is still running and confirm only the newest model is reflected.
- [ ] Run two Dune operations concurrently while managed watch mode is active and confirm watch resumes only after both operations finish.
- [ ] Start a long Dune build and confirm stdout/stderr appears continuously in the Build tool window, then cancel it and confirm no child compiler processes remain.
- [ ] Open a workspace containing two nested `dune-project` roots and confirm both roots are linked, watched, and provisioned without duplicate configurations.
- [ ] Comment out an executable stanza and confirm its gutter run marker disappears; verify `(executables ...)`, `(tests ...)`, and `(cram ...)` markers.
- [ ] With managed watch active, run the Dune `build` task and confirm it completes through `dune rpc build .` without starting a second watch/build process.

## Environment and REPL

- [ ] Remove UTop from a disposable environment and confirm **Open OCaml REPL** is disabled until the repair action installs it.
- [ ] Select a Dune Package Management environment, install missing developer tools, and confirm LSP, formatting, Dune tasks, and `dune utop` use that project sandbox.
- [ ] Change the language-server executable and additional arguments, then confirm the running OCaml language service restarts without reopening the project.

## Packaging

- [ ] Confirm the candidate version is intentional and contains no `SNAPSHOT` suffix.
- [ ] For a GitHub prerelease, confirm publication targets the Marketplace `beta` channel.
- [ ] For a normal GitHub release, confirm publication targets the Marketplace `default` channel.
- [ ] Complete the repository build, tests, Plugin Verifier, signing, and archive-install checks before publishing.
