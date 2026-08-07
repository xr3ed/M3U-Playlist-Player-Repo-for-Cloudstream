# Provider Maintenance Guide

This document defines the recommended maintenance standard for providers in **BetbetMiro Extension**.

The goal is simple: keep providers usable, keep the repository buildable, and avoid risky changes that are not supported by source evidence.

---

## Maintenance Priority

Provider maintenance should follow this order:

1. Provider is alive and usable in CloudStream.
2. Gradle build succeeds.
3. `repo.json` and `plugins.json` remain valid.
4. GitHub Actions remain successful.
5. Documentation stays accurate.
6. Cosmetic cleanup only happens when it does not break provider behavior.

If documentation or cosmetic cleanup conflicts with provider behavior, prioritize provider behavior.

---

## CloudStream Behavior Is the Source of Truth

A provider should be judged by how it behaves inside CloudStream, not only by whether the Kotlin parser compiles.

A provider is not considered fully fixed unless the relevant app flow works:

- Homepage or main page loads.
- Categories show usable cards.
- Detail page opens without crashing.
- Episode list or movie play item is available when expected.
- Playback resolver emits at least one video callback link when playback is supported.

A `loadLinks()` function returning `true` is not enough. It must emit a usable video callback link to prove playback resolution.

---

## Evidence-Based Workflow

Before changing provider code, collect evidence from the active source.

Recommended evidence:

- Active source domain.
- Homepage response.
- Category or genre URL sample.
- Search URL/API sample, if supported.
- Detail page sample.
- Episode or movie play item sample.
- Player page.
- Iframe or embed URL.
- API response, media-player script, or direct media URL.
- Subtitle source, if available.
- Required request headers such as referer, origin, cookies, or user agent.

Do not add selectors, fallback domains, extractors, parser branches, or hosts without evidence from the active source.

---

## Standard Provider Fix Flow

A mature provider fix should usually cover these areas in one focused patch:

### 1. Homepage / Main Page

Check that:

- Categories appear.
- Categories contain cards.
- Cards have title.
- Cards have poster when available.
- Cards point to the correct detail URL.
- Pagination or load more works when supported by the source.

### 2. Search

Check that:

- Search query is encoded correctly.
- Search result cards point to valid detail URLs.
- Empty search results are handled safely.
- Search does not crash if the source changes layout.

Search is important, but homepage/load/playback should stay higher priority.

### 3. Detail / Load

Check that:

- Detail page opens without crash.
- Title and poster are parsed safely.
- Episodes are listed for series/anime.
- Movie entries expose a playable item.
- Empty or missing metadata does not crash the provider.

### 4. Playback / loadLinks

Trace the real source flow:

```text
Detail URL -> episode/play item -> player page -> iframe/API/script -> direct media/subtitle
```

Check that:

- URLs passed to extractors are absolute and valid.
- Required headers are included.
- Referer/origin follows source evidence.
- Extractor callback emits at least one video link.
- Subtitles are attached when available.
- Failed hosts do not prevent other hosts from being tried.

Do not claim playback is fixed without callback link evidence.

---

## Version Bump Requirement

Every provider code change must bump the provider version in its `build.gradle.kts` file.

Example:

```kotlin
version = 13
```

Documentation-only changes do not need a provider version bump.

When fixing multiple providers, bump each changed provider version.

---

## Provider Status

Provider status should describe the current expected state.

Recommended meaning:

- `1`: active/usable provider.
- `0` or `3`: disabled, broken, deprecated, or intentionally inactive provider, depending on existing project convention.

Do not mark a provider active if homepage/load/playback are not reasonably usable.

---

## File Scope Rule

Keep patches focused.

Do not touch unrelated providers or unrelated repository files unless the task requires it.

Avoid broad formatting-only changes. They make review harder and can hide provider-specific fixes.

If a fix targets one provider, the changed files should usually stay inside that provider module plus its `build.gradle.kts`.

---

## Build Validation

When possible, run:

```bash
./gradlew make
```

For Windows:

```bat
.\gradlew.bat make
```

If only one module needs validation and the repo supports module-level tasks, a narrower Gradle task may be used.

Do not claim build success unless Gradle actually succeeds.

If the build fails, include the relevant error lines and the module that failed.

---

## Runtime Validation

When runtime/device playback is not directly tested, say so clearly.

Recommended wording:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source.
```

If HAR/log evidence was used:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source/HAR.
```

Do not overclaim playback success.

---

## Domain Changes

When a source changes domain:

1. Verify the old domain redirects or fails.
2. Verify the new domain is active.
3. Check homepage/category.
4. Check detail/load.
5. Check playback flow.
6. Update `mainUrl` only after evidence is clear.
7. Bump provider version.

Domain redirect alone does not guarantee the provider is fully working.

---

## Using DomainCheck.py

`DomainCheck.py` can help detect redirected provider domains and update `mainUrl` values.

Use it carefully.

Recommended use:

- Treat it as a pre-check helper.
- Review its changes before committing.
- Confirm the provider still works in CloudStream behavior terms.
- Do not rely on it as proof of playback or source compatibility.

A dry-run style workflow is safer than blind automatic commits.

---

## New Provider Checklist

A new provider should include:

- Provider module folder.
- `build.gradle.kts` metadata.
- Plugin registration.
- Main provider class.
- Homepage/category support when available.
- Search support when available.
- Detail/load support.
- Episode list or movie play item.
- Playback resolver that emits video callback links.
- Required headers/referer/origin/cookie handling when needed.
- Version set correctly.
- Provider status set correctly.

A skeleton provider without usable load/playback flow should be marked clearly as incomplete or not submitted until mature.

---

## Pull Request Expectations

A good provider PR should explain:

- What provider was changed.
- What was broken.
- What evidence was checked.
- What files changed.
- Whether version was bumped.
- Whether Gradle build passed.
- Whether homepage/load/playback were verified.
- What remains unverified.

The PR should not mix unrelated provider fixes.

---

## Honest Status Labels

Maintainers may use these status labels when reporting a fix:

```text
Kotlin syntax sanity: OK / gagal / belum dicek
Gradle build lokal: SUCCESS / gagal / belum dijalankan
repo.json: valid / invalid / belum dicek
plugins.json: valid / invalid / belum dicek
Sudah melakukan Crawl Evidence Based ke sumber websitenya: ya / tidak
GitHub Actions: sukses / gagal / belum dijalankan / belum diverifikasi
Homepage category cards: terbukti / belum terbukti
Load detail/episode: terbukti / belum terbukti
Playback callback link > 0: terbukti / belum terbukti
Commit/PR: dibuat / belum dibuat
File lain tidak disentuh: ya / tidak
```

Use these labels honestly. Do not claim a status without proof.

---

## Final Rule

Patch with evidence. Validate honestly. Keep scope tight. Do not touch unrelated providers.
