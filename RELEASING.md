# Releasing

Releases are created by `.github/workflows/release.yml` whenever a `v*` tag is pushed.

The tag must match `versionName` in `app/build.gradle.kts`. For example, `versionName = "1.3.0"` requires tag `v1.3.0`.

Every release contains:

- `speedometer-vX.Y.Z.apk` for a production-signed build, or `speedometer-vX.Y.Z-test.apk` when signing is not configured
- `speedometer-vX.Y.Z-source.zip`
- `speedometer-vX.Y.Z.spdx.json`
- `SHA256SUMS.txt`

GitHub also provides its standard source archives automatically.

## Production Signing

Create a `release-signing` Actions environment, move all five release values into it, and remove any repository-level copies before pushing a production tag:

- `RELEASE_KEYSTORE_BASE64`: base64-encoded JKS keystore
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_CERT_SHA256`: expected signing-certificate SHA-256 digest, with or without colons

With all five values present, the workflow signs the release APK and verifies certificate continuity. Partial signing configuration or an unexpected certificate fails closed.

The unprivileged build job creates and tests an unsigned release APK without access to signing secrets. Its SPDX SBOM scans the release APK and metadata generated from the exact Gradle-resolved release runtime coordinates rather than only the source tree. The signing job receives the secrets only for its signing shell step, verifies the resulting APK, deletes the temporary keystore, and creates GitHub build-provenance attestations for the APK, SBOM, and source archive. Only the final publication job has `contents: write`.

Dependency versions are explicit and Gradle dependency verification is strict. Dependabot proposes Gradle and GitHub Actions updates weekly; dependency checksum changes must be reviewed and regenerated intentionally rather than bypassing verification.

With none of the environment secrets present, the workflow uses the installable debug-signed APK, adds `-test` to its filename, and marks the GitHub release as a prerelease. This mode is intended only for testing.

## Release Steps

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run `./gradlew --no-build-cache clean test lint assembleDebug stageReleaseSbomInputs assembleDebugAndroidTest`.
3. Commit the release changes.
4. Create the matching annotated tag, for example `git tag -a v1.3.0 -m "Speedometer v1.3.0"`.
5. Push the commit and tag.
6. Verify the workflow and download each published asset.

An existing tag can be republished manually from the Actions page or with `gh workflow run Release --ref vX.Y.Z -f tag=vX.Y.Z`. Manual dispatch must run from the same tag supplied as input, resolves the input only as `refs/tags/vX.Y.Z`, and verifies that the checked-out commit is the tag target before building. This keeps build provenance bound to the release commit.
