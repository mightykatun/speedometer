# Repository Guide

## Build And Verification

- This is one Android application module, `:app`. Use the checked-in Gradle wrapper; Java 21 matches the release workflow, while app bytecode targets Java 11.
- Fast local gate: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- Run one JVM test class with `./gradlew testDebugUnitTest --tests 'com.mightykatun.speedometer.app.domain.SpeedEstimatorTest'` (replace the fully qualified class name as needed).
- Compose instrumentation tests need a device or emulator: `./gradlew connectedDebugAndroidTest`. `./gradlew assembleDebugAndroidTest` only compiles the test APK.
- The only CI workflow is the tag/manual release workflow; there is no pull-request gate. Its build command is `./gradlew --no-build-cache clean test lint assembleDebug stageReleaseSbomInputs`.
- `make install` builds the debug APK and runs `adb install -r`; `make log` filters logcat to `MainActivity` and `GnssStatus`.
- Without root `keystore.properties`, `assembleRelease` produces `app/build/outputs/apk/release/app-release-unsigned.apk`. `make release` still prints the signed filename, so do not trust that message when no keystore is configured.
- `stageReleaseSbomInputs` is release-only: it depends on `assembleRelease`, copies the locally signed APK when `keystore.properties` exists or the unsigned APK otherwise, and stages exact `releaseRuntimeClasspath` artifacts for Syft.

## Architecture And Invariants

- `MainActivity.kt` is the real application/UI entrypoint and contains the single-screen Compose UI, permission recovery, Picture-in-Picture, preferences, and lifecycle wiring.
- `SpeedometerViewModel` owns presentation/session state. `SpeedRepositoryViewModel` separately owns `SpeedRepositoryImpl` so acquisition survives configuration recreation and the worker closes only when that owner is cleared.
- Real backgrounding (`onStop` when not changing configuration) must stop GNSS/sensor listeners and reset current speed, trend, and session maxima. Only speed unit, requested tracking mode, and global UI refresh rate persist in `SharedPreferences`.
- `SpeedRepositoryImpl` serializes lifecycle commands, GNSS, location, motion, and estimator calls on one `HandlerThread`. Main-thread deliveries are generation-guarded so callbacks queued before stop/restart cannot leak into a new session; preserve this ordering model.
- Android boundaries are the injectable worker/dispatcher/location/motion interfaces in `RepositoryPlatform.kt`. Repository lifecycle and ordering behavior is intentionally covered by local JVM tests using fakes, not only device tests.
- `domain/` is pure Kotlin. Measurement and estimator timestamps use elapsed-realtime nanoseconds; do not substitute wall-clock time or callback arrival time. Read `WIKI.md` before changing estimator or measurement semantics.
- GNSS is the absolute speed source in both modes. `HANDHELD` ignores IMU; `FIXED` requires both linear-acceleration and rotation-vector sensors and must fall back to handheld if registration fails.
- IMU may only bridge bounded short dropouts; it must not raise session maximum speed. Maximum candidates come from accepted raw GNSS, and stale/unreliable speed becomes unavailable rather than a synthetic zero or display floor.
- Preserve the privacy boundary: the manifest has location permissions but no internet permission, and the app has no analytics or network dependency.

## Tests

- Pure estimator/statistics behavior belongs in `app/src/test/.../domain`; repository races, retries, fallback, and callback generations belong in `SpeedRepositoryImplTest` using the existing platform fakes.
- Compose tests instantiate `SpeedometerScreen` directly in `SpeedometerScreenTest`; keep action semantics/content descriptions stable or update those tests with intentional UI changes.
- Global UI refresh throttling and graph interpolation are presentation-only. Every repository estimate must still update session statistics; never feed interpolated graph values back into acquisition or statistics.
- Field accuracy is not established by JVM tests. `WIKI.md` defines the real-device validation corpus and acceptance targets for estimator tuning.

## Dependencies And Releases

- Repositories are centralized in `settings.gradle.kts` with `FAIL_ON_PROJECT_REPOS`; adding a repository inside `app/build.gradle.kts` fails the build.
- Gradle dependency verification metadata is checked in at `gradle/verification-metadata.xml`. Review and update checksums intentionally with dependency changes; do not bypass verification.
- KAPT is currently unused. `kapt.incremental.apt=false` is a deliberate mitigation for CVE-2026-53914; do not re-enable its incremental cache when introducing annotation processing.
- For releases, follow `RELEASING.md` and `.github/workflows/release.yml`. The `vX.Y.Z` tag must exactly match `versionName`, `versionCode` must increase, and all five persistent signing secrets are mandatory; missing, partial, or mismatched signing configuration fails closed. Releases are stable production releases marked latest, never beta, test, or prerelease builds.
