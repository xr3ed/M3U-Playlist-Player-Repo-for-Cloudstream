# New Provider Guide

This guide explains the expected standard for adding a new CloudStream provider to **BetbetMiro Extension**.

A new provider should be useful, evidence-backed, buildable, and testable. Do not submit an empty skeleton as a finished provider.

---

## 1. Minimum Requirement

A new provider should implement the core CloudStream flow:

```text
homepage/mainPage -> detail/load -> episode or movie play item -> loadLinks/playback
```

Recommended minimum:

- Real active `mainUrl`.
- Real homepage/category entries from the source.
- `getMainPage()` with populated cards.
- `search()` when the source supports search.
- `load()` with valid detail parsing.
- Episode list for series/anime/drama.
- Movie play item for movie-only sources.
- `loadLinks()` connected to the real player flow when playback is possible.
- Version configured in `build.gradle.kts`.

---

## 2. Source Evidence First

Before writing provider code, collect source evidence:

- Homepage URL.
- Category/genre URLs.
- Search URL or API endpoint.
- Detail page URL.
- Episode page or movie play URL.
- Player page URL.
- Iframe/API/media-player/script URL.
- Direct `.m3u8` or `.mp4` URL if available.
- Subtitle URL if available.
- Required headers such as referer, origin, cookie, or user agent.

Do not invent selectors, categories, hosts, or fallback domains without evidence.

---

## 3. Provider Identity

Define clear provider identity:

- Provider name.
- Main URL.
- Language/region.
- Supported content type.
- `hasMainPage` value.
- Supported TV types.

Use names and categories that reflect the source website.

---

## 4. Homepage / MainPage

A provider homepage should not be empty.

Checklist:

```text
Homepage category cards: proven / not proven
```

Expected behavior:

- Categories are real source categories.
- Each category emits cards.
- Cards have title.
- Posters are parsed when available.
- Detail URLs are correct.
- Pagination/load-more works when supported.

If categories appear but cards are empty, the provider is not ready.

---

## 5. Search

Implement search when source supports it.

Checklist:

```text
Search: proven / not proven / not supported
```

Expected behavior:

- Search endpoint is source-backed.
- Query is encoded correctly.
- Results map to correct detail URLs.
- Empty-result behavior does not crash.

Do not fake search using unrelated categories.

---

## 6. Detail / Load

`load()` must produce usable detail data.

Checklist:

```text
Load detail/episode: proven / not proven
```

Expected behavior:

- Detail page opens without crash.
- Title is correct.
- Poster is correct when available.
- Synopsis/metadata are parsed when available.
- Series/anime/drama has episode list.
- Movie has a playable item if no episode list exists.
- Episode/movie data feeds `loadLinks()` correctly.

Do not use random detail links as episodes.

---

## 7. Playback / loadLinks

Playback is proven only when a video callback link is emitted.

Checklist:

```text
Playback callback link > 0: proven / not proven
```

Trace the flow:

```text
detail -> episode/movie item -> player page -> iframe/API/script -> direct media/subtitle -> callback
```

Check:

- Correct player URL.
- Correct referer/origin/cookie/user-agent.
- Server selection.
- Iframe or API extraction.
- Packed JavaScript or token handling when present.
- Direct `.m3u8` or `.mp4` URL.
- Subtitle callback when available.

Returning `true` from `loadLinks()` is not proof of playback.

---

## 8. Version and Build Metadata

A new provider must have valid build metadata.

Check:

- Module directory is correct.
- `build.gradle.kts` exists.
- Provider status is set correctly.
- Initial version follows repository convention.
- Icon URL or metadata is source-appropriate when used.

For future provider code changes, bump the provider version.

---

## 9. Build Validation

Run Gradle when possible:

```bash
./gradlew make
```

Windows:

```bat
.\gradlew.bat make
```

Report honestly:

```text
Kotlin syntax sanity: OK / failed / not checked
Gradle build lokal: SUCCESS / failed / not run
```

Do not claim build success without proof.

---

## 10. Runtime Validation

New provider runtime should be tested through CloudStream when possible.

Record:

```text
Homepage category cards: proven / not proven
Load detail/episode: proven / not proven
Playback callback link > 0: proven / not proven
```

If playback was not directly tested on runtime/device, say:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source.
```

If HAR/log evidence was used, say:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source/HAR.
```

---

## 11. Pull Request Expectations

A new provider Pull Request should include:

- Source name and URL.
- Root source evidence summary.
- What content type is supported.
- Changed files.
- Initial version.
- Homepage validation status.
- Detail/load validation status.
- Playback callback status.
- Gradle build status.
- Known limitations.

Unverified areas must be clearly marked.

---

## 12. What Is Not Accepted as Finished

A new provider is not finished if it only has:

- Empty class skeleton.
- Fake categories.
- Placeholder selectors.
- Hardcoded unrelated URLs.
- `loadLinks()` returning `true` without callback links.
- No detail parser.
- No episode/movie play item.
- No source evidence.
- No version metadata.

Skeletons may be used for local experiments, but should not be presented as complete provider work.

---

## Maintainer Rule

A new provider should be complete enough to be useful and honest enough to be reviewable.

If something is not proven, mark it as not proven.
