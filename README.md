# GPS Speedometer

A minimalist, privacy-focused Android speedometer built with Kotlin and Jetpack Compose. It provides real-time GPS data with a high-contrast HUD interface designed for readability.

## Motivation

I got tired of dirty ad filled, user tracking, etc speedometer apps, so I made this simple one. Download the APK from Releases or build from source.

## Installation

Download and install the APK from [Releases](https://github.com/mightykatun/speedometer/releases/latest) or build from source.

## Features

* **Accuracy-aware Speed:** GNSS speed is filtered by its reported uncertainty without hiding valid low-speed readings. The display shows its current uncertainty in km/h, mph, knots, or m/s.
* **Tracking Modes:** `gnss` uses satellite speed only. `gnss+imu` uses a rigidly mounted phone's motion sensors for short-term smoothing between GNSS fixes and falls back safely when sensor quality is insufficient.
* **Satellite Status:** Real-time GNSS satellite count with a color-coded status indicator (Red/Green).
* **Session Statistics:**
    * **Top Speed:** Tracks the maximum speed reached in the current session.
    * **Top Satellites:** Tracks the maximum number of satellites connected.
* **Smart Logic:**
    * **5-Second Warmup:** Max speed tracking only begins 5 seconds after GPS lock to prevent initialization spikes.
    * **Confidence Weighting:** Android's speed uncertainty controls how strongly each GNSS reading corrects the estimate.
    * **Short Dropout Bridging:** `gnss+imu` uses linear acceleration for no more than 3 seconds without trustworthy GNSS.
    * **No Artificial Floor:** Slow movement remains visible; zero is used only after strong stationary evidence.
    * **Honest Signal Loss:** Unreliable stale estimates become unavailable instead of snapping to zero.
* **Privacy & Cleanliness:**
    * **No Ads:** Completely free and clean interface.
    * **No Tracking:** No analytics, no data collection, no internet permission required.
    * **No Background Drain:** App completely stops GPS usage when minimized to save battery.

## Session Behavior

> **Note:** This app is designed as an active dashboard.

* **Active Only:** Speed and stats are tracked only while the screen is on and the app is visible.
* **Auto-Reset:** Minimizing the app, turning off the screen, or switching apps **immediately wipes** all session data (current speed, max speed, satellite counts).
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

Pushing a version tag such as `v1.2.2` runs the GitHub Actions release workflow. It verifies the version, runs tests and lint, builds the APK, creates an explicit source ZIP, generates SHA-256 checksums, and publishes all assets to GitHub Releases.

Production signing uses repository secrets. Without signing secrets, the workflow publishes a clearly named debug-signed test APK as a prerelease. See [RELEASING.md](RELEASING.md).

## License

MIT
