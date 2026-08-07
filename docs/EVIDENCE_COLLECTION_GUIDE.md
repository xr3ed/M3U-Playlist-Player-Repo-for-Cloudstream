# Evidence Collection Guide

This guide explains how to collect evidence before changing a CloudStream provider in **BetbetMiro Extension**.

Use it for provider fixes, new providers, playback issues, domain changes, category/search/detail failures, and review reports.

---

## 1. Evidence First

Do not patch provider behavior by guessing.

Before changing parser, selector, endpoint, headers, referer, extractor, or playback logic, collect evidence from the active source.

Evidence should answer:

- What is broken?
- Where does the flow fail?
- What does the active source currently return?
- Which provider code path no longer matches the source?
- What is the smallest safe fix?

---

## 2. Evidence Types

Useful evidence includes:

- Active source URL.
- Homepage/category HTML.
- Search result HTML or API response.
- Detail page HTML or API response.
- Episode/player page HTML.
- Iframe URL.
- API request/response.
- Media script or encoded data.
- Direct media URL when visible.
- Subtitle URL when visible.
- HAR/network trace.
- CloudStream logcat/log output.
- Screenshot of app/source failure.

Use more than one evidence type when the issue spans multiple steps.

---

## 3. Minimum Evidence By Issue Type

### Homepage or category issue

Collect:

```text
homepage URL
category URL
sample cards from source
current provider selector/parser
actual provider output symptom
```

### Search issue

Collect:

```text
search URL or API endpoint
sample query
source search response
current provider search parser
actual provider search output symptom
```

### Detail/load issue

Collect:

```text
detail URL
source detail HTML/API
episode/movie item data
current provider load parser
actual provider detail symptom
```

### Playback/loadLinks issue

Collect:

```text
detail URL
episode/movie item URL or data
player URL
iframe/API URL
headers/referer/origin evidence
direct media or encoded media evidence
callback result symptom
```

Returning `true` from `loadLinks()` is not enough evidence of playback success.

### Domain issue

Collect:

```text
old mainUrl
new active mainUrl
redirect behavior
sample homepage/category/detail URL on new domain
whether player/media domain changed
```

---

## 4. HAR Evidence

HAR is strongest when the issue involves:

- Player API.
- Token/nonce.
- Headers.
- Referer/origin.
- Cookie/session requirement.
- Cloudflare/security gate.
- Hidden iframe or script request.

When using HAR, record:

```text
request URL
method
status code
referer
origin
content type
response shape
media URL or iframe URL if visible
```

Sanitize private cookies, tokens, accounts, and personal identifiers before posting publicly.

---

## 5. Logcat / App Logs

Logs are useful for:

- Kotlin exception.
- Parser crash.
- Network error.
- Null/cast error.
- Extractor failure.
- Callback count evidence.

Record:

```text
provider name
provider version
CloudStream version/channel
device/Android version
exact action before failure
relevant log snippet
```

Avoid posting unrelated full device logs.

---

## 6. Screenshot Evidence

Screenshots are useful for:

- Empty homepage.
- Empty category.
- Wrong title/poster.
- Detail page missing episodes.
- Player error.
- Region/security block.

Screenshot evidence alone is often not enough for parser fixes. Pair it with source HTML/API evidence when possible.

---

## 7. Compare Old Provider vs Active Source

Before patching, compare:

```text
old selector/parser/endpoint
active source selector/API/HTML
runtime symptom
minimal fix needed
```

Do not rewrite unrelated provider areas just because one selector changed.

Preserve still-valid behavior.

---

## 8. Evidence Boundaries

Each evidence type proves only what it directly shows.

Examples:

- HTML evidence can prove selectors changed.
- API evidence can prove response fields changed.
- HAR can prove required headers or player request flow.
- Gradle success can prove build success.
- GitHub Actions success can prove workflow success.
- Runtime callback logs can prove callback link count.

Do not claim playback success from homepage HTML.

Do not claim runtime success from Gradle build only.

---

## 9. Evidence Report Template

Use this template:

```text
Provider: ...
Issue type: homepage / category / search / detail / playback / domain / build / metadata
Source URLs checked:
- ...

Evidence collected:
- HTML: yes / no
- API response: yes / no
- HAR: yes / no
- Screenshot: yes / no
- Logcat/app log: yes / no

Root cause category: ...
Old provider behavior: ...
Active source behavior: ...
Minimal fix: ...
Unverified parts: ...
```

---

## 10. Maintainer Rule

Evidence should guide the patch.

If evidence is insufficient, stop and state what is missing instead of guessing.
