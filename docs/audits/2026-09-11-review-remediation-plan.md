# September 2026 Review Remediation Plan

**Goal:** Correct OCaml and Dune lexical behavior, make Dune Watch replacement safe, harden CI and release automation, and add a real Split Mode RPC/UI smoke test.

**Architecture:** Keep the existing hand-written fallback lexers and single-root Dune services, but make their state machines match the current OCaml 5.5 and Dune lexical grammars. Keep release automation in GitHub Actions, use immutable action revisions, and add a dedicated JetBrains Starter/Driver test source set for the cross-process UI path.

**Tech stack:** Kotlin 2.4, IntelliJ Platform 2026.2, JUnit 4, JUnit 5 Starter/Driver integration tests, Gradle Kotlin DSL, GitHub Actions.

## Global constraints

- Do not run tests, builds, linters, formatters, Plugin Verifier, IDEs, CI jobs, or publication commands.
- Do not create commits or push changes.
- Keep the one-Dune-root and one-watch-per-project model.
- Preserve the current Split Mode frontend/shared/backend boundaries.
- Add regression tests before changing production behavior, but leave them unexecuted under repository instructions.

## Task 1: OCaml 5.5 lexical correctness

**Files:**

- Modify `src/test/kotlin/dev/munormae/OCamlLexerGoldenTest.kt`.
- Modify `shared/src/main/kotlin/lang/highlighting/OCamlLexer.kt`.

- [x] Replace the incorrect `#let`/`#foo` golden expectations with `\#let` raw identifiers and separate `#` plus keyword/method-name tokens.
- [x] Add PPX quoted-string cases for `{%sql|...|}`, `{%%foo|...|}`, and `{%foo tag|...|tag}`.
- [x] Add an incremental restart assertion for a multiline PPX quoted string.
- [x] Add exact accepted OCaml 5.5 lowercase/uppercase letter boundary cases and rejected Greek, Cyrillic, CJK, and non-ASCII digit cases.
- [x] Add valid `\o251` and invalid `\o777` character cases.
- [x] Add beginning-of-line line-directive cases and prove a non-leading `#` remains a reserved/operator token.
- [x] Change raw identifier recognition to require `\#` followed by a lowercase identifier start.
- [x] Generalize quoted-string opening parsing to ordinary and `%`/`%%` extension forms while preserving the existing marker-based restart state.
- [x] Replace broad Unicode predicates with the exact OCaml letter sets and ASCII digits.
- [x] Restrict octal character escapes to `[0-3][0-7][0-7]`.
- [x] Tokenize valid line directives as whitespace only at the start of a line.

## Task 2: Dune end-of-line strings and fallback discovery

**Files:**

- Modify `src/test/kotlin/dev/munormae/DuneSyntaxHighlightingTest.kt`.
- Modify `backend/src/test/kotlin/dune/SExpressionTest.kt`.
- Modify `backend/src/test/kotlin/dune/run/DuneRunConfigurationProvisionerTest.kt`.
- Modify `shared/src/main/kotlin/lang/highlighting/DuneLexer.kt`.
- Modify `backend/src/main/kotlin/dune/SExpression.kt`.

- [x] Add lexer cases proving each `"\|`/`"\>` line is a string and ordinary strings retain restart behavior.
- [x] Add full-lexing versus per-line incremental-restart invariants for both handwritten lexers.
- [x] Add parser cases with mixed `"\|` and `"\>` blocks containing parentheses.
- [x] Add the requested fallback-discovery fixture with an EOL string before `(executable (name main))` and assert that `main` is discovered.
- [x] Detect EOL-string introducers before ordinary quoted strings and consume a single physical line in the highlighter.
- [x] Parse a contiguous, indentation-tolerant sequence of EOL-string lines as one `SAtom`, interpreting escapes after `"\|` and preserving text after `"\>`.

## Task 3: Synchronous Dune Watch replacement

**Files:**

- Modify `backend/src/test/kotlin/dune/DuneWatchServiceTest.kt`.
- Modify `backend/src/main/kotlin/dune/DuneWatchService.kt`.

- [x] Extract a testable termination helper contract covering graceful stop, bounded wait, force kill, and failure.
- [x] Add tests for graceful termination and force-kill fallback using a small fake lifecycle adapter rather than an OS process.
- [x] Route refresh replacement, pause, disabled-state stop, and disposal through one `terminateWatch` path.
- [x] Ensure the old handler has terminated before `start()` creates its replacement.

## Task 4: CI and release hardening

**Files:**

- Modify `.github/workflows/build.yml`.
- Modify `.github/workflows/release.yml`.
- Modify `.github/dependabot.yml` only if needed for pinned-action updates.

- [x] Pin every external action to the full commit SHA of an explicit release and keep the release number in a comment.
- [x] Add a Windows job that runs `gradlew.bat check` and make release draft creation depend on it.
- [x] Replace global draft deletion with an idempotent upsert scoped to the current `gradle.properties` version.
- [x] Attach Marketplace publication to the `marketplace` GitHub Environment so secrets and optional approval rules can be managed there.

## Task 5: Automated Split Mode RPC/UI smoke test

**Files:**

- Modify `build.gradle.kts`.
- Create `src/integrationTest/kotlin/dev/munormae/OCamlSplitModeRpcUiTest.kt`.
- Modify `.github/workflows/build.yml` only if the test can run non-interactively with repository-provided credentials.
- Modify `README.md` and `docs/release-smoke-checklist.md`.

- [x] Add the JetBrains Starter/Driver JUnit 5 source set and a split-mode `testIdeUiSplitMode` task with plugin installation target `BOTH`.
- [x] Start IDEA Ultimate in Split Mode with a temporary local trusted OCaml project and the built plugin installed.
- [x] Open the OCaml settings UI, invoke `Refresh status`, and observe the final status labels through Driver.
- [x] Reject `Not checked`, `Detecting...`, trust-blocked, and backend-connection-failure outcomes, proving UI → frontend → Fleet RPC → backend detection → StateFlow → frontend delivery.
- [x] Keep the longer failure/reconnection/editor scenarios in the manual checklist.

## Task 6: Repository hygiene and external controls

**Files:**

- Modify `.gitignore`.
- Move the existing audit design/plan from `docs/superpowers/` into `docs/audits/` and update links.
- Modify `CHANGELOG.md`.

- [x] Remove obsolete `backend/sources/...` negations.
- [x] Move durable audit documents to neutral names under `docs/audits/`.
- [x] Record the language, Dune lifecycle, CI, release, and Split Mode test changes under `Unreleased`.
- [x] Document that GitHub branch protection must require Build, Test, Test (Windows), and Verify plugin and must block force pushes; applying repository settings remains external to project files.
