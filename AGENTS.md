# AGENTS Guide for DroneController

This document is for coding agents working in this repository.
Follow it as the default operating guide for edits, validation, and style.

## Project Snapshot

- Project type: Android app (single module `:app`)
- Build system: Gradle Kotlin DSL (`build.gradle.kts`)
- Language: Kotlin + Android XML resources
- Namespace / app id: `com.example.dronecontroller`
- Java/Kotlin target: Java 11 (`sourceCompatibility`/`targetCompatibility`)
- Compile SDK / target SDK: 36

## Repository Layout

- Root build files:
  - `settings.gradle.kts`
  - `build.gradle.kts`
  - `gradle/libs.versions.toml`
  - `gradle.properties`
- App module:
  - `app/build.gradle.kts`
  - `app/src/main/java/...`
  - `app/src/main/res/...`
  - `app/src/test/...` (local unit tests)
  - `app/src/androidTest/...` (instrumented tests)

## Environment Assumptions

- Run commands from repo root.
- Use the wrapper: `./gradlew` (Unix/macOS) or `gradlew.bat` (Windows).
- For instrumented tests, ensure at least one emulator/device is connected.
- Do not assume CI-only tools are present unless added to Gradle first.

## Build Commands

- Clean build artifacts:
  - `./gradlew clean`
- Build everything for app module (compile + unit tests + lint checks via `build`):
  - `./gradlew :app:build`
- Assemble debug APK:
  - `./gradlew :app:assembleDebug`
- Assemble release APK:
  - `./gradlew :app:assembleRelease`
- Install debug build on connected device:
  - `./gradlew :app:installDebug`

## Lint Commands

- Run default lint task:
  - `./gradlew :app:lint`
- Run debug lint only:
  - `./gradlew :app:lintDebug`
- Run release lint only:
  - `./gradlew :app:lintRelease`
- Apply safe lint auto-fixes:
  - `./gradlew :app:lintFix`

## Test Commands

### Local Unit Tests (JVM)

- Run all unit tests:
  - `./gradlew :app:test`
- Run debug unit tests:
  - `./gradlew :app:testDebugUnitTest`
- Run a single unit test class:
  - `./gradlew :app:testDebugUnitTest --tests "com.example.dronecontroller.ExampleUnitTest"`
- Run a single unit test method:
  - `./gradlew :app:testDebugUnitTest --tests "com.example.dronecontroller.ExampleUnitTest.addition_isCorrect"`

### Instrumented Tests (device/emulator)

- Run all connected debug instrumented tests:
  - `./gradlew :app:connectedDebugAndroidTest`
- Run a single instrumented test class:
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.dronecontroller.ExampleInstrumentedTest`
- Run a single instrumented test method:
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.dronecontroller.ExampleInstrumentedTest.useAppContext`

## Fast Validation Recipes

- For Kotlin-only logic changes (no resources/manifest):
  - `./gradlew :app:testDebugUnitTest`
- For UI/resource/manifest changes:
  - `./gradlew :app:lintDebug :app:assembleDebug`
- Before final handoff:
  - `./gradlew :app:lint :app:testDebugUnitTest :app:assembleDebug`

## Coding Style Baseline

Use Kotlin official style. The repo explicitly sets:

- `kotlin.code.style=official` in `gradle.properties`
- IntelliJ project code style defaults to `KOTLIN_OFFICIAL`

### Kotlin Formatting

- Use 4-space indentation; no tabs.
- Keep lines readable; wrap long argument lists one-per-line.
- Prefer trailing commas in multiline Kotlin constructs when helpful.
- Keep one top-level class/object per file unless tightly related.
- Avoid wildcard imports.

### Imports

- Group imports in standard Kotlin/IDE order.
- Remove unused imports.
- Prefer explicit imports over fully qualified names in code.
- Use `import org.junit.Assert.assertEquals`-style selective imports for tests when it improves clarity.

### Naming Conventions

- Types (`class`, `object`, `interface`, `enum`): `PascalCase`.
- Functions/variables/properties: `camelCase`.
- Constants: `UPPER_SNAKE_CASE` in `const val`.
- Resource IDs: `snake_case` with semantic prefixes (`btn_send`, `et_ip`, etc.).
- Test names: behavior-focused, e.g. `sendUdp_whenPortInvalid_returnsEarly`.

### Types and Nullability

- Prefer explicit nullability in APIs (`String?` vs `String`).
- Avoid `!!`; use safe calls, Elvis (`?:`), guard clauses, or `requireNotNull`.
- Add explicit types for public members and non-obvious local values.
- Keep functions small and single-purpose; return typed results where possible.

### Error Handling

- Never swallow exceptions silently.
- Catch specific exceptions where practical (e.g., parsing/network errors) instead of broad `Exception`.
- Surface user-visible failures via UI-safe mechanisms (Toast/Snackbar/state update), not only stack traces.
- Log failures with context (`tag`, operation, inputs excluding secrets).
- Use early returns for invalid user input.

### Concurrency and Threading

- Do not block the main thread for network or I/O.
- Prefer structured concurrency (`kotlinx.coroutines`) for new async work.
- If legacy threads are used, isolate them and handle lifecycle cancellation risks.
- Keep UI updates on main thread only.

### Android and Resource Rules

- Put user-facing text in `res/values/strings.xml` (no hardcoded UI strings in layouts/code).
- Keep manifest, layout, and resource changes minimal and consistent.
- Preserve package/namespace consistency (`com.example.dronecontroller`) unless explicitly migrating.
- Respect existing XML arrangement conventions from project code style.

### Testing Expectations

- Add/update unit tests for non-trivial logic changes.
- Add/update instrumented tests for Android framework/UI behavior changes.
- Keep tests deterministic and independent.
- Use clear Arrange-Act-Assert structure.

## Dependency and Build File Guidelines

- Manage dependency versions via `gradle/libs.versions.toml`.
- In Gradle files, prefer version catalog aliases (`libs.*`) over inline coordinates.
- Keep module/plugin config scoped; avoid unnecessary top-level changes.

## Agent Workflow Expectations

- Read relevant files before editing; do not guess architecture.
- Make the smallest safe change that solves the request.
- Preserve existing behavior unless the task asks to change it.
- After edits, run the narrowest meaningful verification first, then broader checks.
- If tests cannot run (e.g., no device for instrumented tests), state exactly what was not run and why.

## Cursor/Copilot Rule Sources

Checked for additional agent instructions in:

- `.cursor/rules/`
- `.cursorrules`
- `.github/copilot-instructions.md`

Current status: none of these files are present in this repository.

If added later, treat them as high-priority supplements to this guide.
