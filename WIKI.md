# Speedometer - Product and Engineering Documentation

## Overview

- **App:** Speedometer
- **Package:** `com.mightykatun.speedometer.app`
- **Platform:** Native Android, min SDK 24, target SDK 35
- **Purpose:** Privacy-focused, accuracy-aware speed and sailing start-line display
- **Network access:** None

The app uses Android GNSS speed as its absolute speed source. In `gnss+imu` mode, Android's linear-acceleration and rotation-vector sensors provide bounded short-term prediction between GNSS fixes. Inertial data never replaces GNSS indefinitely.

## Screen

The app remains a single-screen HUD with no navigation graph.

### Top Left

- Shows `vX.Y.Z` as a compact gray label using the packaged app version
- Shows recent satellites reported as used-in-fix by Android's GNSS status callback; stale evidence returns to zero
- Colors the count red at zero, orange from one through five, and green at six or more; there is no separate satellite status dot
- Satellite count is status information, not a speed-accuracy measurement

### Top Right

- Persisted text-only `gnss` / `gnss+imu` selector matching the HUD labels
- Defaults to `gnss+imu` and falls back to `gnss` when either required motion sensor is unavailable
- Disabled when the device lacks either linear acceleration or rotation-vector sensors
- A persisted global refresh selector below the mode control cycles through 0.5, 1, and 2 seconds
- Hidden in Picture-in-Picture mode

### Center

- Current speed to two decimal places
- Tap the unit to cycle km/h, mph, knots, and m/s
- Low speeds remain visible; there is no 1.5 km/h display floor
- `--` means speed is not currently defensible and pulses while acquiring the initial measurement
- A small colored dot in Picture-in-Picture and compact `± value unit` line report one-standard-deviation uncertainty
- The uncertainty indicator compares relative uncertainty `p = 100 * uncertainty / speed` with speed-dependent limits using `x` in m/s: green through `20/(1+x^2) + 10 - 5*atan(x/10)`, amber through `20/(1+x^2) + 20 - 10*atan(x/5)`, and red above that or when the percentage is undefined
- The uncertainty line is hidden while acquiring the first required GNSS fix or IMU sample
- Double-tapping the numeric speed cycles the portrait presentation through normal, focused-speed, and regatta displays without changing acquisition

Estimate states:

| State | Indicator | Meaning |
|---|---|---|
| Tracking | Number available | Recent GNSS correction and bounded uncertainty |
| Estimated | Number available | Number is available but uncertainty or fix age is elevated |
| Acquiring | `--` | No valid GNSS speed seed or first IMU sample yet |
| Speed unavailable | `--` | Last defensible estimate is too old or too uncertain; reports `no signal` only when the recent satellite count is zero |

### Regatta Display

The portrait-only regatta display hides the two normal header rows to give a large true vessel heading the same numeral size as the existing centered speed readout. Speed remains in its normal position and is fixed to knots; `DTL | TTL` and text-only `pin | boat` controls sit below. It also hides speed uncertainty, the map, trend, altitude, normal direction labels, session maxima, reset, and float actions. Landscape and Picture-in-Picture temporarily override any portrait selection with the existing speed-only display, including its uncertainty indicator, and restore the selected portrait display afterward.

- Heading is formatted as three digits plus a speed-unit-style label, such as `005 deg`; unavailable heading is `-- deg`
- DTL is signed whole-meter perpendicular distance to the infinite start line: positive on the pre-start side and negative on the course side
- With the committee boat at the starboard end and pin at the port end when looking up-course, the directed boat-to-pin line has pre-start on its left and course-side on its right
- TTL is a whole non-negative number of seconds only when reliable GPS speed/course proves the vessel is closing from the pre-start side; stationary, parallel, opening, course-side, or uncertain motion displays `-- s`
- A red point control captures a current GPS fix with at most `10 m` horizontal accuracy and turns green; failed capture leaves it unchanged and warns the user
- A green point ignores a physical single tap and clears only on physical double-tap; each endpoint resets independently
- Line points remain in memory across display changes, configuration recreation, background acquisition segments, and the normal session reset; they clear individually or on process death
- The line is considered usable only when endpoint separation exceeds both the sum of reported endpoint accuracies and twice their root-sum-square vector uncertainty
- TTL additionally requires an estimator-accepted GNSS correction, measured pre-start distance beyond two-sigma projected position/endpoint uncertainty, at least `0.2 m/s` speed, speed accuracy at most `2.0 m/s`, course accuracy at most `20°`, and inward velocity beyond two-sigma speed/course/start-line-angle uncertainty

### Position Trail

The existing normal north-up trail is unchanged: it labels and draws GPS movement heading while moving. True vessel heading is shown only by the regatta display and is never written to GPX.

### Bottom

- Top speed records accepted raw GNSS candidates after a two-second warmup, with at least three satellites and no estimator probation
- Top satellites tracks the session maximum independently
- A live-smoothed 30-second trend tail fades toward the left and animates between global UI refreshes
- `reset` restarts acquisition and session statistics from the normal HUD
- Text-only `float` enters Picture-in-Picture on Android 8+

## Tracking Modes

### GNSS

`gnss` is the handheld-safe mode. It confidence-weights GNSS speed and ignores all IMU input because hand movement cannot be separated reliably from vehicle acceleration.

### GNSS + IMU

`gnss+imu` assumes the phone is rigidly mounted. It:

1. Uses `TYPE_ROTATION_VECTOR` to transform device acceleration into magnetic East/North/Up.
2. Anchors travel direction with a quality-gated GNSS bearing and local magnetic declination.
3. Tracks turns between GNSS fixes from relative yaw change.
4. Projects horizontal acceleration into longitudinal and lateral components.
5. Rejects spikes with a median filter and smooths accepted acceleration by elapsed time rather than sample count.
6. Increases process uncertainty for lateral motion, vertical motion, and unstable acceleration.
7. Predicts speed and acceleration bias only inside a time- and uncertainty-based physical envelope.
8. Quarantines violent handling and falls back to GNSS-only whenever orientation, course, timestamp, or sensor quality is inadequate.

`gnss+imu` does not promise tunnel navigation. Inertial propagation is limited to three seconds because consumer accelerometer bias creates rapidly growing velocity error.

### Vessel Heading

The vessel-heading channel is independent of tracking mode and the speed estimator. It uses Android's fused `TYPE_ROTATION_VECTOR`, falling back to `TYPE_GEOMAGNETIC_ROTATION_VECTOR` when necessary. The supported regatta mount is exact: phone portrait and vertical, screen facing aft, with device `-Z` pointing toward the bow. There is no installation-offset calibration.

Rotation matrices map the bow axis into magnetic East/North. Samples with Android's unreliable status, reported heading accuracy worse than `25°`, inadequate unreported accuracy, or a horizontal bow projection below `0.25` are rejected. Accepted angles use circular exponential smoothing with a `250 ms` time constant and become unavailable after `500 ms` without a fresh sensor sample; GPS COG is never substituted.

True-north correction uses the bundled degree-12 World Magnetic Model 2025 coefficients and the latest valid GPS latitude, longitude, altitude, UTC, and elapsed-realtime epoch. The model accepts dates from 2025 through 2029 and rejects locations where horizontal magnetic intensity is below `2000 nT`, so heading fails closed in navigation-blackout regions and after model expiry. Coefficients come from NOAA/NCEI's [WMM2025 release](https://doi.org/10.25921/aqfd-sd83); model documentation is the [WMM2025 technical report](https://doi.org/10.25923/prbc-s316).

## Data Pipeline

```text
LocationManager GPS_PROVIDER ─┐
GnssStatus satellite count ───┤
TYPE_LINEAR_ACCELERATION ──────┼─> SpeedRepositoryImpl worker thread
TYPE_ROTATION_VECTOR ──────────┤             ├─> SpeedEstimator -> SpeedEstimate ─┐
GEOMAGNETIC_ROTATION_VECTOR ───┘             └─> WMM2025 -> VesselHeading ────────┤
                                                                                v
                                                                SpeedometerViewModel -> Compose
```

`SpeedRepositoryImpl` serializes location and sensor callbacks on one `HandlerThread`. A 10 Hz tick keeps stale-data state current, while accepted location callbacks may emit immediately. Starting and stopping are idempotent. Listeners remain active across configuration recreation and are removed when `onStop` represents real backgrounding.

Refresh selection is presentation-only. Repository estimates and session statistics continue at 10 Hz; speed, uncertainty, quality, maxima, satellites, and the graph publish together at the selected UI interval. Unavailable/recovered speed transitions and satellite loss to zero publish immediately, and interpolated graph values never feed the estimator or statistics.

GNSS satellite callbacks update the displayed count and timestamped satellite evidence for subsequent fixes. Evidence and the displayed count expire after two seconds without a fresh callback. They never replay a cached `Location` and never refresh fix age.

## Measurement Semantics

`GnssMeasurement` preserves:

- Optional speed from `Location.hasSpeed()`
- Optional 68-percent speed uncertainty on API 26+
- Optional course over ground and course uncertainty
- Horizontal positional accuracy, kept separate from speed uncertainty
- Local magnetic declination
- Temporally bounded used-in-fix evidence from the latest preceding GNSS status callback
- `Location.elapsedRealtimeNanos` measurement time

`MotionMeasurement` preserves transformed East/North/Up linear acceleration, device yaw/pitch/roll, orientation reliability, the acceleration timestamp, and the exact rotation-vector timestamp used for that transform.

`PositionFix` names Android `Location.bearing` explicitly as course over ground and carries optional raw ground speed, API 26+ speed/course accuracy, and replay-aware estimator acceptance for TTL. `VesselHeading` is a separate true-north orientation value and timestamp. GPX remains standard latitude, longitude, optional elevation, and UTC time only.

Both timestamps use Android's elapsed-realtime-since-boot timebase. Callback arrival time and wall-clock time are not used for filtering.

## Estimator

### GNSS Correction

GNSS speed is authoritative. Measurement variance starts from Android's speed uncertainty:

```text
sigma = max(reportedSpeedAccuracy, 0.2 m/s)
R = (1.5 * sigma)^2
```

Missing speed accuracy receives a conservative `2.0 m/s` uncertainty. Measurements above `2.0 m/s` reported uncertainty do not correct the estimate. Poor horizontal position accuracy alone does not force speed to zero.

An innovation gate rejects isolated statistically implausible speed jumps. Two consecutive high-quality, mutually consistent fixes trigger controlled reacquisition so the filter cannot lock out after a genuine speed change or GNSS outage. Reacquired speed remains in maximum-speed probation until another in-gate GNSS fix confirms it.

Displayed speed may be a fused estimate, but session maximum consumes separate raw, accepted GNSS candidates. Replay emits bounded candidate upserts, retractions, and finalizations so delayed fixes cannot leave a maximum that the final chronological history rejects. Inertial prediction can never inflate the recorded maximum. The two-second maximum warmup begins at the first accepted GNSS correction. The maximum gate requires inflated two-sigma uncertainty at or below `2.0 m/s`; with the `1.5` inflation above, reported one-sigma speed accuracy must be about `0.67 m/s` or better.

### Fixed-Mode Prediction

The fixed-mode state is forward speed and longitudinal acceleration bias:

```text
x = [speed, bias]
speed' = speed + (longitudinalAcceleration - bias) * dt
bias' = bias
```

All covariance calculations use `Double`. GNSS corrections use the Joseph covariance form for numerical stability.

The acceleration channel uses a median-of-three prefilter and an exponential smoother with an `80 ms` time constant. Its covariance includes lateral/vertical projection leakage and measured sample instability. Acceleration is ignored when uncertainty exceeds the configured limit, and violent motion clears the course and starts a `500 ms` inertial quarantine. Prediction is rejected if it exceeds a physical envelope derived from the latest GNSS speed, GNSS uncertainty, elapsed time, and conservative acceleration/braking limits.

### Delayed Measurements

The estimator retains five seconds of timestamped inputs and state checkpoints. A GNSS measurement delayed by up to three seconds is inserted at its measurement epoch, then all later events are replayed. Applying an old fix at callback time is prohibited. GNSS, motion, and orientation identities are deduplicated by stream and elapsed-realtime timestamp. History uses indexed duplicate checks, binary insertion, logical-prefix pruning, and amortized compaction. An accepted delayed correction still replays normally, but one measured before the current provider-recovery boundary cannot complete recovery.

### Low Speed and Stationarity

There is no arbitrary minimum display speed. Valid values such as 0.1, 0.2, or 0.5 m/s remain numeric.

Zero requires repeated, high-confidence GNSS readings of exactly zero over at least two seconds. Every accepted positive speed immediately exits stationarity, however small it is. `gnss+imu` additionally requires quiet longitudinal acceleration.

The internal Gaussian state is not clipped. Only its published result is constrained to the physically valid non-negative speed domain.

### Signal Age

| Condition | Result |
|---|---|
| No accepted speed | Acquiring, no number |
| Recent correction with 2-sigma uncertainty at or below 2.5 m/s | Tracking |
| Correction age up to 3 seconds | Estimated/degraded number |
| Correction older than 3 seconds | Unavailable, `--` |
| Strong stationary evidence | Valid `0.00` |

No watchdog injects fake zero readings.

## Session Behavior

- Acquisition starts in `onStart` after precise-location permission is available
- Acquisition and sensor listeners survive configuration recreation but stop when the app backgrounds
- Backgrounding clears live readings and the trend but preserves in-memory session maxima and trail segments until explicit reset or process death
- The trail starts only after two accurate, physically plausible fixes, samples movement at 3 m spacing, progressively compacts at 2,048 points, and always retains its first and latest anchors
- Trail projection is north-up, does not connect separate foreground acquisition spans, and continuously zooms to fit the complete session path; it is hidden when the viewport cannot keep it clear of the speed display
- On Android 10+, double-tapping the visible trail exports its sampled acquisition spans as GPX track segments directly to Downloads and confirms the saved filename with a standard Toast
- Double-tapping the numeric speed cycles normal, focused-speed, and regatta portrait displays without resetting acquisition or session state; landscape and Picture-in-Picture always use the enlarged speed-only layout
- Display unit, requested tracking mode, and global refresh interval persist locally
- A non-sensitive permission-requested marker persists only to distinguish first request from settings-only denial; permission grants and in-flight state do not persist as behavioral preferences
- No location, motion, or session history leaves app memory unless the user explicitly exports a GPX file; the app still has no network or broad storage permission

## Errors and Fallbacks

| Condition | Behavior |
|---|---|
| Fine location denied | Explain that precise location is required |
| Permission can be requested again | Show `grant location` |
| Permission permanently denied or policy-blocked | Show `open settings` instead of an inert grant action |
| GPS provider disabled | Show `gps provider disabled` inline until enabled, then wait for an estimator-accepted correction from the current recovery epoch with an elapsed-realtime timestamp newer than the disable/re-enable boundary |
| Speed absent or invalid | Ignore the measurement; never synthesize zero |
| Speed uncertainty too poor | Preserve the prior estimate until it becomes stale |
| IMU sensors absent | Disable `gnss+imu` |
| IMU registration fails | Run GNSS-only for that session, preserve the requested `gnss+imu` preference, and retry next session |
| GPS startup fails transiently | Show a foreground retry action |
| Orientation unreliable or stale in `gnss+imu` | Continue GNSS-only |
| Course absent or stale in `gnss+imu` | Continue GNSS-only |
| Gross phone movement in `gnss+imu` | Drop the course anchor, quarantine IMU prediction, and require a fresh course |
| GNSS absent for more than 3 seconds | Mark speed unavailable |
| Heading sensor absent or registration fails | Continue GPS speed normally and show heading as unavailable |
| Heading sample unreliable or older than 500 ms | Show `-- deg`; never substitute GPS course |
| WMM2025 outside date/altitude validity or magnetic blackout | Show `-- deg` |
| Regatta point fix absent, stale, or worse than 10 m | Preserve the point and warn instead of capturing |

## Architecture

```text
app/src/main/java/com/mightykatun/speedometer/app/
├── AccuracyLevel.kt
├── MainActivity.kt
├── PositionTrailMap.kt
├── PositionTrailProjection.kt
├── SpeedRepositoryViewModel.kt
├── SpeedometerViewModel.kt
├── data/repository/
│   ├── HeadingTracker.kt
│   ├── RepositoryPlatform.kt
│   ├── SpeedRepository.kt
│   └── SpeedRepositoryImpl.kt
├── di/SpeedometerViewModelFactory.kt
└── domain/
    ├── MonotonicClock.kt
    ├── RegattaNavigation.kt
    ├── SessionStatisticsTracker.kt
    ├── SpeedEstimator.kt
    ├── geomagnetic/WorldMagneticModel2025.kt
    ├── model/
    │   ├── EstimateQuality.kt
    │   ├── GnssMeasurement.kt
    │   ├── MaximumCandidate.kt
    │   ├── MotionMeasurement.kt
    │   ├── PositionFix.kt
    │   ├── PortraitDisplayMode.kt
    │   ├── RegattaState.kt
    │   ├── SessionConfig.kt
    │   ├── SessionStatistics.kt
    │   ├── SpeedEstimate.kt
    │   ├── SpeedEstimatorConfig.kt
    │   ├── SpeedometerState.kt
    │   ├── SpeedTrendSample.kt
    │   ├── SpeedUnit.kt
    │   ├── TrackingMode.kt
    │   └── VesselHeading.kt
    ├── time/AndroidElapsedRealtimeClock.kt
    └── util/SpeedConverter.kt
```

No third-party map, location, or sensor-fusion package is used. The position trail is projected and rendered locally without tiles or network access. Android's composite sensors provide orientation and gravity removal; the app-specific two-state estimator remains explicit and JVM-tested.

## Verification

Automated gates:

```bash
./gradlew --no-build-cache clean test lint assembleDebug assembleRelease assembleDebugAndroidTest
```

JVM tests cover repository lifecycle/retry/generation ordering, low-speed preservation, invalid measurements, uncertainty percentage bands, satellite status boundaries, outliers, reacquisition probation, replay-aware maximum candidates, GNSS isolation, GNSS + IMU prediction, trend retention, position-trail gating/compaction/projection, segmented GPX encoding, spike/vertical-shock rejection, violent-motion quarantine, orientation freshness, sensor-rate invariance, course expiry, delayed replay, duplicate inputs, history compaction, stationary evidence, safe mode transitions, stale-data unavailability, WMM2025 NOAA vectors, mount-axis heading, circular filtering, start-line geometry, DTL/TTL uncertainty gates, point lifetime, and portrait display cycling. Compose instrumentation tests cover mode, unit, reset, float, permission recovery, unchanged normal/focused/landscape layouts, regatta formatting and point gestures, and position-trail accessibility and export gestures.

## Field Validation

Automated tests prove deterministic behavior, not real-world sensor accuracy. Production tuning must compare raw GNSS and GNSS + IMU estimates against an independent reference across:

- Parked engine-off and engine-running cases
- Continuous 0.2, 0.5, and 1.0 m/s crawling
- Normal and hard acceleration/braking
- Hills, ramps, rough roads, and speed bumps
- Constant-speed curves and roundabouts
- Urban canyons and 1-10 second GNSS interruptions
- Phone movement while `gnss+imu` is selected
- Multiple mounts and materially different Android devices
- The exact aft-facing portrait sailing mount on cardinal headings, heel/pitch angles, and low-horizontal-field regions
- Surveyed start lines approached, paralleled, crossed, and extended beyond both endpoints at several speeds

Initial acceptance targets:

| Metric | Target |
|---|---|
| Clear-sky moving 95th-percentile absolute error | At most 0.5 m/s |
| Two-second dropout 95th-percentile error | At most 1.0 m/s |
| Constant-speed turn pulse | At most 0.5 m/s |
| False stationary decisions during crawl corpus | Zero |
| Delayed replay versus chronological replay | Floating-point tolerance |
| True-heading error versus surveyed azimuth outside WMM blackout | At most the sensor-reported uncertainty plus 1° |
| Signed DTL versus surveyed line | Within combined GNSS endpoint/current-fix uncertainty |

`gnss+imu` should ship as an accuracy improvement only if it beats GNSS-only error or response latency across the complete validation corpus. Visual smoothness alone is not evidence of accuracy.
