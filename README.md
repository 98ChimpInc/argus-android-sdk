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
- **`ArgusConfiguration`** ... data class holding the API key, endpoint URL, tenant ID, environment, and user ID. Use `ArgusConfiguration.create(context, ...)` to have `environment` auto-detected from the host app's build context (see [Environment auto-detection](#environment-auto-detection)).
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

## Environment auto-detection

Host apps can let the SDK infer `environment` from the build context instead of hard-coding it. Use `ArgusConfiguration.create(...)` and omit the `environment` argument:

```kotlin
val config = ArgusConfiguration.create(
    context = appContext,
    apiKey = BuildConfig.ARGUS_API_KEY,
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
    apiKey = BuildConfig.ARGUS_API_KEY,
    baseURL = BuildConfig.ARGUS_BASE_URL,
    tenantId = "northwind",
    userId = currentUser.uid,
    environment = "staging"   // explicit override
)
```

The existing `ArgusConfiguration(apiKey, baseURL, tenantId, environment, userId)` constructor is unchanged for callers that already manage `environment` themselves.

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

### R8 / Proguard

The reflective lookup requires `ARGUS_TRACK` to survive code shrinking on
release builds. Most apps don't have to do anything — the field survives
default R8 because `BuildConfig.DEBUG` is referenced everywhere and the
class gets kept by association. But if your release config is aggressive
(custom shrinker rules, `-allowobfuscation`, or you've stripped
`BuildConfig` explicitly) the field can disappear, and an internal-track
build silently resolves as `"prod"` instead of `"staging"`.

To pin it, add this rule to your app's `proguard-rules.pro`:

```
-keep class **.BuildConfig { public static java.lang.String ARGUS_TRACK; }
```

If you only set `ARGUS_TRACK` on a subset of flavors, scope the rule to
that package:

```
-keep class com.acme.app.BuildConfig { public static java.lang.String ARGUS_TRACK; }
```

The symptom you'd see without the rule: a Play Internal Testing build
ships with `BuildConfig.ARGUS_TRACK = "staging"` defined in source, but
the SDK resolves environment to `"prod"` on cold-start. Logcat shows the
reflection caught a `NoSuchFieldException` (silently — that's the
runCatching boundary). When in doubt, check the resolved environment via
`ArgusConfiguration.autoDetectedEnvironment(context)` in a debug overlay.

## apiKeys, multi-product workspaces

### Where do apiKeys come from?

Open the Argus dashboard → **Settings → API keys**, pick the Product you
want, and copy its `apiKey`. Each Product in a workspace has its own
disjoint apiKey; rotating it invalidates the previous value.

### Multiple Products in one app

A single Customer workspace can own multiple Argus Products (web app, mobile
app, partner integration, etc.), each with its own apiKey, tenants, flags, and
audit log. If your Android app needs flags from more than one Product, create
one `ArgusFeatureFlagServiceImpl` per Product:

```kotlin
val webAppFlags = ArgusFeatureFlagServiceImpl(
    appVersionName = "1.0.0",
    appCoroutineScope = scope,
    moshi = moshi,
    configuration = ArgusConfiguration.create(
        context = appContext,
        apiKey = "argus_<your-web-app-product-key>",
        baseURL = "https://us-central1-argus-app-f0ff3.cloudfunctions.net",
        tenantId = "acme_ca",
        userId = "user-uid"
        // environment auto-detects per the section above
    )
)

val mobileAppFlags = ArgusFeatureFlagServiceImpl(
    appVersionName = "1.0.0",
    appCoroutineScope = scope,
    moshi = moshi,
    configuration = ArgusConfiguration.create(
        context = appContext,
        apiKey = "argus_<your-mobile-app-product-key>",
        baseURL = "https://us-central1-argus-app-f0ff3.cloudfunctions.net",
        tenantId = "acme_ca",
        userId = "user-uid"
    )
)
```

Each instance maintains its own flag cache and `StateFlow`. Wire each one
into Hilt under a distinct qualifier (e.g. `@Named("webApp")`,
`@Named("mobileApp")`) so call sites resolve the right service.

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
