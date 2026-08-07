# Commit Guide

This guide explains the preferred commit style for **BetbetMiro Extension**.

Use it for provider fixes, new providers, documentation changes, build changes, repository metadata changes, and workflow changes.

---

## 1. Commit Style

Use concise conventional-style commit subjects:

```text
fix(provider-name): short technical summary
docs(scope): short documentation summary
chore(scope): short maintenance summary
ci(scope): short workflow summary
build(scope): short build summary
```

Keep the subject specific and technical.

Good examples:

```text
fix(anichin): update player resolver headers
docs(provider): add new provider guide
chore(git): add gitattributes
ci(actions): adjust artifact upload path
build(zeronime): bump provider version
```

---

## 2. Provider Fix Commit Body

Provider fix commits should explain:

- Root cause.
- Exact fix.
- What behavior was preserved.
- Version bump.
- Validation status.
- Unverified runtime notes when applicable.

Recommended body:

```text
- Root cause: ...
- Fix: ...
- Preserved: ...
- Version bump: ...
- Validation: ...
```

Do not claim playback success unless callback video links were proven.

---

## 3. New Provider Commit Body

New provider commits should explain:

- Source URL.
- Supported content type.
- Homepage/category behavior.
- Detail/load behavior.
- Playback/loadLinks behavior.
- Initial version.
- Known limitations.

Recommended subject:

```text
feat(provider-name): add source provider
```

A new provider should not be presented as complete if it is only a skeleton.

---

## 4. Documentation Commit Body

Documentation commits may be short.

Examples:

```text
docs(build): add local build guide
docs(index): link actions guide
docs(metadata): add repository metadata guide
```

For docs-only commits, no provider version bump is required.

---

## 5. Build / Metadata Commit Body

Build or metadata commits should explain what changed and what was checked.

Examples:

```text
build(provider-name): bump provider version
chore(metadata): update plugin list metadata
ci(actions): fix workflow artifact path
```

Report metadata status honestly:

```text
repo.json: valid / invalid / not checked / not touched
plugins.json: valid / invalid / not checked / not touched
```

---

## 6. Version Bump Rule

If provider code changes, bump the provider version in `build.gradle.kts`.

Use commit body wording:

```text
- Version bump: <old> -> <new>
```

Documentation-only changes do not require provider version bumps.

---

## 7. Evidence Wording

Provider commits should be evidence-backed.

Useful wording:

```text
- Source evidence: homepage/category/detail/player checked
- Playback: callback link verified
- Playback: not runtime-proven, resolver aligned with source evidence
```

When runtime playback was not directly tested, use:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source.
```

If HAR/log was used, use:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source/HAR.
```

---

## 8. Reference / Upstream Credit

When public upstream/reference behavior materially helps a fix, credit it professionally when verifiable.

Acceptable wording:

```text
- Credit: <project/user/source>
- Align provider flow with active source behavior
- Update resolver from verified source evidence
```

Avoid wording that makes the change sound like blind copy-paste or hides the technical reason.

Do not credit unverified references.

---

## 9. Avoid Overclaiming

Do not write commit messages that claim:

- Build success without Gradle or Actions proof.
- Playback success without callback links.
- `repo.json` or `plugins.json` validity without checking.
- Runtime behavior from syntax checks only.
- Actions success from local build only.

Use honest status labels instead.

---

## 10. Final Status Template

For important fixes, include or report:

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

## Maintainer Rule

A commit message should make review easier.

Keep it specific, honest, and tied to evidence.
