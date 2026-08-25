# Speedometer - Product and Engineering Documentation

## Overview

- **App:** Speedometer
- **Package:** `com.mightykatun.speedometer.app`
- **Platform:** Native Android, min SDK 24, target SDK 35
- **Purpose:** Privacy-focused, accuracy-aware vehicle speed display
- **Network access:** None

The app uses Android GNSS speed as its absolute speed source. In `gnss+imu` mode, Android's linear-acceleration and rotation-vector sensors provide bounded short-term prediction between GNSS fixes. Inertial data never replaces GNSS indefinitely.

## Screen

The app remains a single-screen HUD with no navigation graph.

### Top Left

- Shows recent satellites reported as used-in-fix by Android's GNSS status callback; stale evidence returns to zero
- GNSS status is green at three or more satellites and red below three
- Satellite count is status information, not a speed-accuracy measurement

### Top Right

- Persisted text-only `gnss` / `gnss+imu` selector matching the HUD labels
- Defaults to `gnss`
- Disabled when the device lacks either linear acceleration or rotation-vector sensors
- A persisted global refresh selector below the mode control cycles through 0.5, 1, and 2 seconds
- Hidden in Picture-in-Picture mode

### Center

- Current speed to two decimal places
- Tap the unit to cycle km/h, mph, knots, and m/s
- Low speeds remain visible; there is no 1.5 km/h display floor
- `--` means speed is not currently defensible
- A small colored dot in Picture-in-Picture and compact `± value unit` line report one-standard-deviation uncertainty
- The uncertainty indicator is green at or below 10 percent of current speed, amber through 20 percent, and red above 20 percent or when the percentage is undefined
- The uncertainty line is hidden while acquiring the first required GNSS fix or IMU sample

Estimate states:

| State | Indicator | Meaning |
|---|---|---|
| Tracking | Number available | Recent GNSS correction and bounded uncertainty |
| Estimated | Number available | Number is available but uncertainty or fix age is elevated |
| Acquiring | `--` | No valid GNSS speed seed or first IMU sample yet |
| Speed unavailable | `--` | Last defensible estimate is too old or too uncertain; reports `no signal` only when the recent satellite count is zero |

### Bottom

- Top speed records accepted raw GNSS candidates after a two-second warmup, with at least three satellites and no estimator probation
- Top satellites tracks the session maximum independently
- A live-smoothed 30-second trend tail fades toward the left and animates between global UI refreshes
- `reset` restarts acquisition and session statistics from the normal HUD
- Text-only `float` enters Picture-in-Picture on Android 8+

## Tracking Modes

### GNSS

`gnss` is the safe default. It confidence-weights GNSS speed and ignores all IMU input because hand movement cannot be separated reliably from vehicle acceleration.

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

## Data Pipeline

```text
LocationManager GPS_PROVIDER ─┐
GnssStatus satellite count ───┤
TYPE_LINEAR_ACCELERATION ──────┼─> SpeedRepositoryImpl worker thread
TYPE_ROTATION_VECTOR ──────────┘             │
                                             v
                                      SpeedEstimator
                                             │
                                             v
                                      SpeedEstimate
                                             │
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
- Optional bearing and bearing uncertainty
- Horizontal positional accuracy, kept separate from speed uncertainty
- Local magnetic declination
- Temporally bounded used-in-fix evidence from the latest preceding GNSS status callback
- `Location.elapsedRealtimeNanos` measurement time

`MotionMeasurement` preserves transformed East/North/Up linear acceleration, device yaw/pitch/roll, orientation reliability, the acceleration timestamp, and the exact rotation-vector timestamp used for that transform.

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

The estimator retains five seconds of timestamped inputs and state checkpoints. A GNSS measurement delayed by up to three seconds is inserted at its measurement epoch, then all later events are replayed. Applying an old fix at callback time is prohibited. GNSS, motion, and orientation identities are deduplicated by stream and elapsed-realtime timestamp. History uses indexed duplicate checks, binary insertion, logical-prefix pruning, and amortized compaction.

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
- Session statistics reset when the app backgrounds
- Display unit, tracking mode, and global refresh interval persist locally
- No location, motion, or session history leaves the device

## Errors and Fallbacks

| Condition | Behavior |
|---|---|
| Fine location denied | Explain that precise location is required |
| GPS provider disabled | Show `gps provider disabled` inline until the provider is enabled, then wait for a fresh speed-bearing fix |
| Speed absent or invalid | Ignore the measurement; never synthesize zero |
| Speed uncertainty too poor | Preserve the prior estimate until it becomes stale |
| IMU sensors absent | Disable `gnss+imu` |
| Orientation unreliable or stale in `gnss+imu` | Continue GNSS-only |
| Course absent or stale in `gnss+imu` | Continue GNSS-only |
| Gross phone movement in `gnss+imu` | Drop the course anchor, quarantine IMU prediction, and require a fresh course |
| GNSS absent for more than 3 seconds | Mark speed unavailable |

## Architecture

```text
app/src/main/java/com/mightykatun/speedometer/app/
├── AccuracyLevel.kt
├── MainActivity.kt
├── SpeedRepositoryViewModel.kt
├── SpeedometerViewModel.kt
├── data/repository/
│   ├── RepositoryPlatform.kt
│   ├── SpeedRepository.kt
│   └── SpeedRepositoryImpl.kt
├── di/SpeedometerViewModelFactory.kt
└── domain/
    ├── SpeedEstimator.kt
    ├── SessionStatisticsTracker.kt
    ├── MonotonicClock.kt
    ├── model/
    │   ├── EstimateQuality.kt
    │   ├── GnssMeasurement.kt
    │   ├── MaximumCandidate.kt
    │   ├── MotionMeasurement.kt
    │   ├── SessionConfig.kt
    │   ├── SessionStatistics.kt
    │   ├── SpeedEstimate.kt
    │   ├── SpeedEstimatorConfig.kt
    │   ├── SpeedometerState.kt
    │   ├── SpeedTrendSample.kt
    │   ├── SpeedUnit.kt
    │   └── TrackingMode.kt
    ├── time/AndroidElapsedRealtimeClock.kt
    └── util/SpeedConverter.kt
```

No third-party location or sensor-fusion package is used. Android's composite sensors provide orientation and gravity removal; the app-specific two-state estimator remains explicit and JVM-tested.

## Verification

Automated gates:

```bash
./gradlew --no-build-cache clean test lint assembleDebug assembleRelease assembleDebugAndroidTest
```

JVM tests cover repository lifecycle/retry/generation ordering, low-speed preservation, invalid measurements, uncertainty percentage bands, outliers, reacquisition probation, replay-aware maximum candidates, GNSS isolation, GNSS + IMU prediction, trend retention, spike/vertical-shock rejection, violent-motion quarantine, orientation freshness, sensor-rate invariance, course expiry, delayed replay, duplicate inputs, history compaction, stationary evidence, safe mode transitions, and stale-data unavailability. Compose instrumentation tests cover mode, unit, reset, float, and permission-recovery actions.

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

Initial acceptance targets:

| Metric | Target |
|---|---|
| Clear-sky moving 95th-percentile absolute error | At most 0.5 m/s |
| Two-second dropout 95th-percentile error | At most 1.0 m/s |
| Constant-speed turn pulse | At most 0.5 m/s |
| False stationary decisions during crawl corpus | Zero |
| Delayed replay versus chronological replay | Floating-point tolerance |

`gnss+imu` should ship as an accuracy improvement only if it beats GNSS-only error or response latency across the complete validation corpus. Visual smoothness alone is not evidence of accuracy.
