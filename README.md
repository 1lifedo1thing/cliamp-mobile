# cliamp

Terminal soul, native body. A radio and music player for
[cliamp radio](https://radio.cliamp.stream), the 53,000-station
[Radio Browser](https://www.radio-browser.info/) directory, and the servers you
host yourself.

One platform per directory. There is no build at the root: each client builds on
its own terms with its own toolchain, and nothing at the top level has to know
about any of them.

| Directory | What is in it |
| --- | --- |
| [`android/`](android) | The Android app. Kotlin, Compose, Media3. Shipping; its README is the one with build instructions. |
| [`ios/`](ios) | Nothing yet. |
| [`desktop/`](desktop) | Nothing yet. |
| [`docs/`](docs) | [`design.md`](docs/design.md), the system the clients are built to, read out of the Android build; [`concept.md`](docs/concept.md) and the artboards it came from. |

`LICENSE`, `THIRD-PARTY-NOTICES.md` and `licenses/` cover the repository as a
whole and stay here.

## Why one repo

The clients will share a design system, a palette set and a vocabulary of
screens — not code. Nothing about Compose survives a trip to Swift. What is
worth keeping together is [`docs/`](docs), one issue tracker, and one place to
notice when a client grows something the others should have too.
