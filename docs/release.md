# Release

Push a version tag. GitHub Actions builds the signed APK, writes the changelog,
and publishes the release. No other step is necessary.

## Make a release

1. Make sure `main` holds the commits you want to ship.
2. Create the tag on that commit.

   ```sh
   git tag v0.2.0
   git push origin v0.2.0
   ```

3. Follow the `release` workflow in the Actions tab.

The workflow publishes the release when the APK passes the signature check.

## What the workflow does

- Builds `:app:assembleRelease`. The tag supplies `KLEEAMP_VERSION`. The run
  number supplies `KLEEAMP_VERSION_CODE`, so every release installs over the
  previous one.
- Fails if the APK carries the Android debug key.
- Collects the commits between the previous tag and the new tag.
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
workflow replaces the assets and the notes of that release.

The repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and
`KEY_PASSWORD` must be set. The pipeline lives in
[`.github/workflows/release.yml`](../.github/workflows/release.yml).
