# Audit Remediation Backlog

Source report: [`AUDIT_REPORT.md`](AUDIT_REPORT.md)
Baseline revision: `65b8eca0faf4ebb1c57313f075d8a2a80c4f11dc`

Each task is designed for one sub-agent or tightly coordinated layer owner. Agents must not change persisted `TrackingMode` values (`handheld`, `fixed`) and must preserve the observable two-state speed/bias estimator unless a task explicitly changes an external contract.

## Completion Status

| Tasks | Status |
| --- | --- |
| AUD-001 through AUD-009A | Complete |
| AUD-010 | Mitigated without a pre-release Kotlin migration; KAPT is unused/disabled and untrusted build caches are prohibited |
| AUD-011 | Implementation and long-stream regression complete; device benchmark evidence remains under AUD-015 |
| AUD-012 | Nonallocating ingestion implementation complete; device allocation measurement remains under AUD-015 |
| AUD-014 | Complete, including strict verification, SBOM/provenance, immutable update pins, and Dependabot policy |
| AUD-013 | Tests implemented and APK compiled; execution awaits a connected emulator/device |
| AUD-015 | Automated long-stream/compaction coverage complete; outdoor accuracy, CPU, and battery validation await a device and reference route |

Repository administration was applied and manually verified on 2026-08-23: environment `release-signing` (`20432952015`), `main` history/deletion ruleset `21240680`, and immutable `v*` tag ruleset `21240681`. Environment deployment policies allow only `main` and `v*` tags. Independent two-person approval still requires adding a second trusted reviewer; the current sole administrator cannot provide separation of duties alone.

## Recommended Serial Order

Dependencies are mandatory; the order also minimizes conflicts where tasks touch the same estimator, repository, or workflow files. Priority reflects release sequencing and shared-code risk, so a bounded finding can be P0/P1 even when its report severity is Medium/Low.

| Order | Task | Priority | Owner | Dependencies |
| --- | --- | --- | --- | --- |
| 1 | AUD-001 Restore Gradle wrapper trust | P0 | Build/security | None |
| 2 | AUD-002 Make repository startup retryable | P0 | Android data | None |
| 3 | AUD-003 Make maximum tracking replay-aware | P0 | Domain | None |
| 4 | AUD-004 Clarify monotonic warmup policy | P1 | Domain/presentation | AUD-003 |
| 5 | AUD-005 Bind maximum trust to coherent fix evidence | P1 | Android data/domain | AUD-002, AUD-003, AUD-004 |
| 6 | AUD-006 Reject duplicate motion inputs | P1 | Domain | AUD-003, AUD-005 |
| 7 | AUD-007 Unify lifecycle and session ownership | P1 | Presentation/Activity | AUD-004 |
| 8 | AUD-008 Add permission and PiP recovery | P1 | Activity/UI | AUD-002, AUD-007 |
| 9 | AUD-009 Harden privileged release workflow | P1 | Build/security | AUD-001 |
| 10 | AUD-009A Apply repository release protections | P1 | Repository administrator | AUD-009 |
| 11 | AUD-010 Address Kotlin build-plugin advisory | P1 | Build | AUD-001, AUD-009 |
| 12 | AUD-011 Optimize replay history | P2 | Domain/performance | AUD-003, AUD-006 |
| 13 | AUD-012 Remove sensor-rate estimate allocations | P2 | Domain/data | AUD-003, AUD-011 |
| 14 | AUD-013 Add Android integration coverage | P2 | Test/UI/data | AUD-002, AUD-007, AUD-008 |
| 15 | AUD-014 Add provenance and dependency controls | P2 | Build/security | AUD-009A, AUD-010 |
| 16 | AUD-015 Validate fixed mode in the field | P2 | QA/performance | AUD-002, AUD-005, AUD-006, AUD-007, AUD-008, AUD-011, AUD-012 |

## P0 Tasks

### AUD-001: Restore Gradle Wrapper Trust

Owner: Build/security agent
Finding: SEC-001
Files: `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/*`

Work:

- Regenerate the wrapper from an independently trusted Gradle 8.13 distribution.
- Confirm the JAR SHA-256 equals Gradle's published 8.13 wrapper hash `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
- Add the official binary distribution checksum as `distributionSha256Sum`.
- Add wrapper validation before every CI Gradle invocation, pinning the validation action to a full commit SHA.
- Record the verification command in release documentation.

Acceptance:

- `sha256sum gradle/wrapper/gradle-wrapper.jar` matches the official reference.
- A deliberately modified wrapper fails CI before Gradle executes.
- `./gradlew --version`, `test`, `lint`, and `assembleDebug` pass.

### AUD-002: Make Repository Startup Retryable

Owner: Android data-layer agent
Finding: RUN-001, RUN-003, THREAD-001
Files: `SpeedRepositoryImpl.kt`, `SpeedRepository.kt`, production test seams, new Android-facing tests

Work:

- Serialize repository lifecycle state on the worker thread.
- Represent stopped, starting, started, and terminal closed states explicitly.
- On any registration failure, remove partial callbacks, clear the session, transition to stopped, and retain or report a retryable error.
- Represent fixed-mode sensor registration success/failure and visibly fall back or degrade when either sensor cannot register.
- Make `startUpdates`, `stopUpdates`, mode changes, and `close` idempotent under rapid ordering.
- Define no-op/error behavior for every call after close.
- Capture and validate generation for every queued task, including mode changes.
- Introduce injectable Android service/worker boundaries or name the Robolectric/instrumentation strategy used to control failures.

Acceptance:

- A failed permission check can be followed by a successful `startUpdates` without recreating the repository.
- GNSS status registration failure removes location updates and permits retry.
- A runtime exception during registration permits retry and emits one useful error.
- Partial or complete motion-sensor registration failure cannot leave the UI claiming active `gnss+imu` fusion.
- Stop during start cannot leave callbacks or an estimate tick registered.
- A queued mode change cannot emit into callbacks belonging to a later generation.
- Calls after close cannot restart the thread or access unregistered services.
- Existing domain and ViewModel tests remain green.

### AUD-003: Make Maximum Tracking Replay-Aware

Owner: Domain agent
Finding: DOM-001
Files: `SpeedEstimator.kt`, `SpeedEstimate.kt`, `SessionStatisticsTracker.kt`, related tests

Work:

- Define ownership of accepted maximum candidates under delayed replay.
- Give candidates stable identities and support replacement/retraction, or calculate the session maximum inside replayed estimator state.
- Do not expose inertial prediction as a raw maximum candidate.
- Preserve GNSS authority, uncertainty gates, warmup, and fix-evidence requirements.
- Bound retained candidate state to the session/replay policy.
- Finalize candidates when they leave the replay window while preserving a committed scalar maximum for finalized valid candidates.

Acceptance:

- Chronological and delayed delivery of the same input set produce exactly the same final top speed.
- A formerly trusted candidate that replay invalidates no longer contributes to top speed.
- Duplicate delivery of a candidate is idempotent.
- Candidate updates cannot lower a valid unrelated higher maximum.
- Pruning replay history cannot lose the highest finalized session candidate.
- Tests cover replay before and after candidate commitment.

## P1 Tasks

### AUD-004: Clarify Monotonic Warmup Policy

Owner: Domain/presentation agent
Finding: LIFE-002
Dependencies: AUD-003
Files: `MonotonicClock.kt`, `domain/time/AndroidElapsedRealtimeClock.kt`, `SessionStatisticsTracker.kt`, factory and tests

Work:

- Preserve the production use of Android elapsed realtime for session and estimate timestamps.
- Rename the generic `currentTimeMillis` abstraction so its monotonic contract and unit are explicit.
- Decide and document whether warmup starts at Activity session start or first eligible maximum candidate.
- If first accepted GNSS provenance is required instead, extend `SpeedEstimate` explicitly so the tracker can observe that event.
- Keep tests in the same monotonic clock domain as production.

Acceptance:

- Wall-clock changes cannot affect warmup.
- A GNSS acquisition delay follows the documented warmup policy.
- Tests distinguish Activity-start and first-fix timing.
- Names encode monotonic clock semantics and units.

### AUD-005: Bind Maximum Trust to Coherent Fix Evidence

Owner: Android data/domain agent
Finding: RUN-002
Dependencies: AUD-002, AUD-003, AUD-004
Files: `SpeedRepositoryImpl.kt`, `GnssMeasurement.kt`, estimator and statistics tests

Work:

- Choose a defensible provenance rule for satellite evidence.
- Prefer fix-native speed accuracy for trust unless GNSS status can be temporally associated with the same fix.
- If satellite count remains a trust input, carry its observation timestamp and enforce a bounded age/order relationship.
- Keep display satellite count independent from maximum-candidate evidence.

Acceptance:

- A stale GNSS status callback cannot approve a maximum candidate from a later fix.
- A newer unrelated status callback cannot retroactively alter an earlier candidate.
- Displayed current/max satellite behavior remains documented and tested.
- Field names encode units and timestamp domain.

### AUD-006: Reject Duplicate Motion Inputs

Owner: Domain agent
Finding: DOM-002
Dependencies: AUD-003, AUD-005
Files: `SpeedEstimator.kt`, `MotionMeasurement.kt`, `SpeedEstimatorTest.kt`

Work:

- Define motion and orientation input identity.
- Reject exact duplicates before they mutate filters, covariance, stationary state, or bias.
- Preserve distinct orientation and acceleration events that legitimately share a timestamp.
- Ensure deduplication remains correct across delayed insertion and history pruning.

Acceptance:

- Adding any motion sample twice gives the same state as adding it once.
- Duplicate stationary samples cannot alter bias.
- Duplicate samples cannot change median/residual filters.
- Chronological and delayed sequences remain equivalent.

### AUD-007: Unify Lifecycle and Session Ownership

Owner: Presentation/Activity agent
Finding: LIFE-001
Dependencies: AUD-004
Files: `MainActivity.kt`, `SpeedometerViewModel.kt`, factory, lifecycle tests

Work:

- Define session boundaries for backgrounding, configuration recreation, PiP, and process recreation.
- Move the idempotent session transition into one owner.
- Reset or retain tracker state and `SpeedometerState` together, never partially.
- Ensure repeated `onStart` does not restart warmup or erase valid session data unexpectedly.

Acceptance:

- Night mode/locale recreation cannot show old maxima backed by a reset tracker.
- Backgrounding follows the documented session reset policy.
- PiP transitions do not create a second session.
- Process recreation initializes a coherent acquiring state.

### AUD-008: Add Permission and PiP Recovery

Owner: Activity/UI agent
Finding: UX-001, UX-002
Dependencies: AUD-002, AUD-007
Files: `MainActivity.kt`, manifest/resources, Compose/instrumentation tests

Work:

- Represent permission-required state explicitly instead of only as an error string.
- Provide retry and app-settings actions after permanent denial or revocation.
- Recheck permission on foreground entry and coordinate retry with AUD-002.
- Check PiP feature support and handle a failed entry without crashing or leaving stale state.

Acceptance:

- Initial denial, permanent denial, settings grant, and background revocation each have a recoverable UI path.
- Tracking restarts after a grant without Activity/process recreation.
- PiP control is unavailable on unsupported devices.
- UI actions have semantic labels and tests.

### AUD-009: Harden Privileged Release Workflow

Owner: Build/security agent
Finding: SEC-003, SEC-004
Dependencies: AUD-001
Files: `.github/workflows/release.yml`, release documentation

Work:

- Pin every third-party action to a reviewed full commit SHA with a version comment.
- Split unprivileged verification/build from privileged signing/publication where practical.
- Scope signing secrets to the minimum signing step rather than job-wide `env`.
- Scope `contents: write` to the publication job.
- Decide and enforce signed tag or attestation identity.
- Write the exact repository-admin handoff consumed by AUD-009A.

Acceptance:

- No mutable action tag executes with signing secrets or a write token.
- No signing secret is available to wrapper validation or ordinary test steps.
- Test-only prereleases still work without production secrets and remain clearly labeled `-test`.
- Tag/version mismatch, partial secrets, and untrusted refs fail closed.

### AUD-009A: Apply Repository Release Protections

Owner: Repository administrator
Finding: SEC-004
Dependencies: AUD-009
Files: GitHub repository settings; no source edit is required unless documenting final rule IDs

Work:

- Create a protected production environment with required approval and bind signing secrets to it.
- Protect `main` and `v*` refs with the checks and review policy defined by AUD-009.
- Restrict release/tag mutation to the intended maintainers or automation identity.
- Verify that the workflow's production job references the protected environment.

Acceptance:

- GitHub API/UI shows active `main` and `v*` rules.
- Production signing secrets are environment-scoped and unavailable before approval.
- A non-authorized writer cannot replace a release tag or bypass required checks.
- The applied rule/environment identifiers and manual verification date are recorded.

### AUD-010: Address Kotlin Build-Plugin Advisory

Owner: Build agent
Finding: SEC-002
Dependencies: AUD-001, AUD-009
Files: root and app Gradle files, dependency metadata, CI

Work:

- Evaluate a compatible Android Gradle Plugin, Kotlin, Compose compiler/plugin, and Compose BOM upgrade that reaches a patched Kotlin plugin.
- Do not upgrade Kotlin alone if that creates compiler/runtime incompatibility.
- Until the upgrade is viable, document that remote/untrusted build caches are prohibited and keep CI ephemeral.

Acceptance:

- The selected toolchain is not in the affected Kotlin plugin range, or a documented temporary mitigation and upgrade blocker is approved.
- Debug and minified release builds pass tests and lint.
- Compose rendering and estimator JVM tests remain green.
- Resolved dependency versions are recorded and reviewable.

## P2 Tasks

### AUD-011: Optimize Replay History

Owner: Domain/performance agent
Finding: PERF-001
Dependencies: AUD-003, AUD-006
Files: `SpeedEstimator.kt`, benchmarks/tests

Work:

- Add a deterministic benchmark for the requested 20,000 microsecond fixed-mode cadence, five-second history, and delayed GNSS replay.
- Replace repeated full-history deduplication scans with tracked identities or timestamp indexes.
- Replace front removal from `MutableList` with a deque, ring buffer, or logical start index.
- Preserve timestamp ordering and input priority exactly.

Acceptance:

- Record benchmark device/JVM, warmups, repetitions, baseline distribution, and an agreed regression threshold.
- Benchmarks show lower worker-thread time for steady ingestion and delayed replay without exceeding the threshold in any covered case.
- History remains bounded.
- Chronological and delayed outputs remain bitwise/effectively equivalent under existing tolerances.
- No new unbounded map/index is introduced.

### AUD-012: Remove Sensor-Rate Estimate Allocations

Owner: Domain/data agent
Finding: PERF-002
Dependencies: AUD-003, AUD-011
Files: estimator API, repository implementation, tests

Work:

- Separate measurement ingestion from estimate snapshot creation.
- Stop creating `SpeedEstimate` for motion callbacks whose return value is discarded.
- Keep GNSS-triggered and 100 ms tick output behavior intentional and documented.

Acceptance:

- Fixed-mode motion ingestion performs no `SpeedEstimate` allocation when no output is requested.
- UI output cadence and GNSS responsiveness do not regress.
- Allocation/performance measurement is attached to the change.

### AUD-013: Add Android Integration Coverage

Owner: Test/UI/data agent
Finding: TEST-001
Dependencies: AUD-002, AUD-007, AUD-008
Files: `app/src/androidTest`, Gradle test configuration, and production seams needed to inject Activity/repository platform boundaries

Work:

- Add Compose interaction tests for all three app-owned controls.
- Add lifecycle tests for recreation, foreground/background, PiP, and permission-state transitions.
- Add repository integration tests with controllable Android service boundaries where feasible.
- Verify preference persistence and safe fallback values through Activity recreation.

Acceptance:

- Mode toggle, unit cycle, and PiP control have forward and return-path assertions.
- Permission denial/grant recovery is automated.
- Lifecycle tests assert tracker and UI state coherence.
- CI runs instrumentation tests on a defined emulator/API matrix, or the limitation is explicitly documented.

### AUD-014: Add Provenance and Dependency Controls

Owner: Build/security agent
Finding: SEC-005
Dependencies: AUD-009A, AUD-010
Files: Gradle verification metadata, dependency configuration, release workflow/docs

Work:

- Add Gradle dependency verification and an intentional locking/update policy.
- Generate an SBOM for release artifacts.
- Add build provenance/attestation and signer-certificate continuity verification.
- Keep APK/source checksums and verify them before publication.
- Add Dependabot or equivalent monitoring for Gradle and GitHub Actions.

Acceptance:

- An unapproved dependency checksum fails the build.
- Release assets include an SBOM and verifiable provenance.
- A production APK signed by an unexpected certificate fails publication.
- Routine dependency updates have a documented regeneration/review procedure.

### AUD-015: Validate Fixed Mode in the Field

Owner: QA/performance agent
Finding: residual runtime and performance risk
Dependencies: AUD-002, AUD-005, AUD-006, AUD-007, AUD-008, AUD-011, AUD-012

Work:

- Define outdoor walking, steady driving, acceleration, braking, stop, tunnel/dropout, device-remount, and poor-sky scenarios.
- Compare displayed and maximum speeds against timestamped reference data.
- Measure worker-thread CPU, allocations, sensor callback latency, and battery drain in handheld and fixed modes.
- Profile `GeomagneticField` construction and Compose speed formatting; optimize PERF-003 only if it exceeds the agreed threshold.
- Add a non-production, in-memory diagnostic stream for candidate decisions; do not persist raw location.
- Capture device/API/sensor capabilities and raw test conditions without storing user location in committed artifacts.

Acceptance:

- Accuracy and maximum-speed error budgets are defined before testing.
- Chronological logs explain every accepted/rejected maximum candidate.
- Fixed mode does not exceed the agreed battery/CPU budget relative to handheld mode.
- No crash, stuck startup, stale session, or unrecoverable permission state occurs across the scenario matrix.

## Global Completion Checklist

- [x] All P0 tasks complete before the next production-signed release.
- [x] Every behavior fix includes regression coverage against the baseline behavior.
- [x] `./gradlew --no-build-cache clean test lint assembleDebug assembleRelease assembleDebugAndroidTest` passes.
- [ ] A minified release build passes with test signing before publication.
- [ ] `git diff --check` passes.
- [ ] Persisted tracking-mode values remain `handheld` and `fixed`.
- [ ] No network permission, analytics, telemetry, or location persistence is introduced without explicit review.
- [ ] Documentation and release claims match the final implementation and signing mode.
