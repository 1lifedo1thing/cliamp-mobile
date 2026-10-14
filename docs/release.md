# Release

Push a version tag. GitHub Actions builds the signed APK, writes the changelog,
and publishes the release. No other step is necessary.

## Make a release

1. Make sure `main` holds the commits you want to ship.
2. Create an annotated tag on that commit. The tag body is the user-facing
   highlights: it opens `What's Changed`, above the full commit list the
   workflow always keeps.

   ```sh
   git tag -a v0.5.0 -m "kleeamp 0.5.0" -m "The podcasts, library and visuals release: ..."

   git push origin v0.5.0
   ```

3. Follow the `release` workflow in the Actions tab.

The workflow publishes the release when the APK passes the signature check.

## What the workflow does

- Builds `:app:assembleRelease`. The tag supplies `KLEEAMP_VERSION`. The run
  number supplies `KLEEAMP_VERSION_CODE`, so every release installs over the
  previous one.
- Fails if the APK carries the Android debug key.
- Collects the commits between the previous tag and the new tag.
- Prepends the annotated tag body as the user-facing highlights above the
  commit list. A lightweight tag ships commits only.
- Links each commit to its author on GitHub when the commit email belongs to
  an account. Merge commits are skipped.
- Uploads `kleeamp-<version>.apk` and `checksums.txt`.

## Versions

Use `v` and three numbers, for example `v0.2.0`. A tag with a hyphen is a
prerelease, for example `v0.2.0-beta.1`. The release page marks that tag as a
prerelease.

A stable release compares against the previous stable tag. It skips prerelease
tags, so its notes cover the full beta change set.

## Rebuild a release

Run the `release` workflow by hand and enter a tag that already exists. The
workflow refreshes the notes of that release. Releases here are immutable,
so a rebuild cannot replace the assets: the notes keep the version code
and SHA-256 of the originally uploaded APK.

The repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and
`KEY_PASSWORD` must be set. The pipeline lives in
[`.github/workflows/release.yml`](../.github/workflows/release.yml).
