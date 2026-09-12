# Repository Settings

These controls live in GitHub and cannot be enforced by files in the repository alone.

## Protect `master`

Create a branch ruleset for `master` with these settings:

- require a pull request before merging;
- require the `Build`, `Test`, `Test (Windows)`, `Split Mode Smoke`, and `Verify plugin` status checks;
- require branches to be up to date before merging;
- block force pushes and branch deletion;
- do not allow bypasses unless an emergency-maintainer policy is explicitly required.

The workflow job display names above are defined in `.github/workflows/build.yml`.

## Gate Marketplace publication

Create a GitHub Environment named `marketplace`. Store `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and `PRIVATE_KEY_PASSWORD` as environment secrets rather than repository-wide secrets.

Configure required reviewers on the environment if publication must wait for explicit confirmation that the release smoke checklist has been completed. The release workflow declares `environment: marketplace`, so its publish job cannot access those secrets or proceed past environment protection rules until approval is granted.

Store a Base64-encoded IntelliJ IDEA Ultimate offline activation file as the repository or organization secret `LICENSE_KEY`. The `Split Mode Smoke` job fails if this secret is unavailable; configure the secret before requiring that job in branch rules or preparing a release. GitHub does not pass repository secrets to workflows from fork pull requests, so external contributions require a separate trusted validation path.
