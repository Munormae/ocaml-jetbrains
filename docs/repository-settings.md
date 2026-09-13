# Repository Settings

These controls live in GitHub and cannot be enforced by files in the repository alone.

## Protect `master`

Create a branch ruleset for `master` with these settings:

- require a pull request before merging;
- require the `Build`, `Test`, `Test (Windows)`, and `Verify plugin` status checks;
- require branches to be up to date before merging;
- block force pushes and branch deletion;
- do not allow bypasses unless an emergency-maintainer policy is explicitly required.

The workflow job display names above are defined in `.github/workflows/build.yml`.

## Gate Marketplace publication

Create a GitHub Environment named `marketplace`. Store `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and `PRIVATE_KEY_PASSWORD` as environment secrets rather than repository-wide secrets.

Configure required reviewers on the environment if publication must wait for explicit confirmation that the release smoke checklist has been completed. The release workflow declares `environment: marketplace`, so its publish job cannot access those secrets or proceed past environment protection rules until approval is granted.

`Split Mode Smoke` is optional because JetBrains requires an IntelliJ IDEA Ultimate license to run it. If one is available, store the Base64-encoded offline activation file as the repository or organization secret `LICENSE_KEY`; the job then runs on trusted events and must pass before a release draft is prepared. Without the secret, the job is skipped on both pull requests and master pushes, and does not block release draft creation. Do not make it a required status check: a skipped job does not verify the frontend/backend UI roundtrip. Record this testing gap in the release checklist when no license is available.
