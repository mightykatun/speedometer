# Speedometer - Product and Engineering Documentation

## Overview

- **App:** Speedometer
- **Package:** `com.mightykatun.speedometer.app`
- **Platform:** Native Android, min SDK 24, target SDK 35
- **Purpose:** Privacy-focused, accuracy-aware vehicle speed display
- **Network access:** None

The app uses Android GNSS speed as its absolute speed source. In fixed mode, Android's linear-acceleration and rotation-vector sensors provide bounded short-term prediction between GNSS fixes. Inertial data never replaces GNSS indefinitely.

## Screen

The app remains a single-screen HUD with no navigation graph.

### Top Left

- Satellites used in the current GNSS fix
- Green at three or more satellites, red below three
- Satellite count is status information, not a speed-accuracy measurement

### Top Right

- Persisted text-only `handheld` / `fixed` switch matching the HUD labels
- Defaults to handheld
- Disabled when the device lacks either linear acceleration or rotation-vector sensors
- Hidden in Picture-in-Picture mode

### Center

- Current speed to two decimal places
- Tap the unit to cycle km/h, mph, knots, and m/s
- Low speeds remain visible; there is no 1.5 km/h display floor
- `--` means speed is not currently defensible
- A small colored dot and compact `+/- value unit` line report estimator quality and one-standard-deviation uncertainty

Accuracy states:

| State | Indicator | Meaning |
|---|---|---|
| Tracking | Green | Recent GNSS correction and bounded uncertainty |
| Estimated | Amber | Number is available but uncertainty or fix age is elevated |
| Acquiring GPS | Gray | No valid speed seed yet |
| Speed unavailable | Red | Last trustworthy GNSS correction is too old |

### Bottom

- Top speed records only high-confidence estimates after the five-second warmup and with at least three satellites
- Top satellites tracks the session maximum independently
- `float` enters Picture-in-Picture on Android 8+

## Tracking Modes

### Handheld

Handheld mode is the safe default. It confidence-weights GNSS speed and ignores all IMU input because hand movement cannot be separated reliably from vehicle acceleration.

### Fixed

Fixed mode assumes the phone is rigidly mounted. It:

1. Uses `TYPE_ROTATION_VECTOR` to transform device acceleration into magnetic East/North/Up.
2. Anchors travel direction with a quality-gated GNSS bearing and local magnetic declination.
3. Tracks turns between GNSS fixes from relative yaw change.
4. Projects horizontal acceleration along the current travel direction.
5. Predicts speed and acceleration bias between GNSS corrections.
6. Falls back to GNSS-only whenever orientation, course, timestamp, or sensor quality is inadequate.

Fixed mode does not promise tunnel navigation. Inertial propagation is limited to three seconds because consumer accelerometer bias creates rapidly growing velocity error.

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

`SpeedRepositoryImpl` serializes location and sensor callbacks on one `HandlerThread`. It emits UI estimates at no more than 10 Hz on the main thread. Starting and stopping are idempotent, and all listeners are removed in `onStop`.

GNSS satellite callbacks only update satellite state. They never replay a cached `Location` and never refresh fix age.

## Measurement Semantics

`GnssMeasurement` preserves:

- Optional speed from `Location.hasSpeed()`
- Optional 68-percent speed uncertainty on API 26+
- Optional bearing and bearing uncertainty
- Horizontal positional accuracy, kept separate from speed uncertainty
- Local magnetic declination
- Satellites used in fix
- `Location.elapsedRealtimeNanos` measurement time

`MotionMeasurement` preserves transformed East/North/Up linear acceleration, device yaw/pitch/roll, orientation reliability, and `SensorEvent.timestamp`.

Both timestamps use Android's elapsed-realtime-since-boot timebase. Callback arrival time and wall-clock time are not used for filtering.

## Estimator

### GNSS Correction

GNSS speed is authoritative. Measurement variance starts from Android's speed uncertainty:

```text
sigma = max(reportedSpeedAccuracy, 0.2 m/s)
R = (1.5 * sigma)^2
```

Missing speed accuracy receives a conservative `2.0 m/s` uncertainty. Measurements above `3.0 m/s` reported uncertainty do not correct the estimate. Poor horizontal position accuracy alone does not force speed to zero.

An innovation gate rejects isolated statistically implausible speed jumps. Three consecutive high-quality, mutually consistent fixes trigger controlled reacquisition so the filter cannot lock out after a genuine speed change or GNSS outage.

### Fixed-Mode Prediction

The fixed-mode state is forward speed and longitudinal acceleration bias:

```text
x = [speed, bias]
speed' = speed + (longitudinalAcceleration - bias) * dt
bias' = bias
```

All covariance calculations use `Double`. GNSS corrections use the Joseph covariance form for numerical stability.

### Delayed Measurements

The estimator retains five seconds of timestamped inputs and state checkpoints. A GNSS measurement delayed by up to three seconds is inserted at its measurement epoch, then all later events are replayed. Applying an old fix at callback time is prohibited.

### Low Speed and Stationarity

There is no arbitrary minimum display speed. Valid values such as 0.1, 0.2, or 0.5 m/s remain numeric.

Zero requires repeated, high-confidence GNSS readings of exactly zero over at least two seconds. Every accepted positive speed immediately exits stationarity, however small it is. Fixed mode additionally requires quiet longitudinal acceleration.

The internal Gaussian state is not clipped. Only the published result is constrained to the physically valid non-negative speed domain.

### Signal Age

| Condition | Result |
|---|---|
| No accepted speed | Acquiring, no number |
| Recent correction with 2-sigma uncertainty at or below 1 m/s | Tracking |
| Correction age up to 3 seconds | Estimated/degraded number |
| Correction older than 3 seconds | Unavailable, `--` |
| Strong stationary evidence | Valid `0.00` |

No watchdog injects fake zero readings.

## Session Behavior

- Acquisition starts in `onStart` after precise-location permission is available
- Acquisition and all sensor listeners stop in `onStop`
- Session statistics reset when the app backgrounds
- Display unit and tracking mode persist locally
- No location, motion, or session history leaves the device

## Errors and Fallbacks

| Condition | Behavior |
|---|---|
| Fine location denied | Explain that precise location is required |
| GPS provider disabled | Display `gps provider disabled` |
| Speed absent or invalid | Ignore the measurement; never synthesize zero |
| Speed uncertainty too poor | Preserve the prior estimate until it becomes stale |
| Fixed-mode sensors absent | Disable fixed mode |
| Orientation unreliable or stale | Continue GNSS-only |
| Course absent or stale | Continue GNSS-only |
| Gross phone movement | Drop the course anchor and require GNSS reacquisition |
| GNSS absent for more than 3 seconds | Mark speed unavailable |

## Architecture

```text
app/src/main/java/com/mightykatun/speedometer/app/
├── MainActivity.kt
├── SpeedometerViewModel.kt
├── data/repository/
│   ├── SpeedRepository.kt
│   └── SpeedRepositoryImpl.kt
├── di/SpeedometerViewModelFactory.kt
└── domain/
    ├── SpeedEstimator.kt
    ├── SessionStatisticsTracker.kt
    ├── TimeProvider.kt
    ├── model/
    │   ├── EstimateQuality.kt
    │   ├── GnssMeasurement.kt
    │   ├── MotionMeasurement.kt
    │   ├── SessionConfig.kt
    │   ├── SpeedEstimate.kt
    │   ├── SpeedEstimatorConfig.kt
    │   ├── SpeedometerState.kt
    │   ├── SpeedUnit.kt
    │   └── TrackingMode.kt
    ├── time/ProductionTimeProvider.kt
    └── util/SpeedConverter.kt
```

No third-party location or sensor-fusion package is used. Android's composite sensors provide orientation/gravity removal; the app-specific two-state estimator remains explicit and JVM-tested.

## Verification

Automated gates:

```bash
./gradlew clean test lint assembleDebug
```

Estimator tests cover low-speed preservation, invalid measurements, uncertainty gating, outliers, reacquisition, handheld isolation, fixed-mode prediction, course expiry, delayed replay, duplicate fixes, stationary evidence, mode reset, and stale-data unavailability.

## Field Validation

Automated tests prove deterministic behavior, not real-world sensor accuracy. Production tuning must compare raw GNSS, handheld estimates, and fixed estimates against an independent reference across:

- Parked engine-off and engine-running cases
- Continuous 0.2, 0.5, and 1.0 m/s crawling
- Normal and hard acceleration/braking
- Hills, ramps, rough roads, and speed bumps
- Constant-speed curves and roundabouts
- Urban canyons and 1-10 second GNSS interruptions
- Phone movement while fixed mode is selected
- Multiple mounts and materially different Android devices

Initial acceptance targets:

| Metric | Target |
|---|---|
| Clear-sky moving 95th-percentile absolute error | At most 0.5 m/s |
| Two-second dropout 95th-percentile error | At most 1.0 m/s |
| Constant-speed turn pulse | At most 0.5 m/s |
| False stationary decisions during crawl corpus | Zero |
| Delayed replay versus chronological replay | Floating-point tolerance |

Fixed mode should ship as an accuracy improvement only if it beats GNSS-only error or response latency across the complete validation corpus. Visual smoothness alone is not evidence of accuracy.
