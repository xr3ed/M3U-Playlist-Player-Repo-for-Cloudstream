# Build Guide

This guide explains how to build **BetbetMiro Extension** locally and how to report build status honestly.

Use this guide for local development, provider fixes, new provider work, and build failure triage.

---

## 1. Build Authority

Gradle is the local build authority.

Do not claim build success unless the Gradle command finishes successfully.

Use clear wording:

```text
Gradle build lokal: SUCCESS / gagal / belum dijalankan
```

---

## 2. Requirements

Recommended requirements:

- Git.
- Java compatible with the repository Gradle/Android tooling.
- Repository Gradle wrapper files:
  - `gradlew`
  - `gradlew.bat`
  - `gradle/wrapper/gradle-wrapper.jar`
  - `gradle/wrapper/gradle-wrapper.properties`

Check Java version:

```bash
java -version
```

If Java is missing or incompatible, fix Java before debugging provider code.

---

## 3. Linux / macOS / Termux Build

From the repository root:

```bash
chmod +x gradlew
./gradlew make
```

If permission is already correct, `chmod +x gradlew` is not needed.

Common Termux notes:

- Run from the repository root.
- Make sure storage/repo path is readable.
- Avoid interrupted dependency downloads.
- Re-run after network failure.

---

## 4. Windows Build

From the repository root:

```bat
.\gradlew.bat make
```

If line endings were changed incorrectly, restore wrapper files or check `.gitattributes`.

`gradlew.bat` should use CRLF line endings.

---

## 5. Common Build Failures

### Kotlin syntax error

Usually caused by:

- Missing brace.
- Wrong import.
- Wrong nullable handling.
- API signature mismatch.
- Bad regex escaping.

Report:

```text
Kotlin syntax sanity: gagal
Gradle build lokal: gagal
```

### CloudStream API mismatch

Usually caused by:

- Changed function signature.
- Wrong model type.
- Unsupported helper function.
- Dependency/API version mismatch.

Check nearby working providers before guessing.

### Provider module failure

If one provider fails, identify the exact module/provider from Gradle output.

Do not edit unrelated providers unless the error proves they are affected.

### Metadata generation failure

If build passes but plugin metadata fails, check:

- Provider `build.gradle.kts`.
- Version value.
- Status value.
- Icon/url metadata.
- Generated `plugins.json` output.

---

## 6. Build After Provider Change

For provider code changes, check:

```text
Provider version bump: yes / no
Kotlin syntax sanity: OK / failed / not checked
Gradle build lokal: SUCCESS / failed / not run
```

Every changed provider should receive a version bump in its `build.gradle.kts`.

Documentation-only changes do not need a provider version bump.

---

## 7. Build After Documentation-Only Change

For documentation-only work, it is acceptable to report:

```text
Gradle build lokal: tidak dijalankan, dokumentasi saja
repo.json: tidak disentuh
plugins.json: tidak disentuh
Provider runtime: tidak terpengaruh
```

Do not modify provider files just to justify a docs-only build.

---

## 8. GitHub Actions vs Local Build

Local Gradle and GitHub Actions are different evidence points.

Use both statuses when relevant:

```text
Gradle build lokal: SUCCESS / gagal / belum dijalankan
GitHub Actions: sukses / gagal / belum dijalankan / belum diverifikasi
```

Do not claim Actions success from a local build.

Do not claim local build success from a successful Actions run unless local build was actually run.

---

## 9. Clean Build Notes

If build behavior seems stale, try:

```bash
./gradlew clean
./gradlew make
```

Only use clean when needed. A normal build is usually enough for routine checks.

---

## 10. Build Report Template

Use this template when reporting build results:

```text
Command run: ...
Environment: Linux / macOS / Windows / Termux / GitHub Actions
Java version: ...
Failed task: ...
Failed module/provider: ...

Kotlin syntax sanity: OK / failed / not checked
Gradle build lokal: SUCCESS / failed / not run
repo.json: valid / invalid / not checked / not touched
plugins.json: valid / invalid / not checked / not touched
GitHub Actions: success / failed / not run / not verified
File lain tidak disentuh: yes / no
```

---

## Maintainer Rule

Build status must be evidence-based.

If Gradle was not run, say it was not run.
