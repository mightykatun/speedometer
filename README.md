# GPS Speedometer

A minimalist, privacy-focused Android speedometer built with Kotlin and Jetpack Compose. It provides real-time GPS data with a high-contrast HUD interface designed for readability.

## Motivation

I got tired of dirty ad filled, user tracking, etc speedometer apps, so I made this simple one. Download the APK from Releases or build from source.

## Installation

Download and install the APK from [Releases](https://github.com/mightykatun/speedometer/releases/latest) or build from source.

## Features

* **Accuracy-aware Speed:** GNSS speed is filtered by its reported uncertainty without hiding valid low-speed readings. The display shows its current uncertainty in km/h, mph, knots, or m/s.
* **Tracking Modes:** `gnss` uses satellite speed only. `gnss+imu` adds bounded prediction for a rigidly mounted phone.
* **Speed Trend:** A live-smoothed scrolling tail shows the most recent 30 seconds without exposing discrete refresh steps.
* **Offline Position Trail:** A north-up, auto-fitting monochrome trace shows GNSS altitude, current course, and the complete in-memory session path without map tiles or network access. Double-tap the map to save a GPX trace to Downloads on Android 10+.
* **Focused Speed Display:** Double-tap the speed numbers to hide the surrounding HUD without resetting the session. Landscape uses the enlarged focused display automatically.
* **Refresh Control:** The complete measurement UI refreshes every 0.5, 1, or 2 seconds while acquisition and statistics continue at full rate.
* **Satellite Status:** The used-in-fix count is red at zero, orange from one through five, and green at six or more.
* **Session Statistics:**
    * **Top Speed:** Tracks trusted raw GNSS maxima; inertial prediction never increases it.
    * **Top Satellites:** Tracks the maximum number of satellites used in a fix.
* **Smart Logic:**
    * **2-Second Warmup:** Max speed tracking begins 2 seconds after GPS lock while retaining GNSS uncertainty, satellite, and outlier gates.
    * **Confidence Weighting:** Android's speed uncertainty controls how strongly each GNSS reading corrects the estimate.
    * **Short Dropout Bridging:** `gnss+imu` uses linear acceleration for no more than 3 seconds without trustworthy GNSS.
    * **No Artificial Floor:** Slow movement remains visible; zero is used only after strong stationary evidence.
    * **Honest Signal Loss:** Unreliable stale estimates become unavailable instead of snapping to zero.
* **Privacy & Cleanliness:**
    * **No Ads:** Completely free and clean interface.
    * **No Tracking:** No analytics, no data collection, no internet permission required.
    * **Explicit Export:** Location data leaves app memory only when you double-tap the trail to save a GPX file.
    * **No Background Drain:** App completely stops GPS usage when minimized to save battery.

## Session Behavior

> **Note:** This app is designed as an active dashboard.

* **Active Only:** Speed and stats update only while the screen is on and the app is visible.
* **Session Continuity:** Minimizing the app, turning off the screen, or switching apps clears live readings but keeps in-memory session maxima and trail segments until `reset` or process death.
* **Battery Safe:** The app aggressively disconnects from the GPS hardware the moment it loses focus.

## Preview

<p align="center">
  <img src="screenshots/app-preview-landscape.jpg" width="300" alt="App Screenshot Landscape"/>
</p>
<p align="center">
  <img src="screenshots/app-preview-portrait.jpg" width="300" alt="App Screenshot Portrait"/>
</p>

## Tech Stack

* **Language:** Kotlin
* **UI Framework:** Jetpack Compose (Material3)
* **API:** Android `LocationManager`, `GnssStatus`, and `SensorManager`
* **Architecture:** Lifecycle-aware MVVM with a pure, timestamped speed estimator.
* **Min SDK:** 24
* **Target SDK:** 35

## Build & Install (CLI)

1.  **Clone**
    ```bash
    git clone <repo_url>
    cd gps-speedometer
    ```

2.  **Build & Install (Using Makefile)**
    ```bash
    make install
    ```
    *Or manually for local testing:* `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Releases

Pushing a version tag such as `v2.0.0` runs the GitHub Actions release workflow. It verifies the version, runs tests and lint, builds the APK, creates an explicit source ZIP, generates SHA-256 checksums, and publishes a stable production release marked as latest. The workflow does not publish beta, test, or prerelease builds.

Production signing uses a persistent certificate stored in the protected `release-signing` environment, allowing later APKs with higher version codes to update the installed app in place. Missing or inconsistent signing secrets fail the release. See [RELEASING.md](RELEASING.md).

## License

MIT
