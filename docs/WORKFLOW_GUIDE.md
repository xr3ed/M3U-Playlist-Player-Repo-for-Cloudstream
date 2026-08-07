# Workflow Guide

This guide describes the recommended maintenance workflow for **BetbetMiro Extension**.

Use it for provider fixes, new providers, build failures, metadata work, documentation work, and release preparation.

---

## 1. General Workflow

Recommended order:

```text
issue/report -> evidence -> root cause -> scoped patch -> version bump -> build -> metadata check -> Actions check -> final status
```

Do not skip evidence for provider parser, playback, header, endpoint, or extractor changes.

---

## 2. Provider Fix Workflow

For a provider fix:

1. Identify the exact provider.
2. Read current provider code.
3. Collect active source evidence.
4. Compare old provider behavior with active source behavior.
5. Identify root cause.
6. Patch only the proven broken flow.
7. Preserve still-valid old behavior.
8. Bump provider version in `build.gradle.kts`.
9. Run or report build status honestly.
10. Report runtime and metadata status honestly.

Provider runtime should be checked with:

```text
Homepage category cards
Load detail/episode
Playback callback link > 0
```

---

## 3. New Provider Workflow

For a new provider:

1. Crawl the real source directly.
2. Collect homepage/category evidence.
3. Collect search evidence when supported.
4. Collect detail/load evidence.
5. Collect episode/movie playable item evidence.
6. Collect player/iframe/API/media evidence when possible.
7. Implement provider flow completely, not as a skeleton.
8. Add initial version and metadata.
9. Build and report status.
10. State unverified runtime parts clearly.

If playback is not proven, do not claim complete runtime support.

---

## 4. Build Failure Workflow

For build failures:

1. Identify failed task.
2. Identify failed module/provider.
3. Read exact error.
4. Patch only the cause.
5. Re-run or report build status.
6. Avoid unrelated provider changes.

Use:

```text
Kotlin syntax sanity: OK / failed / not checked
Gradle build lokal: SUCCESS / failed / not run
```

---

## 5. Metadata Workflow

For `repo.json`, `plugins.json`, artifacts, or provider metadata:

1. Identify metadata file or generated output.
2. Check JSON validity when relevant.
3. Check changed provider version.
4. Check artifact path and URL.
5. Check GitHub Actions output when used.
6. Report what was checked and what was not checked.

Use:

```text
repo.json: valid / invalid / not checked / not touched
plugins.json: valid / invalid / not checked / not touched
Artifacts: present / missing / not checked / not applicable
```

---

## 6. Documentation Workflow

For documentation-only changes:

1. Update only relevant docs.
2. Do not modify provider code.
3. Do not bump provider version.
4. Link new docs from `docs/README.md` when useful.
5. Report build as not run if not run.

Use:

```text
Gradle build lokal: not run, documentation only
Provider runtime: not affected
repo.json/plugins.json: not touched
```

---

## 7. GitHub Actions Workflow

For Actions status:

1. Check workflow name.
2. Check branch and commit.
3. Check failed job and step when failed.
4. Check artifacts when relevant.
5. Do not claim runtime behavior from workflow success alone.

Use:

```text
GitHub Actions: success / failed / not run / not verified
```

---

## 8. Final Report Workflow

Final reports should include:

```text
Changed files:
- ...

Root cause: ...
Fix summary: ...
Version bump: yes / no / not applicable

Kotlin syntax sanity: OK / failed / not checked
Gradle build lokal: SUCCESS / failed / not run
repo.json: valid / invalid / not checked / not touched
plugins.json: valid / invalid / not checked / not touched
GitHub Actions: success / failed / not run / not verified
Homepage category cards: proven / not proven / not applicable
Load detail/episode: proven / not proven / not applicable
Playback callback link > 0: proven / not proven / not applicable
Commit/PR: created / not created
File lain tidak disentuh: yes / no
```

---

## 9. Stop Conditions

Stop and ask for more evidence or report incomplete status when:

- Source cannot be reached.
- HAR/log is required but missing.
- Player flow is hidden behind token/cookie not captured.
- Build failure cannot be traced to changed code.
- Requested change would touch unrelated providers.
- User explicitly says diagnosis only or stop.

---

## Maintainer Rule

One mature scoped patch is better than many guessed fixes.

Work should move from evidence to root cause to validation, not from guess to patch.
