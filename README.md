# Argus Android SDK

Drop-in replacement for a Firebase Remote Config-backed `FeatureFlagServiceImpl` in an Android app. Fetches resolved flag values from the Argus HTTP endpoint instead of Firebase Remote Config.

## Architecture

The SDK implements the `FeatureFlagService` interface. Every `@Inject FeatureFlagService` usage in the host app works unchanged ... the only change is where flag values come from.

### Key Components

- **`ArgusFeatureFlagServiceImpl`** ... the main service implementation. Fetches flags from the Argus endpoint, caches them in a `ConcurrentHashMap`, and exposes them through the full `FeatureFlagService` interface.
- **`ArgusConfiguration`** ... data class holding the endpoint URL, tenant ID, environment, and user ID.
- **`FNV1a`** ... FNV-1a 32-bit hash for rollout bucketing. Produces identical output to the JavaScript reference in the Argus backend.
- **`ArgusModule`** ... optional standalone Hilt module for use when the SDK is not wired through the host app's own Hilt graph.

### Design Principles

1. **Zero call-site changes** ... every existing `@Inject FeatureFlagService` usage works unchanged.
2. **Non-blocking initialisation** ... `initialize()` returns immediately. Flag fetch runs in a coroutine.
3. **Offline resilience** ... falls back to `FeatureFlag.getDefaultValues()` when the endpoint is unreachable.
4. **Platform-aware JSON extraction** ... extracts the `"android"` sub-object from platform-split JSON before deserialisation.

## Integration

### As a Local Module

In the host app's `settings.gradle.kts`:

```kotlin
include(":argus-sdk")
project(":argus-sdk").projectDir = file("../argus-android-sdk/argus-sdk")
```

In the host app's `app/build.gradle.kts`:

```kotlin
implementation(project(":argus-sdk"))
```

### Bootstrap Toggle

The `ARGUS_ENABLED` feature flag (read from Firebase Remote Config) controls which implementation is wired at runtime. Default is `false` (Firebase RC). Set to `true` in the Remote Config console to switch to Argus.

## Running Tests

```bash
./gradlew :argus-sdk:test
```

## Dependencies

| Dependency | Reason |
|---|---|
| `firebase-auth-ktx` | Obtain ID token for Argus endpoint auth |
| `hilt-android` | `@Inject` / `@Singleton` annotations |
| `moshi` + `moshi-kotlin` | JSON deserialisation matching existing app pattern |
| `okhttp` | HTTP client matching existing app pattern |
| `kotlinx-coroutines-*` | Async fetch, `StateFlow`, `await()` on Tasks |
| `timber` | Logging matching existing app pattern |
