# Runtime Testing Guide

This guide explains how to validate CloudStream provider behavior at runtime for **BetbetMiro Extension**.

Use it before claiming that a provider is alive, fixed, playable, or fully working in the CloudStream app.

---

## 1. Runtime Testing Priority

Runtime testing should follow the actual CloudStream flow:

```text
homepage/mainPage -> category/search -> detail/load -> episode/movie item -> loadLinks/playback
```

A provider is not fully validated if only one layer is checked.

---

## 2. Test Environment Notes

Record the testing context:

```text
CloudStream version/channel: ...
Device/Android version: ...
Network/VPN/region: ...
Provider build/version: ...
Source URL tested: ...
```

Network, VPN, region, DNS, cookies, and Cloudflare/security pages may affect results.

---

## 3. Homepage / MainPage Runtime Test

Check:

```text
Homepage category cards: terbukti / belum terbukti
```

Runtime homepage is proven when:

- Provider opens without crash.
- Category rows are visible.
- Rows contain cards.
- Cards have titles.
- Posters load when available.
- Card detail URLs open the correct detail page.
- Pagination/load-more works when supported.

If categories appear but cards are empty, homepage is not proven.

---

## 4. Category Runtime Test

Check:

```text
Category pages: terbukti / belum terbukti / tidak didukung
```

Category is proven when:

- Category opens without crash.
- Cards are populated.
- Detail URLs are correct.
- Pagination works when supported.

If category output is empty, compare with active source category page before patching.

---

## 5. Search Runtime Test

Check:

```text
Search: terbukti / belum terbukti / tidak didukung
```

Search is proven when:

- Known title query returns results.
- Results have correct titles.
- Results open correct detail pages.
- Empty search does not crash.

Do not mark provider dead only because search fails if homepage/detail/playback are still alive.

---

## 6. Detail / Load Runtime Test

Check:

```text
Load detail/episode: terbukti / belum terbukti
```

Detail/load is proven when:

- Detail opens without crash.
- Title is correct.
- Poster is correct when available.
- Synopsis/metadata are reasonable when available.
- Series/anime/drama has episode list.
- Movie source has a playable item.
- Episode/movie item carries enough data for `loadLinks()`.

If detail opens but has no episode or play item, playback cannot be validated yet.

---

## 7. Playback / loadLinks Runtime Test

Playback is proven only when at least one video callback link is emitted and usable by CloudStream.

Check:

```text
Playback callback link > 0: terbukti / belum terbukti
```

Trace:

```text
detail -> episode/movie item -> player -> iframe/API/script -> direct media/subtitle -> callback
```

Runtime playback should verify:

- `loadLinks()` is called with correct data.
- Player/iframe/API opens.
- Required headers are correct.
- Direct `.m3u8` or `.mp4` is resolved.
- At least one callback video link is emitted.
- Playback starts or reaches a usable stream selection.

Returning `true` from `loadLinks()` is not proof.

---

## 8. Subtitle Runtime Test

Subtitle is proven when:

```text
Subtitle callback: terbukti / belum terbukti / tidak tersedia
```

Check:

- Subtitle URL exists.
- Language label is correct when available.
- Subtitle format is supported.
- Subtitle callback is emitted without breaking video links.

Subtitle failure should not hide whether video playback itself is working.

---

## 9. Error Types To Record

When runtime fails, record the failure type:

- App crash.
- Empty homepage.
- Empty category.
- Search empty.
- Detail load failure.
- No episode list.
- No movie play item.
- `loadLinks()` false.
- `loadLinks()` true but callback 0 link.
- Iframe/API blocked.
- Header/referer/origin/cookie issue.
- Token/nonce issue.
- Cloudflare/security block.
- Region/VPN/network block.

Precise failure type makes fixes faster and safer.

---

## 10. Runtime Evidence

Useful evidence:

- Screenshot of homepage/category/detail/player error.
- CloudStream logcat/log output.
- HAR/network trace.
- Tested source URL.
- Tested episode/movie item.
- Player/iframe/API URL.
- Direct media URL when available.

Do not include private cookies/tokens publicly unless sanitized.

---

## 11. Runtime Not Tested Wording

If runtime was not tested, say so.

For provider resolver adjusted from source evidence:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source.
```

For HAR/log-backed resolver adjustment:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source/HAR.
```

Do not claim runtime success from parser review or Kotlin syntax sanity alone.

---

## 12. Runtime Report Template

Use this template:

```text
Provider: ...
Provider version: ...
CloudStream version/channel: ...
Device/Android: ...
Network/VPN/region: ...
Tested URLs: ...

Homepage category cards: terbukti / belum terbukti / tidak relevan
Category pages: terbukti / belum terbukti / tidak didukung
Search: terbukti / belum terbukti / tidak didukung
Load detail/episode: terbukti / belum terbukti / tidak relevan
Playback callback link > 0: terbukti / belum terbukti / tidak relevan
Subtitle callback: terbukti / belum terbukti / tidak tersedia

Failure type: ...
Evidence: screenshot/log/HAR/source URL
Notes: ...
```

---

## Maintainer Rule

Runtime claims must come from runtime evidence.

If runtime was not tested, mark it as not tested.
