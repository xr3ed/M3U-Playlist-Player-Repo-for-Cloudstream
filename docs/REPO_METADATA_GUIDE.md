# Repository Metadata Guide

This guide explains how to handle repository metadata for **BetbetMiro Extension**, especially `repo.json`, `plugins.json`, provider build metadata, and generated plugin outputs.

Use this guide when a change affects plugin publishing, repository installation, or generated metadata.

---

## 1. Metadata Priority

Repository metadata is important after provider behavior and build health.

Priority order:

1. Provider works.
2. Gradle build succeeds.
3. `repo.json` and `plugins.json` are valid.
4. GitHub Actions succeeds.
5. Documentation and cosmetics.

Do not break provider code to make metadata look nicer.

---

## 2. repo.json

`repo.json` is the repository-level entry point used by CloudStream to discover the extension repository.

When checking `repo.json`, verify:

- JSON syntax is valid.
- Repository name is correct.
- Plugin list URL points to the expected `plugins.json` location.
- URLs use the correct branch or release path.
- Public access works when expected.

Use honest status wording:

```text
repo.json: valid / invalid / belum dicek / tidak disentuh
```

Do not claim `repo.json` is valid unless it was checked.

---

## 3. plugins.json

`plugins.json` contains plugin/provider metadata generated or published by the build workflow.

When checking `plugins.json`, verify:

- JSON syntax is valid.
- Changed provider appears when expected.
- Version reflects the provider `build.gradle.kts` version.
- Plugin download URL is correct.
- Icon URL is valid when used.
- Provider status is correct.
- Artifact path matches the generated output.

Use honest status wording:

```text
plugins.json: valid / invalid / belum dicek / tidak disentuh
```

Do not claim `plugins.json` is valid unless it was checked.

---

## 4. Provider build.gradle.kts Metadata

Provider metadata usually lives in the provider module `build.gradle.kts`.

Check:

- Provider name.
- Version.
- Status.
- Icon URL.
- Description or repository URL when used.
- Correct plugin ID/module configuration.

For provider code changes, bump the provider version.

For documentation-only changes, no provider version bump is required.

---

## 5. Provider Status

Provider status should represent actual provider condition.

Recommended status logic:

- Alive/provider works: active status.
- Known broken: broken/disabled status only when evidence proves it.
- Unknown: do not make broad claims without testing.

If the user specifically requires provider status `1`, preserve that convention unless source evidence proves a different repository rule.

---

## 6. Generated Artifacts

When builds generate plugin artifacts, check:

- Artifact file exists.
- Artifact name matches metadata.
- Artifact is uploaded by the workflow when expected.
- Artifact URL in metadata points to the correct file.
- Changed provider output was regenerated.

Do not claim artifact availability without checking build output or workflow artifacts.

---

## 7. GitHub Actions Relationship

GitHub Actions may build and publish metadata.

A successful workflow can support claims such as:

```text
GitHub Actions: sukses
```

But it does not automatically prove runtime provider behavior.

Keep runtime status separate:

```text
Homepage category cards: terbukti / belum terbukti / tidak relevan
Load detail/episode: terbukti / belum terbukti / tidak relevan
Playback callback link > 0: terbukti / belum terbukti / tidak relevan
```

---

## 8. Manual ZIP / No Commit Tasks

When the user asks for ZIP/manual-push output:

- Do not push.
- Do not claim commit created.
- Include ZIP/patch link only if actually created.
- Report metadata status honestly.

Use wording:

```text
Commit/PR: belum dibuat
repo.json: tidak disentuh / belum dicek
plugins.json: tidak disentuh / belum dicek
```

---

## 9. Common Metadata Problems

Common issues:

- Invalid JSON comma or quote.
- Version not bumped after provider code change.
- Plugin URL points to old artifact.
- Icon URL points to dead domain.
- Provider included in Gradle but missing from generated metadata.
- Provider output exists but metadata points to wrong path.
- Branch mismatch between README install URL and actual metadata.

Fix only the specific proven metadata issue.

---

## 10. Metadata Report Template

Use this template when reporting metadata work:

```text
Changed files:
- ...

Provider version bump: yes / no / not applicable
Gradle build lokal: SUCCESS / failed / not run
repo.json: valid / invalid / not checked / not touched
plugins.json: valid / invalid / not checked / not touched
GitHub Actions: success / failed / not run / not verified
Artifacts: present / missing / not checked / not applicable
Provider runtime: proven / not proven / not applicable
File lain tidak disentuh: yes / no
```

---

## Maintainer Rule

Metadata claims must be traceable.

If metadata was not checked, mark it as not checked.
