This repo holds the clients. The player UI source of truth lives in **cliamp**, a separate Go project.

## Source map

| Tree                                                | Role                                       |
| --------------------------------------------------- | ------------------------------------------ |
| `android/`                                          | Kotlin / Compose Android client            |
| `desktop/`                                          | Rust desktop client                        |
| `ios/`                                              | iOS client                                 |
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

**Go** — cliamp is Go. Load when a question needs checking cliamp (TUI):

`go-skills-router`, `go-coding-standards`, `go-cli`, `go-architecture-review`, `go-concurrency-review`

**Rust / Desktop** (`.agents/skills/`) — the `desktop/` client is Rust 2024:

`rust-desktop`, `gpui-kit`, `gpui-kit-design-guides`, `rust-skills`

Do not add git plugin entries to `opencode.jsonc`.

## Routing

**Android work**  
Load `android-kotlin-modernize` plus the Kotlin/Compose set. Do not edit cliamp.

**cliamp (TUI) questions**  
Any question that needs checking cliamp loads the Go set first.

**Desktop work**  
Load the Rust/Desktop set (`rust-desktop`, `build-gpui-apps`, `rust-skills`).
`build-gpui-apps` is the UI toolkit.

**Cross-folder info**  
Working in one area but needing info from another loads that area's
skills too: e.g. desktop work that references the Android client loads
the Android set, Android work that checks TUI behavior loads the Go set.

## Commits

Every commit must have a description body, not just a subject line.
Subject: `platform: area: summary` (50 chars or less). Platform comes first:
`android`, `desktop`, or `ios` for client code, `root` for a change at the
repo root (docs, skills, config). Then the area, then the summary. Body: what was wrong, what the fix does, and how it was verified
(build, tests, emulator/phone).
