---
name: android-kotlin-modernize
description: Refactor the Android module to modern Kotlin and clean architecture without changing product behavior. Use when cleaning the android folder, modernizing Kotlin, or refactoring the Android app.
license: MIT
compatibility: opencode
---

# Android Kotlin modernize

Clean android/ only. Same behavior.

- Inventory modules, screens, ViewModels, repos, DI, Gradle.
- Feature + core packages. Do not explode modules unless the tree is already large.
- ViewModel + immutable UiState + onEvent. Compose stays dumb.
- Flow / StateFlow. No GlobalScope.
- Sealed interfaces. Kill dead code.
- Keep Hilt/Koin and MVVM/MVI as they are unless broken.
