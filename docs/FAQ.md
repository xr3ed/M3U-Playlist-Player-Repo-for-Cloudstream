# FAQ

Frequently asked questions for **BetbetMiro Extension** contributors, users, and maintainers.

---

## Is this repository a media host?

No.

This repository contains CloudStream provider code and metadata. It does not host movies, anime, series, videos, subtitles, or streams.

---

## Does a successful Gradle build prove playback works?

No.

Gradle success proves the project built successfully. Playback must still be checked through CloudStream runtime behavior or source/HAR evidence.

Use separate status lines:

```text
Gradle build lokal: SUCCESS / failed / not run
Playback callback link > 0: proven / not proven / not applicable
```

---

## Does a green GitHub Actions run prove provider runtime works?

No.

GitHub Actions can prove workflow/build success, but it does not automatically prove CloudStream app behavior.

Runtime status should be reported separately.

---

## When should a provider version be bumped?

Bump the changed provider version whenever provider code changes.

Documentation-only changes do not require provider version bumps.

---

## What is the minimum proof for a provider fix?

For a mature provider fix, report:

```text
Homepage category cards: proven / not proven / not applicable
Load detail/episode: proven / not proven / not applicable
Playback callback link > 0: proven / not proven / not applicable
Gradle build lokal: SUCCESS / failed / not run
GitHub Actions: success / failed / not run / not verified
```

Do not claim a status that was not actually verified.

---

## What should I include in a broken provider issue?

Include:

- Provider name.
- Source/detail URL.
- What fails: homepage, category, search, detail, episode, playback, subtitle, or metadata.
- CloudStream version/channel.
- Device and Android version.
- Logs, screenshots, or HAR when relevant.
- Whether VPN/region/network may affect the result.

Use the broken provider issue template when available.

---

## Can I submit a new provider skeleton?

A skeleton is not enough for a complete provider PR.

A new provider should include real source evidence and working implementation for the available flow:

- Homepage/category.
- Search when available.
- Detail/load.
- Episode or movie playable item.
- Playback/loadLinks where possible.

If playback is not proven, say so clearly.

---

## Can documentation be changed without running Gradle?

Yes, if the change is documentation-only.

Use honest wording:

```text
Gradle build lokal: not run, documentation only
repo.json: not touched
plugins.json: not touched
Provider runtime: not affected
```

---

## What if evidence is incomplete?

Stop and state what evidence is missing.

Do not patch selectors, endpoints, headers, extractors, or fallback behavior by guessing.

---

## What if a source domain changes?

Check the active source domain, redirects, homepage/category/detail pages, and player/media domain.

A domain update alone does not prove playback.

---

## How should PRs be reviewed?

Review PRs against:

- Scope.
- Evidence.
- Root cause.
- Version bump.
- Build status.
- Metadata impact.
- Runtime claims.

See `PR_REVIEW_GUIDE.md` for the full checklist.

---

## Maintainer Rule

When unsure, report uncertainty honestly.

Provider work should be evidence-based, scoped, and validated without overclaiming.
