# Documentation Index

This directory contains maintenance and compatibility documentation for **BetbetMiro Extension**.

Use this page as a quick map for repository contributors, issue reporters, and maintainers.

## Core provider maintenance

- [`PROVIDER_MAINTENANCE.md`](PROVIDER_MAINTENANCE.md) — provider fixes, new providers, version bumps, source evidence, and honest status reporting.
- [`WORKFLOW_GUIDE.md`](WORKFLOW_GUIDE.md) — full maintenance flow from issue/report to evidence, patch, build, metadata checks, Actions, and final status.
- [`EVIDENCE_COLLECTION_GUIDE.md`](EVIDENCE_COLLECTION_GUIDE.md) — source URL, HTML/API, HAR, logs, screenshots, and evidence limits.
- [`RUNTIME_TESTING_GUIDE.md`](RUNTIME_TESTING_GUIDE.md) — app-level homepage, category, detail, subtitle, and callback validation rules.
- [`NEW_PROVIDER_GUIDE.md`](NEW_PROVIDER_GUIDE.md) — new provider planning and implementation expectations.
- [`PR_REVIEW_GUIDE.md`](PR_REVIEW_GUIDE.md) — scope, evidence, version bump, build, metadata, and review checks.
- [`VALIDATION_CHECKLIST.md`](VALIDATION_CHECKLIST.md) — checklist before claiming build, metadata, provider, or runtime status.

## Build, metadata, and release

- [`BUILD_GUIDE.md`](BUILD_GUIDE.md) — local Gradle build setup and reporting.
- [`ACTIONS_GUIDE.md`](ACTIONS_GUIDE.md) — GitHub Actions runs, logs, artifacts, and status reporting.
- [`REPO_METADATA_GUIDE.md`](REPO_METADATA_GUIDE.md) — `repo.json`, `plugins.json`, provider metadata, and artifact URL review.
- [`COMMIT_GUIDE.md`](COMMIT_GUIDE.md) — conventional commit wording and evidence-backed commit messages.
- [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — build, workflow, metadata, provider, domain, and network triage.
- [`COMPATIBILITY.md`](COMPATIBILITY.md) — CloudStream compatibility and runtime validation boundaries.
- [`RELEASE_PROCESS.md`](RELEASE_PROCESS.md) — release readiness and publish review.

## Monitoring and health checks

- [`DOMAIN_CHECK.md`](DOMAIN_CHECK.md) — reviewing `DomainCheck.py` and domain migration checks.
- [`HEALTH_CHECK_GUIDE.md`](HEALTH_CHECK_GUIDE.md) — read-only provider domain health checks.
- [`SMART_HEALTH_CHECK_GUIDE.md`](SMART_HEALTH_CHECK_GUIDE.md) — search/detail sample health checks.
- [`RUNTIME_HEALTH_CHECK_GUIDE.md`](RUNTIME_HEALTH_CHECK_GUIDE.md) — runtime-oriented player page, media candidate, and request header checks.
- [`provider-health-summary.yml`](../.github/workflows/provider-health-summary.yml) — provider inventory and health summary workflow artifact.

Health check reports are early-warning signals only. They do not replace app/runtime validation.

## FAQ

- [`FAQ.md`](FAQ.md) — quick answers about builds, runtime proof, version bumps, issue reports, new providers, and docs-only changes.

## Repository-level documents

- [`../README.md`](../README.md) — Indonesian main README.
- [`../README_EN.md`](../README_EN.md) — English README.
- [`../CONTRIBUTING.md`](../CONTRIBUTING.md) — contribution guide.
- [`../SUPPORT.md`](../SUPPORT.md) — support guide.
- [`../SECURITY.md`](../SECURITY.md) — security policy.
- [`../CODE_OF_CONDUCT.md`](../CODE_OF_CONDUCT.md) — community rules.
- [`../CHANGELOG.md`](../CHANGELOG.md) — changelog.
- [`../CREDITS.md`](../CREDITS.md) — credits and attribution guide.

## GitHub templates

- [`../.github/pull_request_template.md`](../.github/pull_request_template.md) — Pull Request checklist.
- [`../.github/ISSUE_TEMPLATE/provider_broken.yml`](../.github/ISSUE_TEMPLATE/provider_broken.yml) — broken provider report.
- [`../.github/ISSUE_TEMPLATE/provider_request.yml`](../.github/ISSUE_TEMPLATE/provider_request.yml) — new provider request.
- [`../.github/ISSUE_TEMPLATE/bug_report.yml`](../.github/ISSUE_TEMPLATE/bug_report.yml) — repository/build/workflow bug report.

## Maintainer rule

For provider work, prioritize evidence and CloudStream app behavior over theory.

Do not claim a status that was not actually verified.
