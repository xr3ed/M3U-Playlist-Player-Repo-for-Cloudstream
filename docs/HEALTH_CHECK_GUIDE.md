# Health Check Guide

Health check in this repository is a read-only monitoring layer for provider availability. It helps maintainers detect domain, redirect, timeout, and server-response problems before users report them.

This is not a playback proof and must not be treated as a full CloudStream runtime validation.

## Goals

- Detect providers whose `mainUrl` no longer responds.
- Detect redirects and possible domain moves.
- Detect common response states such as `200`, `301`, `302`, `403`, `404`, `5xx`, and timeout.
- Generate JSON and Markdown reports for maintainers.
- Keep checks safe, reviewable, and non-invasive.

## Non-goals

Health check must not:

- modify provider files;
- bump provider versions;
- update `repo.json` or `plugins.json`;
- create commits automatically;
- open issues automatically without maintainer review;
- claim homepage, detail, episode, or playback success;
- bypass evidence-based provider maintenance rules.

## Health check levels

### Level 1: Domain health

Checks whether the provider base URL responds from the runner environment.

Possible result examples:

- `UP`: request returned a usable HTTP response.
- `REDIRECT`: request redirected to a different URL.
- `FORBIDDEN`: response was `403`.
- `NOT_FOUND`: response was `404`.
- `SERVER_ERROR`: response was `5xx`.
- `TIMEOUT`: request did not finish within the configured timeout.
- `ERROR`: request failed before a usable response was returned.

This level is safe and is the first health check layer used by this repository.

### Level 2: Search or listing health

Future layer. It may test whether a provider can still produce search or listing results. This requires provider-specific sample terms and stronger parser awareness.

### Level 3: Detail/load health

Future layer. It may test a known detail URL and verify that a title, poster, or episode list can still be parsed.

### Level 4: Playback/loadLinks health

Future layer. This is the most sensitive layer. It must not claim playback success unless callback video links are proven. Many hosts require headers, referer, origin, cookies, or runtime behavior that a simple GitHub runner cannot fully represent.

## Report interpretation

Domain health results are only network-level signals.

A provider can be:

- domain `UP` but parser broken;
- domain `UP` but search empty;
- domain `UP` but detail/load broken;
- domain `UP` but playback callback link count is zero;
- domain `DOWN` from GitHub runner but reachable from another region or device.

Use these honest labels when interpreting results:

- `Domain health`: checked by health check script.
- `Homepage category cards`: not proven by domain check.
- `Load detail/episode`: not proven by domain check.
- `Playback callback link > 0`: not proven by domain check.

## Config

The example config lives at:

```text
healthcheck/providers.example.json
```

A real config may be created later as:

```text
healthcheck/providers.json
```

The config should remain explicit and reviewable. Do not let an agent generate a large provider list and commit it without maintainer review.

## Local usage

Run a lightweight domain check:

```bash
python scripts/health_check_domains.py --config healthcheck/providers.example.json
```

Write reports:

```bash
python scripts/health_check_domains.py \
  --config healthcheck/providers.example.json \
  --json-output healthcheck-report.json \
  --markdown-output healthcheck-report.md
```

## GitHub Actions usage

The workflow lives at:

```text
.github/workflows/health-check.yml
```

Recommended initial mode:

- manual `workflow_dispatch`;
- weekly schedule;
- upload report artifacts;
- no auto-fix;
- no auto-commit.

## Maintainer rules

- Treat health check as an early warning, not final proof.
- Provider fixes still require source evidence.
- Runtime playback still requires CloudStream behavior validation.
- If playback is not runtime-proven, use the repository's honest status wording.
