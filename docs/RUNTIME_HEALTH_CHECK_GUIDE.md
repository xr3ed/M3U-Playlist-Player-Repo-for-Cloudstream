# Runtime Health Check Guide

Runtime health check is a conservative monitoring layer for player pages, media candidates, and header sensitivity. It is designed to collect early warning signals without claiming CloudStream playback success.

This layer is still read-only. It must not edit provider code, bump provider versions, update metadata, or create automatic fixes.

## What runtime health check can detect

- Player page or episode page can be reached.
- Player page returns empty, blocked, timeout, not found, or server error responses.
- HTML or script responses contain iframe/embed/API/media candidate markers.
- Media candidate strings such as `.m3u8` or `.mp4` are visible in fetched responses.
- A configured request behaves differently with or without referer/origin/user-agent headers.

## What runtime health check cannot prove

Runtime health check cannot prove:

- CloudStream `load()` created correct episode data.
- CloudStream `loadLinks()` emitted callback video links.
- Media candidates are playable on real devices.
- Runtime playback works in the CloudStream app.
- A provider is fully fixed.

Use these strict boundaries:

```text
Player page reachable != playback OK
Media candidate found != callback link proven
Header-sensitive response != loadLinks fixed
GitHub runner result != device runtime proof
```

## Allowed claims

Runtime health check may report:

- `PLAYER_PAGE_OK`
- `IFRAME_CANDIDATE_FOUND`
- `MEDIA_CANDIDATE_FOUND`
- `HEADER_SENSITIVE`
- `PLAYER_PAGE_BLOCKED`
- `PLAYER_PAGE_TIMEOUT`
- `MEDIA_CANDIDATE_NOT_FOUND`

It must not report:

- `Playback callback link > 0: proven`
- `CloudStream playback works`
- `loadLinks fixed`
- `provider fully alive`

## Config model

Runtime checks should use explicit provider samples. Do not run against all providers without reviewed sample URLs.

Recommended fields:

```json
{
  "name": "ExampleProvider",
  "enabled": true,
  "detailUrl": "https://example.com/detail/example-title",
  "episodeUrl": "https://example.com/watch/example-episode",
  "playerUrl": "https://example.com/player/example",
  "referer": "https://example.com/",
  "origin": "https://example.com",
  "expectedHosts": ["example.com"],
  "expectedMediaPatterns": [".m3u8", ".mp4"],
  "blockedKeywords": ["captcha", "access denied"]
}
```

Schema:

```text
healthcheck/runtime-providers.schema.json
```

Sample config:

```text
healthcheck/runtime-providers.sample.json
```

## Local commands

Check configured player pages:

```bash
python scripts/health_check_player_pages.py \
  --config healthcheck/runtime-providers.sample.json \
  --json-output runtime-player-report.json \
  --markdown-output runtime-player-report.md
```

Check visible media candidates:

```bash
python scripts/health_check_media_candidates.py \
  --config healthcheck/runtime-providers.sample.json \
  --json-output runtime-media-report.json \
  --markdown-output runtime-media-report.md
```

Check header sensitivity:

```bash
python scripts/health_check_headers.py \
  --config healthcheck/runtime-providers.sample.json \
  --json-output runtime-headers-report.json \
  --markdown-output runtime-headers-report.md
```

## GitHub Actions

Workflow:

```text
.github/workflows/runtime-health-check.yml
```

Initial behavior:

- manual dispatch;
- optional weekly schedule;
- report artifact only;
- no auto-fix;
- no auto-commit;
- no version bump;
- no playback success claim.

## Maintainer rules

- Treat runtime health check as an evidence helper, not a pass/fail proof.
- Provider fixes still require source evidence and scoped patches.
- Runtime playback still requires CloudStream app/device validation.
- If playback is not proven, use the repository's honest status wording.
