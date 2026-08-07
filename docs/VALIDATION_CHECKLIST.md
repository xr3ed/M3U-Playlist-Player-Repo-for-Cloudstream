# Validation Checklist

This checklist is used to validate CloudStream provider work in **BetbetMiro Extension**.

Use it before claiming that a provider, build, repository metadata, or release is fixed.

---

## 1. Scope Check

Before validating, confirm the scope:

```text
Target provider/module: ...
Requested change: fix / new provider / docs / build / repo metadata / workflow
Files changed: ...
Unrelated files touched: yes / no
```

Provider fixes should only touch requested or proven-broken areas.

---

## 2. Source Evidence

Collect active source evidence before patching provider logic.

Minimum useful evidence:

- Active homepage URL.
- Category/genre URL.
- Search URL or API endpoint when supported.
- Detail page URL.
- Episode or movie play URL.
- Player/iframe/API/media-player/script URL when playback is involved.
- Direct media or subtitle URL when available.
- Required headers such as referer, origin, cookie, or user agent.

Do not add fallback hosts, selectors, parsers, or extractors without evidence.

---

## 3. Homepage / MainPage Validation

Check CloudStream homepage/main page behavior:

```text
Homepage category cards: proven / not proven
```

A valid homepage should have:

- Category sections visible.
- Cards populated.
- Card title present.
- Poster present when available.
- Detail URL correct.
- Pagination/load-more working when supported.

If homepage is empty, validate source page and selectors before touching playback.

---

## 4. Category / Genre Validation

Check category behavior:

```text
Category pages: proven / not proven / not supported
```

A valid category should have:

- Correct category URL.
- Non-empty card list.
- Correct detail URLs.
- Pagination behavior checked when available.

If source categories changed, update categories from active source evidence.

---

## 5. Search Validation

Check search only when the provider/source supports search:

```text
Search: proven / not proven / not supported
```

A valid search should have:

- Correct endpoint or query URL.
- Query encoded correctly.
- Non-empty result for known searchable title.
- Correct detail URL from result card.

Search is important, but homepage/detail/playback usually has higher survival priority.

---

## 6. Detail / Load Validation

Check detail page behavior:

```text
Load detail/episode: proven / not proven
```

A valid detail page should have:

- No crash.
- Correct title.
- Poster when available.
- Synopsis/tags/year/status when available.
- Episode list for series/anime/drama.
- Movie play item for movie source.
- Correct episode/movie data URL.

If homepage cards open but detail fails, inspect detail URL and active source response.

---

## 7. Playback / loadLinks Validation

Playback is proven only when `loadLinks()` emits at least one video callback link.

```text
Playback callback link > 0: proven / not proven
```

Trace the full flow:

```text
detail -> episode/movie item -> player -> iframe/API/script -> direct media/subtitle
```

Check:

- Player page URL.
- Iframe or embed URL.
- Server selection behavior.
- Required referer/origin/cookie/user-agent.
- API token or nonce when used.
- Packed JavaScript or media-player script when used.
- Direct `.m3u8` or `.mp4` URL.
- Subtitle callback when available.

Returning `true` from `loadLinks()` is not enough.

---

## 8. Build Validation

For provider/code changes, run Gradle when possible:

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

Do not claim build green unless the build completed successfully.

---

## 9. Provider Version Bump

If provider code changed, check version bump:

```text
Provider version bump: yes / no / not applicable
```

Rules:

- Provider code change: bump version.
- Documentation-only change: no provider version bump needed.
- Multiple providers changed: bump each changed provider.

---

## 10. repo.json / plugins.json Validation

Check repository metadata when build, publishing, or plugin output may be affected.

```text
repo.json: valid / invalid / not checked / not touched
plugins.json: valid / invalid / not checked / not touched
```

Do not claim valid unless checked.

---

## 11. GitHub Actions Validation

Remote workflow status should be explicit:

```text
GitHub Actions: success / failed / not run / not verified
```

Do not claim Actions success unless the run was verified.

---

## 12. Documentation-Only Validation

For documentation-only work, use:

```text
Gradle build lokal: not run, documentation only
repo.json: not touched
plugins.json: not touched
Provider runtime: not affected
```

Do not modify provider code during docs-only tasks.

---

## 13. Final Status Template

Use this final template after fixes or validation work:

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
Sudah melakukan Crawl Evidence Based ke sumber websitenya: yes / no / not applicable
GitHub Actions: success / failed / not run / not verified
Homepage category cards: proven / not proven / not applicable
Load detail/episode: proven / not proven / not applicable
Playback callback link > 0: proven / not proven / not applicable
Commit/PR: created / not created
File lain tidak disentuh: yes / no
```

---

## 14. Runtime Playback Not Proven Wording

If playback was not directly tested on runtime/device, say:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source.
```

If HAR/log evidence was used, say:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source/HAR.
```

---

## Maintainer Rule

Validation is not a slogan. It is evidence.

If a status was not checked, mark it as not checked.
