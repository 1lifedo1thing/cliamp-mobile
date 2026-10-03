---
name: rust-desktop
description: >
  Rust workspace rules for the kleeamp desktop client. Use when adding a
  crate, writing a provider, playback, or error type, or reviewing Rust
  that is not UI. Trigger examples: "new crate", "thiserror", "provider
  trait", "playback thread", "no unwrap". Not for gpui-kit views — use
  gpui-kit. Not a substitute for clippy.
user-invocable: true
license: MIT
compatibility: Rust 2024 edition workspace. Requires cargo, clippy, rustfmt.
allowed-tools: Read Edit Write Glob Grep Bash(cargo:*) Bash(rustfmt:*) Bash(rustc:*)
---

# Rust desktop

Four crates. Do not add a fifth until a file is hard to read.

| Crate | May do | May not import |
| --- | --- | --- |
| `core` | types, `QueuePolicy`, `Palette`, `thiserror` | gpui-kit, tokio runtime, sql, http |
| `playback` | cpal, symphonia, fft, eq, command/event channels | gpui-kit |
| `providers` | http, ssh, parsers, return `core` types | gpui-kit |
| `app` | window, views, keymap, composition | decode, raw sockets |

Edition 2024. `rustfmt` on save. `cargo clippy --workspace --all-targets -- -D warnings` before a task is done.

## Errors

Library code returns `Result`. `thiserror` enums with context: `ProviderError::{Unreachable, Auth, HostKeyChanged, Parse}`, `PlaybackError::{DeviceLost, UnsupportedCodec, Stalled}`. Implement `From` so `?` works. No string matching on errors.

`anyhow` only in `main`. `expect` only when the process cannot start (no GPU device), and the message says why. `unwrap` is a review failure outside tests. A background task logs with `tracing` and sends the error to the entity. It does not panic.

Malformed media is not retried. Network and stalls are. Probe a server before saving an account. Secrets go to the OS keychain, never sqlite.

## API shape

Small public surface. Own what you store, borrow what you only read. `QueuePolicy` is plain functions on values: window 60, keep 8 predecessors, shuffle keeps the tapped track first, insert shifts `current_index`. No trait object until a second implementation exists.

Providers share one trait: `probe`, `albums`, `tracks`, `open`. Subsonic auth stays `md5(password + salt)`. SFTP seeks at the remote offset. SSH pins the host key after the first probe.

## Tests

Queue policy, palette parsing, and reconnect backoff are `#[test]` in `core` / `playback`. No gpui-kit in those tests. A pane test exists only when the interaction is the bug.

## Do not

- `block_on` on the UI thread.
- Clone a `String` to satisfy the borrow checker when a `&str` would do, except across a thread boundary.
- Log a token, password, or private key.
- Vendor kleeamp Kotlin. Reimplement from the Android files; `android/` is the behavior spec.
