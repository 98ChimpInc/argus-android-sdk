# Argus Android SDK

Drop-in replacement for the Firebase Remote Config-backed `FeatureFlagServiceImpl` in the SmartHome+ Android app. Fetches resolved flag values from the Argus HTTP endpoint instead of Firebase Remote Config.

## Architecture

The SDK implements the `FeatureFlagService` interface. Every `@Inject FeatureFlagService` usage in the host app works unchanged ... the only change is where flag values come from.

### Key Components

- **`ArgusFeatureFlagServiceImpl`** ... the main service implementation. Fetches flags from the Argus endpoint, caches them in a `ConcurrentHashMap`, and exposes them through the full `FeatureFlagService` interface.
- **`ArgusConfiguration`** ... data class holding the endpoint URL, tenant ID, environment, and user ID. Use `ArgusConfiguration.create(context, ...)` to have `environment` auto-detected from the host app's build context (see [Environment auto-detection](#environment-auto-detection)).
- **`FNV1a`** ... FNV-1a 32-bit hash for rollout bucketing. Produces identical output to the JavaScript reference in the Argus backend.
- **`ArgusModule`** ... optional standalone Hilt module for use outside the SmartHome+ app.

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
project(":argus-sdk").projectDir = file("../telus-smarthome-argus-android-sdk/argus-sdk")
```

In the host app's `app/build.gradle.kts`:

```kotlin
implementation(project(":argus-sdk"))
```

### Bootstrap Toggle

The `ARGUS_ENABLED` feature flag (read from Firebase Remote Config) controls which implementation is wired at runtime. Default is `false` (Firebase RC). Set to `true` in the Remote Config console to switch to Argus.

## Environment auto-detection

Host apps can let the SDK infer `environment` from the build context instead of hard-coding it. Use `ArgusConfiguration.create(...)` and omit the `environment` argument:

```kotlin
val config = ArgusConfiguration.create(
    context = appContext,
    baseURL = BuildConfig.ARGUS_BASE_URL,
    tenantId = "northwind",
    userId = currentUser.uid
)
```

### Detection priority

| Build signal | Detected environment |
|---|---|
| Host app has `ApplicationInfo.FLAG_DEBUGGABLE` set (debug build, or `android:debuggable="true"`) | `"dev"` |
| Release build with a `BuildConfig.ARGUS_TRACK` String constant on the host app | The value of `ARGUS_TRACK` (verbatim) |
| Release build with no `ARGUS_TRACK` | `"prod"` |

You can always short-circuit detection by passing `environment` explicitly:

```kotlin
val config = ArgusConfiguration.create(
    context = appContext,
    baseURL = BuildConfig.ARGUS_BASE_URL,
    tenantId = "northwind",
    userId = currentUser.uid,
    environment = "staging"   // explicit override
)
```

The 4-arg `ArgusConfiguration(baseURL, tenantId, environment, userId)` constructor is unchanged for callers that already manage `environment` themselves.

### Honest caveat on staging vs. prod

Google Play Internal Testing builds and Google Play Production builds are byte-identical from the device's perspective ... there is no runtime signal that distinguishes them. The staging-vs-prod distinction needs a build-time signal you set yourself. Recommended pattern using product flavors:

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        debug   { /* FLAG_DEBUGGABLE set → SDK picks "dev" */ }
        release { /* default → SDK picks "prod" */ }
    }

    flavorDimensions += "track"
    productFlavors {
        create("internal") {
            dimension = "track"
            buildConfigField("String", "ARGUS_TRACK", "\"staging\"")
        }
        create("production") {
            dimension = "track"
            buildConfigField("String", "ARGUS_TRACK", "\"prod\"")
        }
    }

    // Required for `buildConfigField` on AGP 8+
    buildFeatures { buildConfig = true }
}
```

The SDK reads `ARGUS_TRACK` from your generated `BuildConfig` reflectively, so it never takes a compile-time dep on your build configuration ... if the constant isn't there, it silently falls through to the default.

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
