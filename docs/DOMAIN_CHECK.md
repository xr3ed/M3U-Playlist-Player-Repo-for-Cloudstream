# DomainCheck.py Guide

`DomainCheck.py` is a helper script for checking provider `mainUrl` domain redirects in **BetbetMiro Extension**.

It is a maintenance helper only. It is not a CloudStream provider, not a runtime dependency, and not proof that a provider is fully working.

---

## Purpose

The script helps detect when a provider source domain redirects to a new domain.

Typical example:

```text
https://old-domain.example
-> redirects to
https://new-domain.example
```

When it detects a changed domain, the script can update the provider `mainUrl` and bump the provider version in `build.gradle.kts`.

---

## What the Script Does

At a high level, `DomainCheck.py`:

1. Scans provider-like folders in the repository root.
2. Looks for Kotlin files matching the folder name pattern.
3. Reads `override var mainUrl = "..."` from the Kotlin provider file.
4. Requests the domain with redirects enabled.
5. Compares the original domain with the final redirected domain.
6. Updates `mainUrl` when a redirect points to a new domain.
7. Bumps the provider version in `build.gradle.kts`.
8. Attempts to update favicon/icon URL domains in Gradle metadata.

---

## What the Script Does Not Do

`DomainCheck.py` does not verify full CloudStream provider behavior.

It does not prove:

- Homepage cards work.
- Categories are populated.
- Search works.
- Detail pages load.
- Episode lists are valid.
- Movie play items are valid.
- `loadLinks()` emits video callback links.
- Subtitles work.
- Headers/referer/origin/cookies are correct.
- The source is usable in every region or network.

A domain redirect is only one piece of evidence.

---

## Safe Usage

Recommended workflow:

1. Run the script locally only when domain changes are suspected.
2. Review every changed file before committing.
3. Confirm that changed providers still compile.
4. Check homepage/category behavior.
5. Check detail/load behavior.
6. Check playback/loadLinks behavior when applicable.
7. Commit only reviewed changes.

Do not run the script and blindly commit every resulting change.

---

## Running the Script

Install the required Python dependency if needed:

```bash
pip install cloudscraper
```

Run from the repository root:

```bash
python3 DomainCheck.py
```

On some systems:

```bash
python DomainCheck.py
```

---

## Review Changes After Running

After running the script, inspect changes carefully:

```bash
git diff
```

Check changed provider files:

```bash
git diff -- ProviderName/
```

Check whether the script bumped versions correctly:

```bash
git diff -- ProviderName/build.gradle.kts
```

---

## Important Limitations

The script uses simple source patterns and may not detect every provider layout.

Known limitations:

- It expects provider folders to contain a Kotlin file matching the folder name.
- It mainly looks for `override var mainUrl = "..."`.
- It may miss providers using `override val mainUrl`, constants, helper objects, or dynamic domain handling.
- It may skip some folders intentionally.
- It may update Gradle URL text too broadly if metadata format differs.
- It cannot validate runtime behavior.
- It cannot replace manual source evidence.

---

## When To Use

Use this script when:

- A source domain is known or suspected to redirect.
- Multiple providers may need domain checks.
- You want a quick starting point before manual validation.

Do not use it as the only validation step for provider fixes.

---

## When Not To Use

Avoid using this script when:

- The provider breakage is caused by player/extractor changes.
- The source layout changed but domain did not.
- Search/detail/playback parsers are broken.
- You do not plan to review the resulting diff.
- You cannot test or at least inspect the changed provider flow.

---

## Required Validation After Domain Updates

After any domain update, validate:

```text
Homepage category cards: proven / not proven
Load detail/episode: proven / not proven
Playback callback link > 0: proven / not proven
Gradle build: SUCCESS / failed / not run
```

If runtime playback is not proven, say:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source.
```

If HAR/log evidence was used, say:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source/HAR.
```

---

## Commit Guidance

Good commit message example:

```text
fix(provider-name): update source domain

Root cause: old source domain redirects to a new active domain.
Fix: update provider mainUrl and Gradle icon metadata.
Validation: homepage/detail/playback status described in PR body.
Version: bumped provider version.
```

Do not mix unrelated provider domain updates unless they were all checked and reviewed.

---

## Maintainer Rule

`DomainCheck.py` is useful as a helper, but the final authority is CloudStream app behavior plus source evidence.

A provider should not be marked fixed only because the domain was updated.
