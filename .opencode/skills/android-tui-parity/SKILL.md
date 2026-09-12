---
name: android-tui-parity
description: Refactor the Android module to modern Kotlin and clean architecture without changing product behavior. Use when the user asks to clean android folder, modernize Kotlin, refactor Android app, Compose rewrite of existing screens, or prepare the app for a later Terminal mode toggle.
license: MIT
compatibility: opencode
---

# Android Kotlin modernize

## Goal

Clean the existing Android code. Same features. Better structure.

## Do

- Map current android/ (or app/) packages, Gradle, DI, navigation, and data layer first.
- Move toward feature + core packages. Extract modules only if the tree is already large.
- ViewModels expose StateFlow of immutable UiState. One-off events via SharedFlow or Channel.
- Replace callbacks-in-composables business logic with ViewModel intents.
- Prefer sealed interfaces, exhaustive when, named parameters, default args.
- Centralize theme and spacing. Kill duplicated colors/strings in composables.
- Keep Gradle aligned with a version catalog if one exists or add one if versions are scattered.
- Preserve public product behavior and existing resource names users would notice.

## Do not

- Do not add Terminal mode in this pass unless the user asks in the same prompt.
- Do not migrate architecture style (MVVM to MVI, Hilt to Koin) unless the current setup is broken.
- Do not rewrite working screens from scratch when a move + extract is enough.
- Do not introduce new libraries without a reason already present in the repo.

## Project fit (cliamp-mobile)

- Stack is `android/` only: Kotlin 2.3, AGP 9.0.0, Compose BOM 2025.09,
  Media3, Room, DataStore, Navigation Compose, Coroutines, Gradle wrapper.
  `ios/` and `desktop/` are empty placeholders.
- Directly applicable upstream skills: `compose-*`, `kotlin-api-design`,
  `kotlin-concurrency-and-flow`, `kotlin-control-flow`, `gradle-run`.
- Do not apply here: `kotlin-backend-jpa-entity-mapping` (Room, not JPA),
  Kotlin Toolchain skills (Gradle project), `kotlin-tooling-java-to-kotlin`
  (no Java sources), `kotlin-tooling-cocoapods-spm-migration` and
  `kotlin-tooling-native-build-performance` (no KMP iOS target),
  `kotlin-tooling-agp9-migration` (already on AGP 9),
  `release-kotlin-library` (proprietary APK app, releases via
  `.github/workflows/build.yml` + `release.yml`), `android-benchmark-comparison`
  (no benchmarks in repo).
- UI changes must follow `docs/design.md`: 32-role palette contract, Poppins
  for text with JetBrains Mono for numeric readouts only, 22dp gutter,
  rows and hairlines before cards, `MechKey` geometry, brick meter behavior.
  Generic `frontend-design` direction that conflicts with that contract loses.

## Order

1. Inventory and constraints
2. Gradle / package skeleton
3. core models and theme
4. data layer cleanup
5. screen-by-screen ViewModel + Compose
6. delete dead code
7. summarize what changed and what was left
