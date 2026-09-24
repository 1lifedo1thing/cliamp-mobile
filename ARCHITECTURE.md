# Architecture

**Status:** experimental until `v0.9.0`. Package layout, public APIs, and internals can still change without warning.

Before `v0.9.0`, the priority is simplifying things — then freezing them. Nothing about the current structure is guaranteed to stick around.

## Stability

| Phase | Policy |
| --- | --- |
| Now → `v0.9.0` | Unstable. No breaking-change policy. |
| `v0.9.0` | Freeze: module boundaries and public APIs lock in. |
| After `v0.9.0` | Breaking changes need a major version bump, a deprecation period, and a migration note. |

**Frozen** means:

- Module boundaries stay put.
- Public APIs don't get renamed, removed, or changed in incompatible ways without a major bump.
- Internals can still shift around.

**Public** means anything imported across module boundaries. Everything else counts as internal.

## Principles

- Reach for the obvious function before reaching for a new abstraction.
- Keep control flow explicit — no hidden wiring, reflection tricks, or DSLs that obscure what's actually running.
- One responsibility per module. No layers for use cases that don't exist yet.
- Nothing sticks around just because it's already there. Structure gets rewritten until it's actually clear.

## Contributing before `v0.9.0`

Expect churn. If you're restructuring things, aim to make the code smaller and more direct — not more general.

## After `v0.9.0`

- Add features by extending existing modules or adding new ones. Don't break a frozen boundary just to make something feel cleaner.
- Simplicity is still the bar. A change that brings back indirection isn't okay just because it compiles.
- Deprecate before you remove.
- Only generalize once there's a second real use case.
- Whether you wrote it by hand, generated it with AI, or had an agent help out — it's all good. Same review process for everyone.
