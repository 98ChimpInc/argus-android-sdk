# Argus Android SDK

Drop-in replacement for a Firebase Remote Config-backed `FeatureFlagServiceImpl` in an Android app. Fetches resolved flag values from the Argus HTTP endpoint instead of Firebase Remote Config.

> **apiKeys are scoped per Product, not per Customer.** A Customer workspace
> with multiple Products (for example, a web app and a mobile app under the
> same studio account) has multiple apiKeys ... one per Product. If your
> Android app talks to more than one Argus Product, instantiate one
> `ArgusFeatureFlagServiceImpl` per Product, each with its own
> `ArgusConfiguration.apiKey`. See [Multiple Products](#multiple-products) below.

## Architecture

The SDK implements the `FeatureFlagService` interface. Every `@Inject FeatureFlagService` usage in the host app works unchanged ... the only change is where flag values come from.

### Key Components

- **`ArgusFeatureFlagServiceImpl`** ... the main service implementation. Fetches flags from the Argus endpoint, caches them in a `ConcurrentHashMap`, and exposes them through the full `FeatureFlagService` interface.
- **`ArgusConfiguration`** ... data class holding the API key, endpoint URL, tenant ID, environment, and user ID.
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

### Multiple Products

A single Customer workspace can own multiple Argus Products (web app, mobile
app, partner integration, etc.), each with its own apiKey, tenants, flags, and
audit log. If your Android app needs flags from more than one Product, create
one `ArgusFeatureFlagServiceImpl` per Product:

```kotlin
val webAppFlags = ArgusFeatureFlagServiceImpl(
    appVersionName = "1.0.0",
    appCoroutineScope = scope,
    moshi = moshi,
    configuration = ArgusConfiguration(
        apiKey = "argus_<your-web-app-product-key>",
        baseURL = "https://us-central1-argus-app-f0ff3.cloudfunctions.net",
        tenantId = "acme_ca",
        environment = "prod",
        userId = "user-uid"
    )
)

val mobileAppFlags = ArgusFeatureFlagServiceImpl(
    appVersionName = "1.0.0",
    appCoroutineScope = scope,
    moshi = moshi,
    configuration = ArgusConfiguration(
        apiKey = "argus_<your-mobile-app-product-key>",
        baseURL = "https://us-central1-argus-app-f0ff3.cloudfunctions.net",
        tenantId = "acme_ca",
        environment = "prod",
        userId = "user-uid"
    )
)
```

Each instance maintains its own flag cache and `StateFlow`. Wire each one
into Hilt under a distinct qualifier (e.g. `@Named("webApp")`,
`@Named("mobileApp")`) so call sites resolve the right service.

#### Where do apiKeys come from?

Open the Argus dashboard → **Settings** → **Products** → select the Product →
copy its **apiKey**. Each Product has exactly one apiKey; rotating it
invalidates the previous value.

## Running Tests

```bash
./gradlew :argus-sdk:test
```

## Dependencies

| Dependency | Reason |
|---|---|
| `hilt-android` | `@Inject` / `@Singleton` annotations |
| `moshi` + `moshi-kotlin` | JSON deserialisation matching existing app pattern |
| `okhttp` | HTTP client matching existing app pattern |
| `kotlinx-coroutines-*` | Async fetch and `StateFlow` |
| `timber` | Logging matching existing app pattern |

The SDK authenticates with an Argus apiKey (`ArgusConfiguration.apiKey`) ... no Firebase dependency. **apiKeys are per-Product**, not per-Customer: a workspace with multiple Products has multiple apiKeys, and each `ArgusFeatureFlagServiceImpl` instance is bound to exactly one of them.
