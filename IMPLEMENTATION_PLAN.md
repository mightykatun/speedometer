# GNSS and Motion Speed Fusion

- [x] Research Android GNSS/IMU behavior and audit available libraries.
- [x] Map the existing speed, timeout, statistics, and UI pipeline.
- [x] Add explicit measurement, estimate, quality, mode, and estimator configuration models.
- [x] Implement and unit-test the confidence-aware GNSS/IMU speed estimator.
- [x] Replace location-only acquisition with serialized GNSS and motion acquisition.
- [x] Update ViewModel and session statistics to consume quality-aware estimates.
- [x] Add the persisted fixed/handheld control and visible accuracy indication.
- [x] Remove the hard speed floor, stale-fix replay, and synthetic-zero watchdog.
- [x] Update repository documentation for the final behavior and architecture.
- [x] Pass clean unit tests, lint, debug assembly, and final diff checks.
- [x] Record device field-validation requirements and residual limitations honestly.
