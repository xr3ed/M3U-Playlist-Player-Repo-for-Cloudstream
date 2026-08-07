# Smart Health Check Guide

Smart health check is the second monitoring layer after basic domain checks. It uses explicit sample search and detail URLs to detect early provider breakage signals without running CloudStream runtime playback.

This layer is still read-only. It must not edit provider code, metadata, workflow output, or provider versions.

## What smart health check can detect

- Search URL does not respond.
- Search response is empty or too small.
- Search response no longer contains expected keywords.
- Detail URL does not respond.
- Detail response is empty or too small.
- Detail response no longer contains expected keywords.
- Blocking signals such as challenge pages or forbidden responses.
- Redirects that may need maintainer review.

## What smart health check cannot prove

Smart health check cannot prove:

- CloudStream homepage category cards are valid.
- CloudStream `search()` returns proper `SearchResponse` objects.
- CloudStream `load()` returns correct episodes or playable movie data.
- CloudStream `loadLinks()` emits callback video links.
- Playback works on real devices.

Use this interpretation:

```text
Domain UP != parser OK
Search OK != load OK
Detail OK != playback OK
Playback still requires runtime callback proof
```

## Config model

Smart health checks should use explicit provider samples. Do not guess search paths globally for all providers.

Recommended fields:

```json
{
  "name": "ExampleProvider",
  "mainUrl": "https://example.com",
  "enabled": true,
  "sampleSearch": "anime",
  "searchUrl": "https://example.com/?s={query}",
  "sampleDetail": "https://example.com/detail/example-title",
  "expectedKeywords": ["example"]
}
```

The JSON schema lives at:

```text
healthcheck/providers.schema.json
```

A sample config lives at:

```text
healthcheck/providers.sample.json
```

## Local commands

Extract provider `mainUrl` candidates from Kotlin files:

```bash
python scripts/extract_provider_mainurls.py --json-output healthcheck/generated-providers.json
```

Run search-level checks:

```bash
python scripts/health_check_search.py \
  --config healthcheck/providers.sample.json \
  --json-output smart-search-report.json \
  --markdown-output smart-search-report.md
```

Run detail-level checks:

```bash
python scripts/health_check_detail.py \
  --config healthcheck/providers.sample.json \
  --json-output smart-detail-report.json \
  --markdown-output smart-detail-report.md
```

## Workflow behavior

The workflow lives at:

```text
.github/workflows/smart-health-check.yml
```

Initial behavior should remain conservative:

- manual dispatch;
- weekly schedule;
- artifact reports only;
- no auto-fix;
- no auto-commit;
- no automatic provider version bump;
- no playback claims.

## Maintainer rules

- Treat reports as early warning signals.
- Confirm provider issues with active source evidence before patching.
- Keep provider fixes scoped to the affected provider.
- Runtime playback still needs CloudStream/device proof.
- If runtime playback is not proven, report it honestly.
