# Pull Request Review Guide

This guide explains how to review Pull Requests for **BetbetMiro Extension**.

Use it when reviewing provider fixes, new providers, documentation changes, metadata changes, build changes, and workflow changes.

---

## 1. Review Priority

Review in this order:

1. Provider behavior.
2. Gradle/build health.
3. `repo.json` and `plugins.json` validity.
4. GitHub Actions result.
5. Documentation accuracy.
6. Cosmetics.

Provider behavior wins over cosmetic changes.

---

## 2. Scope Check

Before reviewing details, check whether the PR touches only relevant files.

Use:

```text
File lain tidak disentuh: yes / no
```

Provider PRs should not modify unrelated providers, README sections, templates, or workflow files unless the change is clearly required.

Documentation PRs should not modify provider code unless requested.

---

## 3. Evidence Check

For provider PRs, verify the author explains the evidence used.

Look for:

- Source URLs checked.
- Homepage/category evidence.
- Search evidence when search changed.
- Detail/load evidence when detail parsing changed.
- Player/iframe/API evidence when playback changed.
- HAR/log/screenshot when relevant.
- Root cause category.

If evidence is missing, request evidence before merge.

---

## 4. Root Cause Check

Provider PRs should identify what actually broke.

Examples:

```text
homepage kategori kosong
search() kosong
load() gagal
episode data salah
loadLinks() true but callback 0 link
iframe/API changed
selector stale
JSON field changed
referer/origin/header changed
```

A PR that only says “fix provider” without root cause is weak.

---

## 5. Version Bump Check

If provider code changed, check provider `build.gradle.kts` version bump.

Use:

```text
Provider version bump: yes / no / not applicable
```

No provider version bump is needed for documentation-only changes.

---

## 6. Build Check

Check local or Actions build evidence.

Use:

```text
Gradle build lokal: SUCCESS / failed / not run
GitHub Actions: success / failed / not run / not verified
```

Do not accept build claims without command output, workflow result, or explicit note that build was not run.

---

## 7. Runtime Check

Provider PRs should report runtime boundaries.

Use:

```text
Homepage category cards: proven / not proven / not applicable
Load detail/episode: proven / not proven / not applicable
Playback callback link > 0: proven / not proven / not applicable
```

If runtime playback was not tested, the PR should not claim playback success.

Acceptable wording:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source.
```

With HAR/log evidence:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source/HAR.
```

---

## 8. Metadata Check

If the PR affects build output, plugin publishing, or provider metadata, check:

```text
repo.json: valid / invalid / not checked / not touched
plugins.json: valid / invalid / not checked / not touched
Artifacts: present / missing / not checked / not applicable
```

Do not require metadata validation for unrelated documentation-only PRs.

---

## 9. Documentation Check

For documentation PRs, check:

- Link path correctness.
- No false build/runtime claim.
- No conflict with provider SOP.
- No edits to unrelated provider files.
- Terminology matches repository status labels.

Docs should help maintainers, not hide provider uncertainty.

---

## 10. Merge Blockers

Do not merge if:

- Provider code changed but version was not bumped.
- Build fails and failure is caused by the PR.
- PR touches unrelated providers without justification.
- Playback is claimed but callback links were not proven.
- Metadata validity is claimed without checking.
- New provider is only a skeleton but presented as complete.
- Evidence is insufficient for risky parser/playback changes.

---

## 11. Review Comment Template

Use this template:

```text
Review status: approve / request changes / comment only

Scope: OK / needs correction
Evidence: OK / missing / incomplete
Root cause: clear / unclear
Version bump: yes / no / not applicable
Build: success / failed / not run / not verified
Runtime: proven / not proven / not applicable
Metadata: valid / invalid / not checked / not touched

Notes:
- ...
```

---

## Maintainer Rule

A PR should be reviewed against evidence, build status, metadata impact, and runtime claims.

Do not approve claims that are not supported by the PR evidence.
