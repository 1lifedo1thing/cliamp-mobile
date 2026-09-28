# OpenCode

This repo is the **Android app**. The player UI lives in **cliamp**, a separate Go project.

## Source map

| Tree                                                | Role                                       |
| --------------------------------------------------- | ------------------------------------------ |
| this repository                                     | Kotlin / Compose Android client            |
| [bjarneo/cliamp](https://github.com/bjarneo/cliamp) | TUI source of truth (Bubbletea, Lip Gloss) |

Find cliamp without machine-specific paths:

1. `../cliamp`
2. `$CLIAMP_SRC` if set
3. clone from GitHub if the agent must read TUI code and neither path exists

Do not write home directories into this file or into skills.

cliamp layout that matters: `main.go`, `ui/`, `ui/model/`.

## Skills

One local skill owns product work. Everything else is generic language guidance.

**Local** (`.opencode/skills/`)

| Skill                      | Use when                            |
| -------------------------- | ----------------------------------- |
| `android-kotlin-modernize` | Cleaning `android/`. Same behavior. |

**Kotlin / Compose** (`.agents/skills/`) — this app is Kotlin 2.3, AGP 9, Compose:

`using-chrisbanes-skills`, `compose-state-and-effects`, `compose-component-design`, `compose-performance`, `compose-animations`, `compose-focus-navigation`, `compose-ui-testing-patterns`, `kotlin-api-design`, `kotlin-concurrency-and-flow`, `kotlin-control-flow`, `gradle-run`

**Go** — cliamp is Go. Load only when reading that source:

`go-skills-router`, `go-coding-standards`, `go-cli`, `go-architecture-review`, `go-concurrency-review`

Do not add git plugin entries to `opencode.jsonc`.

## Routing

**Android cleanup**  
Load `android-kotlin-modernize` plus the Kotlin/Compose set. Do not edit cliamp.

## Commits

Every commit must have a description body, not just a subject line.
Subject: `area: what changed (#n)` (50 chars or less). Body: what was wrong,
what the fix does, and how it was verified (build, tests, emulator/phone).
