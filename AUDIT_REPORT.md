# Speedometer Engineering Audit

Audit date: 2026-08-23
Audited revision: `65b8eca0faf4ebb1c57313f075d8a2a80c4f11dc` (`v1.3.0`)
Scope: application security, release security, runtime correctness, end-to-end traceability, performance, battery use, and test coverage

## Executive Summary

The application has a small privacy surface. It has no runtime network access, database, analytics, telemetry, WebView, dynamic code loading, or exported component other than the launcher Activity. Location and motion data remain in memory and only user preferences are persisted in private `SharedPreferences`.

The audit found no Critical issue and no evidence of an active compromise. One High issue should be resolved before running another trusted build or treating a build as production-ready:

1. The checked-in Gradle wrapper JAR does not match Gradle's published wrapper checksum for 8.13 or any checksum found in the official reference. Because this binary executes before the build, its provenance cannot currently be trusted.

The next priorities are to make location/GNSS startup retryable, surface fixed-mode sensor registration failure, make maximum tracking replay-aware, bind satellite evidence to the originating fix, align lifecycle/session ownership, and harden the release workflow. Performance findings are source-confirmed opportunities whose runtime significance still requires measurement.

## Remediation Result

Remediation completed on 2026-08-23 in the current worktree. The detailed findings below preserve the baseline audit evidence from `65b8eca`; they are not descriptions of the remediated source.

| Status | Findings |
| --- | --- |
| Resolved | SEC-001, SEC-003, SEC-004, SEC-005, RUN-001, RUN-002, RUN-003, DOM-001, DOM-002, LIFE-001, LIFE-002, PERF-001, PERF-002, UX-001, UX-002, THREAD-001 |
| Mitigated | SEC-002: KAPT is not applied, its incremental cache is disabled, CI uses clean ephemeral builds with `--no-build-cache`, and dependency verification is strict. A stable compatible patched Kotlin toolchain is not yet available. |
| Profile-first | PERF-003: the remaining `GeomagneticField` and rendering work is low-rate/small and was not replaced with riskier caching or formatting code without device evidence. |
| Implemented, device run pending | TEST-001: 78 JVM tests pass per build variant and Compose instrumentation tests compile; no emulator/device is connected to execute them. Outdoor accuracy and battery validation remain field work. |

Release hardening now includes the official Gradle wrapper/distribution checksums, immutable action SHAs, exact tag-to-commit verification, isolated signing, certificate continuity, final checksum verification, an SPDX SBOM, provenance attestations, strict dependency verification, immutable `v*` tag rules, protected `main` history, and a reviewer-gated `release-signing` environment. Production signing remains intentionally unavailable until all five environment values are configured.

Administrative residual: the repository currently has only one trusted administrator/reviewer, so the environment gate cannot provide independent two-person approval. Add a second trusted reviewer before claiming separation of duties; production secrets remain absent in the meantime.

## Rating Model

| Severity | Meaning |
| --- | --- |
| Critical | Direct, broadly exploitable compromise or unrecoverable safety/data impact requiring immediate release halt. |
| High | Material build-integrity or core correctness failure with a credible trigger. Resolve before a production release. |
| Medium | Significant reliability, security-hardening, performance, or maintainability gap with bounded preconditions. |
| Low | Defense-in-depth, UX recovery, efficiency, or coverage improvement with limited immediate impact. |

`Vulnerability` means a concrete security weakness in the current configuration. `Correctness` means an application behavior defect. `Hardening` means a control that reduces future or privileged compromise risk but is not evidence of current exploitation.

## Findings Summary

| ID | Severity | Type | Finding | Primary evidence |
| --- | --- | --- | --- | --- |
| SEC-001 | High | Vulnerability | Unverifiable Gradle wrapper binary | `gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties:1` |
| RUN-001 | Medium | Correctness | Failed location/GNSS startup cannot retry until stopped | `SpeedRepositoryImpl.kt:71-85`, `185-190`, `224-251` |
| DOM-001 | Medium | Correctness | Replayed estimator history cannot retract a committed maximum | `SpeedEstimator.kt:183-215`, `717-725`, `SessionStatisticsTracker.kt:32-43` |
| SEC-002 | Medium | Vulnerability | Kotlin Gradle plugin is affected by unsafe build-cache deserialization | `build.gradle.kts:6` |
| SEC-003 | Medium | Hardening | Mutable action tags run in a job with signing secrets and write permission | `release.yml:14-29`, `32-57`, `116-121` |
| SEC-004 | Medium | Hardening | Release refs and deployment are not protected | `release.yml:3-19`, repository settings |
| RUN-002 | Medium | Correctness | Satellite count is not tied to the GNSS fix epoch | `SpeedRepositoryImpl.kt:156-165`, `282-291` |
| RUN-003 | Medium | Correctness | Fixed mode silently continues when IMU registration fails | `SpeedRepositoryImpl.kt:43-44`, `201-222`, `MainActivity.kt:286-287` |
| LIFE-001 | Medium | Correctness | Activity recreation mixes retained UI state with reset session statistics | `MainActivity.kt:141-159`, `SpeedometerViewModel.kt:53-62` |
| SEC-005 | Low | Hardening | Dependency and artifact provenance controls are incomplete | `gradle-wrapper.properties`, release workflow, missing verification metadata |
| DOM-002 | Low | Correctness | Duplicate motion timestamps mutate estimator filters | `SpeedEstimator.kt:136-157`, `298-346`, `625-659` |
| LIFE-002 | Low | Policy | Maximum-speed warmup starts before the first accepted GNSS fix | `MainActivity.kt:141-145`, `SessionStatisticsTracker.kt:19-25`, `37-40` |
| PERF-001 | Low | Optimization | Replay history performs repeated linear scans and front removals | `SpeedEstimator.kt:123`, `138-141`, `198-215`, `746-752` |
| PERF-002 | Low | Optimization | Fixed mode allocates discarded estimates at the requested sensor cadence | `SpeedEstimator.kt:136-157`, `SpeedRepositoryImpl.kt:208-218`, `316-329` |
| UX-001 | Low | Correctness | Permission revocation has no in-app recovery action | `MainActivity.kt:53-87`, `161-176` |
| UX-002 | Low | Correctness | PiP entry checks API level but not device capability or failure | `MainActivity.kt:111-117`, `377-387` |
| THREAD-001 | Low | Correctness | A queued mode change can emit under a later generation | `SpeedRepositoryImpl.kt:88-110`, `339-366` |
| PERF-003 | Low | Optimization | GNSS and Compose paths contain avoidable transient work | `SpeedRepositoryImpl.kt:272-280`, `MainActivity.kt:302-305` |
| TEST-001 | Low | Coverage | No instrumentation, Compose interaction, lifecycle, or real-sensor tests exist | `app/src/test`, missing `app/src/androidTest` |

## Detailed Findings

### SEC-001: Unverifiable Gradle Wrapper Binary

The repository configures Gradle 8.13, but the checked-in wrapper JAR has SHA-256:

```text
b5173cbc1029dbe2212de0ff1c6331940f1c841bb26a0685b7189615802bf365
```

Gradle publishes this wrapper checksum for 8.13:

```text
81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f
```

The current JAR still launches Gradle 8.13 successfully, but that does not establish that its executable contents are official. A modified wrapper can execute arbitrary code before tests, signing, or packaging and can read job-level signing secrets.

Remediation: regenerate the wrapper from a trusted Gradle 8.13 distribution, verify the resulting official checksum independently, add `distributionSha256Sum`, and validate wrapper JARs in CI before any Gradle invocation.

### RUN-001: Failed Location/GNSS Startup Cannot Retry Until Stopped

`startUpdates` sets `started = true` before `Session.start()` runs on the worker thread. If permission disappears between the main-thread check and worker registration, `registerLocationCallbacks` returns false. The session remains assigned and `started` remains true. A later `startUpdates` call takes the already-started branch and cannot retry location registration.

Impact: tracking can remain inactive until `stopUpdates` or object recreation, even after the transient cause is resolved.

Remediation: model startup as explicit lifecycle states on the worker thread. Failed registration must clean up the session, return to `STOPPED`, and permit retry. Add race and failure-path tests around permission loss, provider exceptions, and GNSS callback registration failure.

### DOM-001: Replayed Maximum Cannot Be Retracted

The estimator correctly inserts delayed GNSS measurements into timestamp order and reprocesses later history. That replay can change whether a later fix is accepted, trusted, or selected as the latest maximum candidate. `SessionStatisticsTracker`, however, consumes emitted candidates incrementally and stores only a monotonically increasing float maximum. It records a candidate timestamp once and has no protocol for replacing or retracting an earlier output.

Impact: top speed can retain a value that the estimator's final chronological history no longer considers valid.

Remediation: make maximum tracking replay-aware. A minimal design is to expose stable candidate IDs and candidate upsert/retract events, then calculate the maximum over the bounded accepted-candidate set. An alternative is to move maximum calculation into the estimator's replayed state. Add a regression test that processes the same sequence chronologically and with delayed GNSS and asserts identical final session maxima.

### SEC-002: Vulnerable Kotlin Build Plugin

The project uses `org.jetbrains.kotlin.android` 1.9.24. GitHub advisory `GHSA-r937-wjx7-w2jp` / `CVE-2026-53914` affects `org.jetbrains.kotlin:kotlin-gradle-plugin` versions before 2.4.20-Beta1. It describes code execution through unsafe deserialization of build-cache metadata and has a Medium CVSS score with local, high-privilege preconditions.

This is a build-host risk, not an Android runtime vulnerability. The repository does not configure a shared remote build cache, reducing exposure, but developer and CI build inputs still need to be trusted.

Remediation: plan a compatible Kotlin and Compose toolchain upgrade. Until then, do not consume untrusted build caches, keep CI runners ephemeral, and avoid enabling a shared remote cache.

### SEC-003: Mutable Actions Share Signing Scope

`actions/checkout@v5`, `actions/setup-java@v5`, `android-actions/setup-android@v3`, and `actions/upload-artifact@v4` are tag references rather than immutable commit SHAs. The release job grants `contents: write` and exposes all four signing secrets at job scope before these actions run.

No malicious action is identified. The risk is that a compromised or moved upstream tag would receive signing material and a write-capable token.

Remediation: pin every action to a reviewed full commit SHA, document the corresponding release tag in a comment, scope signing secrets only to the signing step, and separate unprivileged test/build work from privileged publication.

### SEC-004: Release Refs and Deployment Are Unprotected

The live repository reports no rulesets, no `main` branch protection, and no GitHub environment. Releases run on any pushed `v*` tag or manual dispatch, and the workflow's `--verify-tag` checks that a remote tag exists, not that it is cryptographically signed or immutable.

Remediation: protect `main` and `v*` refs, require review/status checks, publish through a protected environment with approval, and define whether annotated signed tags or GitHub attestations are the release identity.

### RUN-002: Satellite Evidence Has Different Provenance

`GnssStatus.Callback` updates a mutable latest count independently of `LocationListener`. `createMeasurement` copies that latest count into a location fix. The count can therefore describe a different receiver epoch from the speed and speed-accuracy values, yet it is later used to admit maximum-speed candidates.

Remediation: either remove satellite count from maximum trust and rely on fix-native speed accuracy, or attach status only when its timestamp/epoch can be bounded to the location. Document the chosen provenance rule and test stale/newer status interleavings.

### RUN-003: Fixed Mode Silently Continues Without IMU Input

`supportsFixedMode` checks only that both sensors exist. If either `registerListener` call fails, the repository unregisters the listener but emits no error, schedules no retry, and leaves the UI labeling the mode `gnss+imu`.

Impact: users can believe fusion is active while the estimator receives GNSS only. Partial registration failure is cleaned up, but the requested mode and UI remain misleading.

Remediation: treat sensor registration as an explicit fixed-mode capability state. Report failure, retry only under a bounded policy, and either fall back visibly to handheld/GNSS mode or expose a degraded fixed-mode status.

### DOM-002: Duplicate Motion Samples Are Not Idempotent

GNSS timestamps are deduplicated, but motion timestamps are always inserted. A duplicate does not advance Kalman time, yet it still advances median/residual filters and may adjust stationary bias. This makes results dependent on callback duplication.

Remediation: reject duplicate motion inputs using a stable identity such as sensor type plus timestamp, including orientation handling. Add duplicate-before-prune and delayed-duplicate tests.

### LIFE-001: Retained UI and Reset Statistics Diverge

The ViewModel survives ordinary Activity recreation. `onStop` intentionally skips reset while configurations change, but the new Activity calls `onSessionStart`, which resets the tracker without resetting `SpeedometerState`. Old current/max values remain visible until callbacks replace parts of the state, while the internal tracker has begun a new session.

Remediation: give the ViewModel one idempotent lifecycle/session transition that owns both tracker and UI state, or retain both consistently across recreation. Test night mode, locale, and process recreation separately.

### LIFE-002: Warmup Is Based on Activity Start

The warmup timestamp is captured in `onStart` before a usable GNSS fix exists. If acquisition takes longer than the warmup period, the first accepted fix can immediately update top speed, defeating the warmup's likely purpose of suppressing acquisition transients.

Remediation: define the intended policy. If warmup protects GNSS acquisition, start it on the first accepted/tracking GNSS correction rather than Activity start. Use the monotonic clock domain already carried by estimates.

### PERF-001: Replay History Uses Linear Operations

Fixed mode requests orientation and motion callbacks with a 20,000 microsecond period over a five-second replay window. Actual delivery rate is device-dependent. Duplicate scans, insertion search, replay, and `removeAt(0)` are all linear over a list that can contain hundreds of entries. Delayed fixes intentionally trigger replay, so this work occurs on the same serial worker that handles incoming sensors. Runtime impact has not yet been benchmarked.

Remediation: first benchmark representative history sizes. Then use timestamp keys or tracked last-seen IDs for deduplication and a deque/ring-buffer strategy for pruning. Keep deterministic timestamp/priority ordering and chronological equivalence tests.

### PERF-002: Discarded Estimates at Sensor Rate

`onMotionMeasurement` always creates and returns a `SpeedEstimate`; `SpeedRepositoryImpl.updateAcceleration` ignores it. In fixed mode this produces avoidable work and allocation at the device's delivered sensor cadence, while the repository already emits display estimates on a 100 ms tick. Allocation impact has not yet been measured.

Remediation: separate input ingestion from snapshot generation, for example `onMotionMeasurement(...): Unit`, and generate estimates only for GNSS callbacks and the output tick. Preserve public behavior with tests before measuring allocation improvement.

### SEC-005: Incomplete Provenance Controls

The wrapper distribution lacks `distributionSha256Sum`; dependency verification and locking are absent; releases publish checksums but no signer continuity check, SBOM, or build attestation. These controls are defense-in-depth after SEC-001 through SEC-004.

### UX-001: Permission Recovery Is External

Denial produces a useful error, and a later grant followed by `onStart` can recover. However, permanent denial or background revocation has no in-app retry/settings action and can leave stale or acquiring state until the user independently changes settings or recreates the Activity.

### UX-002: PiP Capability Is Assumed

PiP entry is gated by Android version but not `FEATURE_PICTURE_IN_PICTURE`, and the result/failure path is ignored. This is a bounded device-specific reliability issue.

### THREAD-001: Mode Change Can Emit Under a Later Generation

`setTrackingMode` queues worker work without capturing the originating generation, then calls `emitEstimate(..., generation)` using the generation current when the task executes. A rapid mode-change, stop, and restart can let that old task label an estimate with the new generation and deliver it to the new callback set before queued stop/start work completes.

Remediation: capture and validate generation for every queued lifecycle/mode task, or serialize all state mutation and generation assignment on the worker. Add a deterministic mode-change/stop/restart ordering test.

### PERF-003: Small Transient Work

Each GNSS fix constructs a `GeomagneticField`, and speed rendering formats then splits a string on recomposition. These are low-rate or small compared with sensor replay work. Optimize only after profiling PERF-001 and PERF-002.

### TEST-001: Platform Paths Are Untested

The 55 JVM tests exercise estimator, statistics, ViewModel, units, and preferences models well. There are no Android instrumentation or Compose tests for permissions, lifecycle recreation, sensor registration failure, SharedPreferences integration, control semantics, or PiP. Outdoor accuracy and battery behavior are also unvalidated.

## End-to-End Traceability

### UI Interaction Matrix

| Interaction | Forward call chain | Persisted/platform effect | Return path | Result |
| --- | --- | --- | --- | --- |
| Tracking mode row | `toggleable(Boolean)` -> `changeTrackingMode(Boolean)` -> `SpeedRepositoryImpl.setTrackingMode(TrackingMode)` -> worker -> `SpeedEstimator.setTrackingMode` and sensor registration | Writes `tracking_mode` as `handheld` or `fixed` | worker estimate -> main handler -> ViewModel state -> Compose | Complete; lifecycle/race tests missing |
| Speed unit text | `clickable()` -> `cycleSpeedUnit()` -> `SpeedUnit.next()` | Writes `speed_unit` as `kmh`, `mph`, `knots`, or `mps` | Activity Compose state converts km/h for current, accuracy, and max labels | Complete and unit-tested |
| `float` button | `Button` -> `enterPipMode()` -> Android `enterPictureInPictureMode` | Platform PiP state | `onPictureInPictureModeChanged` -> Compose state -> compact layout | Complete on normal devices; capability failure unhandled |
| Permission dialog | system result -> launcher callback -> `startSpeedTracking()` or `ViewModel.onError` | Android permission grant | repository callback or error state -> Compose | Complete initial flow; recovery UX incomplete |

There are no application-owned links, menus, text inputs, database operations, or network requests.

### Runtime Measurement Chain

```text
Android LocationManager / SensorManager
  -> SpeedRepositoryImpl.Session callbacks on speed-sensors HandlerThread
  -> GnssMeasurement / MotionMeasurement
  -> SpeedEstimator serialized ingestion and bounded replay
  -> SpeedEstimate
  -> SpeedRepositoryImpl mainHandler
  -> SpeedometerViewModel
  -> SessionStatisticsTracker + SpeedometerState
  -> Compose recomposition
  -> displayed speed, uncertainty, quality, maxima, and satellite counts
```

The callback signatures match across `SpeedRepository` and `SpeedRepositoryImpl`. Session-originated callbacks capture and validate their generation, preventing obsolete sessions from delivering most queued main-thread work. The mode-change path is the exception described by THREAD-001. Other semantic exceptions are satellite epoch provenance, retry state, fixed-mode registration state, and replay-aware maximum ownership.

### Units and Time Domains

| Value | Producer unit/domain | Consumer | Status |
| --- | --- | --- | --- |
| Location speed | meters/second | estimator | Correct |
| Location speed accuracy | meters/second, one sigma | estimator covariance and ViewModel | Correct; UI labels estimator uncertainty without explicitly stating sigma |
| Display/session speed | km/h internally in `SpeedometerState` | `SpeedUnit.fromKilometersPerHour` | Correct |
| Acceleration | meters/second squared in east/magnetic-north/up frame | estimator | Correct when rotation/course evidence is valid |
| Bearing/declination | degrees | converted to radians in estimator | Correct |
| Orientation | radians | estimator | Correct |
| Sensor and location time | elapsed realtime nanoseconds | estimator ordering/replay | Correct and monotonic |
| Session start | elapsed realtime milliseconds converted to nanoseconds | compared to elapsed realtime candidate timestamps | Correct monotonic clock domain; warmup start event remains ambiguous |

`ProductionTimeProvider.currentTimeMillis()` is generically named but returns `SystemClock.elapsedRealtime()`. Multiplying it to nanoseconds is compatible with `Location.elapsedRealtimeNanos`. Renaming the abstraction would make that contract harder to misuse.

### Persistence Chain

```text
Compose action
  -> MainActivity state mutation
  -> MODE_PRIVATE SharedPreferences apply()
  -> process restart read
  -> SpeedUnit.fromPreference / TrackingMode.fromPreference
  -> safe default on unknown or missing value
  -> Compose and repository configuration
```

Preference keys and values are internally consistent. Preserve `handheld` and `fixed`; they are persisted external state even though the storage is private.

### Release Chain

```text
v* tag or manual dispatch
  -> checkout requested ref
  -> versionName/tag equality check
  -> Java and Android SDK setup
  -> select debug prerelease or secret-backed release signing
  -> clean + unit tests + lint + APK build
  -> APK and source archive
  -> SHA256SUMS.txt
  -> workflow artifact
  -> GitHub prerelease/release
```

`v1.3.0` is currently published as a debug-signed prerelease with `speedometer-v1.3.0-test.apk`, source archive, and checksums. That behavior matches the no-secret fallback in the workflow. It must not be represented as a production-signed release.

## Positive Controls

- No `INTERNET` permission and no runtime networking dependency.
- No database, file export, analytics, telemetry, advertising, or location logging.
- Private preferences use enumerated parsing with safe defaults.
- Only the launcher Activity is exported.
- Release builds enable code shrinking and resource shrinking.
- Release scripts use strict Bash mode and quote variable expansions.
- Partial signing-secret configuration fails closed.
- Session callback delivery is generation-gated after stop/restart; THREAD-001 documents the mode-change exception.
- Estimator rejects invalid GNSS values and duplicate GNSS timestamps.
- Domain and presentation logic have 55 passing JVM tests.

## Verification Performed

- `./gradlew --version`: Gradle 8.13 launches under Java 21.
- `./gradlew :app:dependencyInsight --dependency kotlin-stdlib --configuration debugRuntimeClasspath`: resolved Kotlin stdlib 1.9.24.
- Audited revision verification: `./gradlew clean test lint assembleDebug` passed with 55 tests.
- Wrapper SHA-256 compared against Gradle's official checksum reference.
- GitHub repository settings queried: no branch protection, rulesets, or environments; default workflow token permission is read.
- GitHub release queried: `v1.3.0` is a published prerelease with a test APK.
- No connected-device or outdoor sensor validation was performed.

## References

- Gradle checksum reference: https://gradle.org/release-checksums/
- Gradle wrapper validation guidance: https://github.com/gradle/actions/blob/main/docs/wrapper-validation.md
- GitHub Actions hardening: https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions
- Kotlin advisory: https://github.com/advisories/GHSA-r937-wjx7-w2jp
- Action backlog: [`AUDIT_TASKS.md`](AUDIT_TASKS.md)
