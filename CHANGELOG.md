# Changelog

All notable changes to the Argus Android SDK are documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project adheres to [Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-06-12

### Added

- `configUpdated` per-update signal — consumers can now observe individual flag changes as they arrive from Firestore, not just the aggregate refresh.
- Moshi ergonomics: convenience extensions for typed flag value extraction.

### Changed

- Modernised build toolchain to Kotlin 2.x + KSP (replacing KAPT) + Firebase BoM 34.

## [1.0.2] - 2026-06-08

### Fixed

- `ArgusFlagResolver` now evaluates `languageFilter` conditions, reaching parity with iOS and the server. Previously any non-empty language filter silently short-circuited to no-match, so language-targeted conditions created in the web editor never fired on Android.

## [1.0.1] - 2026-05-29

### Fixed

- `issueStreamToken` now sends `platform=android` so the server returns the correct Firebase config for the Android app. Without this, the SDK received the iOS config and the Firestore listener silently failed to connect.

## [1.0.0] - 2026-05-24

### Added

- API-key authentication (replaces prior auth model).
- Auto-detect environment (dev / staging / prod) from the host app's build context.
- Real-time push via Firestore snapshot listeners — flags update live without polling.
- Self-configuring Firebase: the SDK initialises its own `FirebaseApp` from the server response, so the host app's Firebase project is never touched.
- Per-product, per-environment API key support (`argus_<env>_<48-hex>`).
- R8/ProGuard keep rule documented for `ARGUS_TRACK` reflective lookup.

### Known issues

- **v1.0.0 crashes on launch** due to a Firebase self-configuration race. Fixed in v1.0.1+. **Pin 1.0.1 or later.**
- Flag value changes do not propagate to a running app — the SDK evaluates flags on init only. An app restart picks up new values immediately. **Resolved in v1.1.0** by the `configUpdated` signal (#21).

  The resolution is SDK-side only: v1.1.0 emits the signal, consumers still have to observe it. A host app that resolves flags once at ViewModel init sees no change until it collects that flow.

[1.1.0]: https://github.com/98ChimpInc/argus-android-sdk/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/98ChimpInc/argus-android-sdk/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/98ChimpInc/argus-android-sdk/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/98ChimpInc/argus-android-sdk/releases/tag/v1.0.0
