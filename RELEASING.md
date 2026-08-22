# Releasing

Releases are created by `.github/workflows/release.yml` whenever a `v*` tag is pushed.

The tag must match `versionName` in `app/build.gradle.kts`. For example, `versionName = "1.2.2"` requires tag `v1.2.2`.

Every release contains:

- `speedometer-vX.Y.Z.apk` for a production-signed build, or `speedometer-vX.Y.Z-test.apk` when signing is not configured
- `speedometer-vX.Y.Z-source.zip`
- `SHA256SUMS.txt`

GitHub also provides its standard source archives automatically.

## Production Signing

Configure all four Actions secrets in the fork before pushing a production tag:

- `RELEASE_KEYSTORE_BASE64`: base64-encoded JKS keystore
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
- `RELEASE_STORE_PASSWORD`

With all four secrets present, the workflow builds `assembleRelease`. Partial signing configuration fails closed.

With none of the secrets present, the workflow builds an installable debug-signed APK, adds `-test` to its filename, and marks the GitHub release as a prerelease. This mode is intended only for testing.

## Release Steps

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run `./gradlew clean test lint assembleDebug`.
3. Commit the release changes.
4. Create the matching annotated tag, for example `git tag -a v1.2.2 -m "Speedometer v1.2.2"`.
5. Push the commit and tag.
6. Verify the workflow and download each published asset.

An existing tag can be republished manually from the Actions page or with `gh workflow run Release --ref main -f tag=vX.Y.Z`.
