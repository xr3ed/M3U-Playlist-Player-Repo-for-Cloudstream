# Troubleshooting Guide

This guide helps diagnose common **BetbetMiro Extension** issues without guessing.

Use it for build failures, GitHub Actions failures, repository metadata problems, and CloudStream provider runtime issues.

---

## First Rule

Do not assume the root cause before checking evidence.

For provider issues, collect evidence from:

- CloudStream app behavior.
- Source website pages.
- Logs.
- HAR/network trace when available.
- The current provider parser/resolver code.

For build issues, collect evidence from:

- Gradle output.
- Java version.
- Changed files.
- GitHub Actions logs when applicable.

---

## Gradle Build Fails

Run the wrapper from the repository root:

```bash
./gradlew make
```

Windows:

```bat
.\gradlew.bat make
```

Check:

- Which task failed.
- Which provider/module failed.
- Whether the error is Kotlin syntax, missing import, API mismatch, or Gradle config.
- Java version.
- Whether unrelated files were changed.

Useful command:

```bash
java -version
```

Do not claim build green unless Gradle completed successfully.

---

## GitHub Actions Fails

Check the failed workflow job and logs.

Common causes:

- Java/Gradle mismatch.
- Kotlin compile error.
- Provider module compile error.
- Missing permissions.
- Artifact upload failure.
- Plugin metadata generation failure.
- Branch/output path mismatch.

If Actions was not checked, report:

```text
GitHub Actions: belum diverifikasi
```

---

## repo.json or plugins.json Issues

For repository metadata issues, check:

- JSON syntax validity.
- Expected branch/path.
- Correct plugin list URL.
- Whether plugin artifacts exist.
- Whether changed provider versions are reflected.

Do not claim metadata is valid unless it was actually checked.

Use status wording:

```text
repo.json: valid / invalid / belum dicek
plugins.json: valid / invalid / belum dicek
```

---

## Provider Homepage Empty

If homepage/main page is empty, check:

- Source homepage still opens.
- Category URLs still exist.
- HTML selectors still match.
- Cards have title, poster, and detail URL.
- Pagination/load-more behavior changed.
- Source requires region/VPN/cookies.

Do not jump directly to playback resolver changes when homepage is already broken.

---

## Provider Search Fails

Check:

- Search endpoint URL.
- Query parameter format.
- HTML/API response.
- Empty-result behavior.
- URL encoding.
- Whether source removed search support.

Search is useful, but provider survival priority is usually homepage/detail/playback first.

---

## Detail or Episode Load Fails

Check:

- Detail URL from homepage card.
- Detail page HTML/API response.
- Title/poster extraction.
- Episode list selector.
- Movie play item selector.
- Relative vs absolute URLs.
- JSON-LD/API changes.

A provider card can appear on homepage but still fail if detail URLs changed.

---

## Playback Fails

Playback requires the strongest evidence.

Trace the flow:

```text
detail -> episode/movie item -> player page -> iframe/API/script -> direct m3u8/mp4/subtitle
```

Check:

- Player URL.
- Iframe URL.
- Server list.
- Required headers.
- Referer/origin/cookies.
- JavaScript-packed source.
- API token or nonce.
- HLS/direct media URL.
- Subtitle URL.

A `loadLinks()` function returning `true` is not enough. It must emit at least one callback video link.

Use status wording:

```text
Playback callback link > 0: terbukti / belum terbukti
```

---

## Domain Changed

If a source domain changed:

1. Verify old domain behavior.
2. Verify new domain behavior.
3. Check homepage/category cards.
4. Check detail/load.
5. Check playback if applicable.
6. Update `mainUrl` only with evidence.
7. Bump provider version if provider code changes.

`DomainCheck.py` can help, but it cannot prove full provider runtime behavior.

---

## Source Requires VPN or Region

If source behavior depends on network/location, record:

- Country/region.
- VPN or no VPN.
- DNS provider if relevant.
- Whether the source opens in browser.
- Whether CloudStream receives a different response.

Do not mark a provider dead only from one blocked network response.

---

## Documentation-Only Changes

For docs-only changes, use honest wording:

```text
Gradle build lokal: tidak dijalankan, dokumentasi saja
repo.json: tidak disentuh
plugins.json: tidak disentuh
Provider runtime: tidak terpengaruh
```

Do not modify provider files for documentation-only tasks.

---

## Final Debug Report Template

Use this template for troubleshooting summaries:

```text
Target: provider/repo/build/workflow
Root cause: known / suspected / unknown
Evidence collected: ...
Changed files: ...

Kotlin syntax sanity: OK / gagal / belum dicek
Gradle build lokal: SUCCESS / gagal / belum dijalankan
repo.json: valid / invalid / belum dicek / tidak disentuh
plugins.json: valid / invalid / belum dicek / tidak disentuh
GitHub Actions: sukses / gagal / belum dijalankan / belum diverifikasi
Homepage category cards: terbukti / belum terbukti / tidak relevan
Load detail/episode: terbukti / belum terbukti / tidak relevan
Playback callback link > 0: terbukti / belum terbukti / tidak relevan
Commit/PR: dibuat / belum dibuat
File lain tidak disentuh: ya / tidak
```

---

## Maintainer Rule

Troubleshooting should end with evidence, not assumptions.

If evidence is missing, say what is missing clearly before patching.
