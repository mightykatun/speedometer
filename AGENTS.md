# AGENTS.md - Development Guidelines for Agentic Coding

This file contains build commands, coding standards, and project-specific conventions for agentic coding agents working in this GPS Speedometer Android project.

## Project Overview

A minimalist, privacy-focused Android speedometer built with Kotlin and Jetpack Compose. The app provides real-time GPS data with a high-contrast HUD interface designed for readability.

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material3)
- **Architecture**: MVVM with ViewModel + Compose
- **Min SDK**: 24, **Target SDK**: 35
- **Package**: `com.mightykatun.speedometer.app`

## Build Commands

### Using Gradle (Recommended)
```bash
# Build optimized release APK (requires keystore.properties for signing)
./gradlew assembleRelease

# Build debug APK (faster, no signing required)
./gradlew assembleDebug

# Full build including both variants
./gradlew build

# Clean build artifacts
./gradlew clean

# Run lint checks
./gradlew lint

# Run unit tests (JVM-based)
./gradlew test

# Run instrumentation tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Install debug APK to connected device
./gradlew installDebug
```

### Using Makefile (Convenience)
```bash
# Build release APK (default)
make release

# Build debug APK
make debug

# Build debug and install via ADB
make install

# Clean build artifacts
make clean

# View app-specific logs
make log

# Show all available commands
make help
```

### APK Locations
- **Release**: `app/build/outputs/apk/release/app-release.apk` when locally signed, otherwise `app-release-unsigned.apk`
- **Debug**: `app/build/outputs/apk/debug/app-debug.apk`

## Code Style Guidelines

### Import Organization
Order imports by package type:
1. Standard Java libraries (`java.util.*`)
2. Android framework (`android.*`)
3. AndroidX libraries (`androidx.*`)
4. Third-party libraries (`kotlinx.*`)

Example:
```kotlin
import java.util.Locale
import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
```

### Naming Conventions
- **Classes**: PascalCase (`MainActivity`, `SpeedometerViewModel`)
- **Functions**: camelCase (`updateLocation`, `startTracking`)
- **Variables/Properties**: camelCase (`currentSpeedKmh`, `satelliteCount`)
- **Constants**: UPPER_SNAKE_CASE (when added)
- **Packages**: lowercase with dots (`com.mightykatun.speedometer.app`)

### Type Usage & Null Safety
- Use nullable types (`String?`, `Job?`) when appropriate
- Apply safe calls (`?.`) and Elvis operator (`?:`) for null handling
- Always check nulls before operations (`if (error != null)`)
- Use Compose state functions (`mutableStateOf`, `mutableFloatStateOf`)

### Error Handling Patterns
- Use try-catch blocks for GPS operations and external calls
- Store user-friendly error messages in ViewModel state
- Include permission checks with descriptive error strings
- Graceful degradation on permission denial

```kotlin
try {
    locationManager.requestLocationUpdates(...)
} catch (e: Exception) {
    viewModel.onGpsError("Error starting GPS: ${e.message}")
}
```

### Architecture Patterns
- **Layered architecture**: Domain estimator/statistics code is pure Kotlin; Android clock and sensor/location adapters sit at platform boundaries; Compose and the ViewModel form the presentation layer.
- **MVVM**: MainActivity handles UI/lifecycle and the ViewModel manages presentation state and session statistics.
- **Lifecycle-aware**: Use `lifecycleScope.launch` for coroutines.
- **Compose**: Use `@Composable` functions for UI components.
- **Separation**: Private functions for internal logic, public for external interactions.

## Project Structure

```
app/src/main/java/com/mightykatun/speedometer/app/
├── AccuracyLevel.kt                  # Presentation uncertainty bands
├── MainActivity.kt                    # Activity and UI composition
├── SpeedRepositoryViewModel.kt        # Configuration-stable repository owner
├── SpeedometerViewModel.kt            # State management and UI logic
├── domain/                          # Business logic and clock contract
│   ├── model/                         # Data models
│   │   ├── GnssMeasurement.kt
│   │   ├── EstimateQuality.kt
│   │   ├── MaximumCandidate.kt
│   │   ├── MotionMeasurement.kt
│   │   ├── SpeedEstimate.kt
│   │   ├── SpeedEstimatorConfig.kt
│   │   ├── TrackingMode.kt
│   │   ├── SpeedometerState.kt
│   │   ├── SpeedTrendSample.kt
│   │   ├── SpeedUnit.kt
│   │   ├── SessionConfig.kt
│   │   └── SessionStatistics.kt
│   ├── util/                          # Utilities
│   │   └── SpeedConverter.kt
│   ├── SpeedEstimator.kt             # Confidence-aware GNSS/IMU fusion
│   ├── SessionStatisticsTracker.kt      # Session tracking logic
│   ├── MonotonicClock.kt              # Monotonic time abstraction
│   └── time/
│       └── AndroidElapsedRealtimeClock.kt # Android clock implementation
├── data/                           # Data layer
│   └── repository/                     # Repository pattern
│       ├── RepositoryPlatform.kt          # Injectable Android platform boundaries
│       ├── SpeedRepository.kt             # Speed update contract
│       └── SpeedRepositoryImpl.kt         # Serialized GNSS/sensor acquisition
├── di/                             # Dependency injection
│   └── SpeedometerViewModelFactory.kt    # ViewModel factory
```

The manifest is at `app/src/main/AndroidManifest.xml`.

## Key Dependencies & Versions

- **Android Gradle Plugin**: 8.13.2
- **Kotlin**: 1.9.24
- **Compose Compiler**: 1.5.14
- **Compose BOM**: 2023.08.00
- **Target SDK**: 35, **Min SDK**: 24

## Testing Guidelines

### Unit Tests
- Add JUnit/Kotlin test dependencies if adding unit tests
- Test ViewModel logic and data transformation
- Use `@Test` and standard JUnit assertions

### Instrumentation Tests
- Test UI interactions and Android-specific behavior
- Use Compose testing utilities for UI tests
- Run with `./gradlew connectedAndroidTest`

## Signing & Release Builds

The project supports automatic release signing via `keystore.properties` in project root:

```properties
keyAlias=your-key-alias
keyPassword=your-key-password
storeFile=path/to/keystore.jks
storePassword=your-store-password
```

If `keystore.properties` doesn't exist, release builds will run unsigned for debugging.

## Development Workflow

1. **Make Changes**: Edit Kotlin files following the style guidelines
2. **Run Lint**: `./gradlew lint` to check for issues
3. **Build**: `make debug` for quick builds, `make release` for production
4. **Test**: `make install` to test on connected device
5. **Verify**: Check logs with `make log` if issues occur

## Code Quality Notes

- No custom lint rules configured - uses default Android lint
- ProGuard enabled for release builds with default optimization
- No external style configuration (.editorconfig, ktlint) present
- Relies on default Android Studio/Kotlin formatting

## Special Considerations

- **Privacy-focused**: No internet permissions or tracking libraries
- **Battery-conscious**: GPS and motion listeners are disconnected when the Activity stops outside configuration recreation
- **Session-based**: All data resets when app goes to background
- **Real-time**: Uses one HandlerThread for ordered GNSS and motion processing
- **Accuracy-aware**: GNSS is the absolute source in `gnss` and `gnss+imu`; zero-seeded `imu` is explicitly relative and uncertainty-bounded
- **Mode-aware**: Handheld mode ignores motion sensors, fixed mode requires a rigid mount, and IMU-only requires a stopped reset with the phone's top edge forward

## Common Tasks

### Adding New Features
1. Add state variables to `SpeedometerViewModel.kt`
2. Update UI in `@Composable` functions in `MainActivity.kt`
3. Handle permissions if accessing new Android APIs

### Adding Dependencies
Add to `dependencies` block in `app/build.gradle.kts`:
```kotlin
implementation("group:artifact:version")
```

### Updating Android Manifest
Add permissions, activities, or features in `app/src/main/AndroidManifest.xml`
