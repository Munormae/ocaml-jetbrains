# Release Smoke Checklist

Use this checklist before publishing every plugin release. It supplements automated tests with the frontend/backend paths that require a real IntelliJ split-mode session.

## Prerequisites

- Install OCaml, OPAM, `ocamllsp`, Dune, and `ocamlformat` in a test switch.
- Have a small Dune project with one executable and at least one test stanza.
- For the first JetBrains Marketplace release, upload the signed plugin archive manually before enabling token-based Gradle publication for later versions.

## Split-mode RPC and editor lifecycle

- [ ] Launch **Run IDE (Split Mode)** from the repository run configurations.
- [ ] Open the Dune project and mark it trusted.
- [ ] Open **Settings | Languages & Frameworks | OCaml** and trigger a toolchain refresh.
- [ ] Confirm OPAM, `ocamllsp`, Dune, and `ocamlformat` change from **Not checked** to their detected versions. This exercises the frontend refresh call, backend project resolution and probing, Fleet RPC serialization, backend `StateFlow`, and frontend delivery.
- [ ] Set the OPAM executable to a deliberately missing path and apply the settings.
- [ ] Confirm the OPAM failure reaches the settings page and the three OPAM-dependent tools report that OPAM could not be started.
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

## Packaging

- [ ] Confirm the candidate version is intentional and contains no `SNAPSHOT` suffix.
- [ ] For a GitHub prerelease, confirm publication targets the Marketplace `beta` channel.
- [ ] For a normal GitHub release, confirm publication targets the Marketplace `default` channel.
- [ ] Complete the repository build, tests, Plugin Verifier, signing, and archive-install checks before publishing.
