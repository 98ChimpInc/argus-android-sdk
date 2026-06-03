# Argus Android SDK

Drop-in replacement for a Firebase Remote Config-backed `FeatureFlagServiceImpl` in an Android app. Delivers resolved flag values via **real-time Firestore listeners** (primary channel) — flag changes pushed from the Argus dashboard land in the app in ~1s, no polling — with the Argus `resolveFlags` HTTP endpoint kept as a cold-start / fallback path. (Canonical architecture, see `DECISIONS.md` 2026-06-02, #215. This supersedes the earlier poll-only design.)

> **apiKeys are scoped per Product *and per environment*.** Each Argus Product
> issues three apiKeys ... one for `dev`, one for `staging`, one for `prod` ...
> shaped `argus_<env>_<48-hex>`. The server resolves the calling environment
> from the key bytes themselves (reverse-indexed), so the apiKey you pass to
> the SDK is what decides which environment's flags you read.
>
> Use a different key per build target: debug builds → dev key, internal
> testing track → staging key, production track → prod key. A Customer
> workspace with multiple Products (for example, a web app and a mobile app
> under the same studio account) has its own triple of keys per Product ... if
> your Android app talks to more than one Argus Product, instantiate one
> `ArgusFeatureFlagServiceImpl` per Product, each with its own
> `ArgusConfiguration.apiKey`. See [apiKeys, multi-product workspaces](#apikeys-multi-product-workspaces)
> below.
>
> Pre-M-2 unprefixed apiKeys (issued before the per-environment split) are
> still accepted and resolve as `env=prod` ... no migration burden on existing
> integrations.

## Architecture

The SDK implements the `FeatureFlagService` interface. Every `@Inject FeatureFlagService` usage in the host app works unchanged ... the only change is where flag values come from.

### Real-time push (primary channel, #215)

`initialize()` brings up the real-time channel in four steps:

1. **Bootstrap a scoped token + Firebase config.** The SDK `POST`s its Argus
   apiKey to `{baseURL}/issueStreamToken` (`Authorization: Bearer <apiKey>`) and
   receives `{ token, customerId, productId, env, tenantId, firebaseConfig }`.
   The `token` is a short-lived Firebase **custom token** scoped, via claims, to
   exactly this Product's flag/condition documents. The `firebaseConfig` object
   (`projectId`, `apiKey`, `appId`, `authDomain`, optional `storageBucket` /
   `messagingSenderId`, `useEmulator`) tells the SDK which Firebase project to
   connect to.
2. **Init a named Firebase app — self-configured from the server.** The SDK
   builds `FirebaseOptions` from the returned `firebaseConfig` and initialises a
   *named* secondary `FirebaseApp` (`"argus-sdk"`) so it never collides with the
   host app's default `FirebaseApp`. **The consumer passes only the Argus apiKey
   (+ endpoint base URL) — no Firebase config of their own.** When the returned
   `firebaseConfig.useEmulator` is `true`, the named app's Auth + Firestore point
   at the local emulators (`127.0.0.1:9099`/`:8080` by default) instead of prod.
   An explicit `ArgusConfiguration.firebase` override, if set, takes precedence
   over the server-returned config (e.g. for a bespoke test harness).
3. **Sign in** with `signInWithCustomToken(token)`.
4. **Open snapshot listeners** on the Product's `flags` query, each
   `flags/{id}/environments/{env}` doc, the tenant-override doc when
   tenant-scoped, and `conditions`. On *any* change the SDK re-runs client-side
   resolution and pushes new values into the cache. The Firestore rules permit
   this identity to **read only** its own Product's docs.

**Client-side resolution** (`ArgusFlagResolver`) mirrors the server
`resolveFlags` algorithm exactly — archived/draft skip, env value vs. default,
tenant-override priority, condition priority ordering + version/platform
matching, and rollout bucketing via the shared `FNV1a` — so a flag resolves to
the same value whether it arrives via the listener or the HTTP fallback.

Call `ArgusFeatureFlagServiceImpl.close()` to detach the listeners and tear down
the named Firebase app (e.g. on sign-out).

### Fallback channel: `resolveFlags` HTTP

The `GET {baseURL}/resolveFlags` fetch is **demoted to cold-start / fallback
only**: it paints current values on first launch before the listener delivers
its first snapshot, and it covers the case where Firebase init / sign-in fails
(the SDK stays on the last fetched values and remains `isActive == true`).

### Key Components

- **`ArgusFeatureFlagServiceImpl`** ... the main service implementation. Runs the real-time listener (primary) + HTTP fallback, caches resolved values in a `ConcurrentHashMap`, and exposes them through the full `FeatureFlagService` interface.
- **`ArgusFlagResolver`** ... pure, dependency-free client-side mirror of the server `resolveFlags` algorithm. Unit-tested without a live backend.
- **`ArgusConfiguration`** ... data class holding the Argus API key, endpoint URL, tenant ID, environment, and user ID. Its `firebase: FirebaseConfig?` field is an **optional override** that defaults to `null` — leave it unset on the happy path and the SDK self-configures from the server-returned `firebaseConfig`. Use `ArgusConfiguration.create(context, ...)` to have `environment` auto-detected from the host app's build context (see [Environment auto-detection](#environment-auto-detection)).
- **`FNV1a`** ... FNV-1a 32-bit hash for rollout bucketing. Produces identical output to the JavaScript reference in the Argus backend.
- **`ArgusModule`** ... optional standalone Hilt module for use when the SDK is not wired through the host app's own Hilt graph.

### Design Principles

1. **Zero call-site changes** ... every existing `@Inject FeatureFlagService` usage works unchanged.
2. **Real-time first** ... flag changes push to the app via Firestore listeners in ~1s; no polling on the happy path.
3. **Non-blocking initialisation** ... `initialize()` returns immediately. Bootstrap + listener attach run in a coroutine.
4. **Graceful degrade** ... cold-start HTTP fetch paints first; if Firebase init / sign-in fails the SDK falls back to the last HTTP values, and ultimately to `FeatureFlag.getDefaultValues()` when everything is unreachable.
5. **Platform-aware JSON extraction** ... extracts the `"android"` sub-object from platform-split JSON before deserialisation.

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

> **What the SDK detects is a display-layer hint, not the binding.** Under
> M-2, the server resolves the calling environment from the apiKey bytes
> (each Product issues a distinct `argus_dev_*` / `argus_staging_*` /
> `argus_prod_*` key). The `environment` value the SDK detects is reported
> on `ArgusConfiguration` and surfaces in things like debug overlays and
> analytics tagging ... it is *not* what tells the server which environment
> to read from. **The apiKey wins.**
>
> If you hardcode the prod apiKey in a debug build, the SDK's
> `autoDetectedEnvironment(context)` will return `"dev"` but the server
> will resolve as `prod` and hand back production flag values. To keep
> display and server agreement, pair each build target with the matching
> per-environment apiKey: debug → dev key, internal track → staging key,
> production track → prod key.

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
want, and you'll see a three-row table ... one row per environment (`dev`,
`staging`, `prod`), each with its own key shaped `argus_<env>_<48-hex>`
(e.g. `argus_prod_3f9c…`). Copy the key for the environment that matches
the build target you're configuring.

The server uses the key bytes to decide which environment to read from
... there is no separate `environment` parameter on the wire ... so each
Product has three keys and you choose which one to ship in each build
target:

| Build target | apiKey to ship |
|---|---|
| Debug / local | the Product's `argus_dev_*` key |
| Internal testing track | the Product's `argus_staging_*` key |
| Production / Play Store | the Product's `argus_prod_*` key |

Each Product's three keys are disjoint and rotate independently; rotating
one environment's key invalidates only that environment's previous value.

> **Compat note.** Pre-M-2 unprefixed apiKeys (no `argus_<env>_` prefix)
> are still accepted and resolve as `env=prod`. Existing integrations
> keep working without changes ... you only need to switch to per-env
> keys when you want non-prod targets to read non-prod flags.

### Multiple Products in one app

A single Customer workspace can own multiple Argus Products (web app, mobile
app, partner integration, etc.), each with its own *triple* of apiKeys
(`argus_dev_*` / `argus_staging_*` / `argus_prod_*`), tenants, flags, and
audit log. If your Android app needs flags from more than one Product, create
one `ArgusFeatureFlagServiceImpl` per Product, and pass each one the
environment-matched key for the current build target.

The cleanest pattern is to declare a `BuildConfig` String per Product, one
per build target, holding the right `argus_<env>_*` key for that target:

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        debug {
            buildConfigField("String", "ARGUS_KEY_WEB",    "\"argus_dev_<48-hex>\"")
            buildConfigField("String", "ARGUS_KEY_MOBILE", "\"argus_dev_<48-hex>\"")
        }
        release {
            buildConfigField("String", "ARGUS_KEY_WEB",    "\"argus_prod_<48-hex>\"")
            buildConfigField("String", "ARGUS_KEY_MOBILE", "\"argus_prod_<48-hex>\"")
        }
    }
    // ...flavors for staging (internal track) per the auto-detection section above
    buildFeatures { buildConfig = true }
}
```

Then wire one service instance per Product:

```kotlin
val webAppFlags = ArgusFeatureFlagServiceImpl(
    appVersionName = "1.0.0",
    appCoroutineScope = scope,
    moshi = moshi,
    configuration = ArgusConfiguration.create(
        context = appContext,
        apiKey = BuildConfig.ARGUS_KEY_WEB,     // argus_<env>_* per build target
        baseURL = "https://us-central1-argus-app-f0ff3.cloudfunctions.net",
        tenantId = "acme_ca",
        userId = "user-uid"
        // environment auto-detects per the section above (display-layer only)
    )
)

val mobileAppFlags = ArgusFeatureFlagServiceImpl(
    appVersionName = "1.0.0",
    appCoroutineScope = scope,
    moshi = moshi,
    configuration = ArgusConfiguration.create(
        context = appContext,
        apiKey = BuildConfig.ARGUS_KEY_MOBILE,  // argus_<env>_* per build target
        baseURL = "https://us-central1-argus-app-f0ff3.cloudfunctions.net",
        tenantId = "acme_ca",
        userId = "user-uid"
    )
)
```

Each instance maintains its own flag cache and `StateFlow`. Wire each one
into Hilt under a distinct qualifier (e.g. `@Named("webApp")`,
`@Named("mobileApp")`) so call sites resolve the right service. The
structural pattern is unchanged from before per-env apiKeys ... only the
keys themselves are env-scoped now.

## Running Tests

```bash
./gradlew :argus-sdk:test
```

## Dependencies

| Dependency | Reason |
|---|---|
| `hilt-android` | `@Inject` / `@Singleton` annotations |
| `moshi` + `moshi-kotlin` | JSON deserialisation matching existing app pattern |
| `okhttp` | HTTP client for the `issueStreamToken` bootstrap + `resolveFlags` fallback fetch |
| `firebase-bom` + `firebase-auth-ktx` + `firebase-firestore-ktx` | Real-time push primary channel (#215): scoped sign-in + Firestore snapshot listeners |
| `kotlinx-coroutines-*` (incl. `play-services`) | Async bootstrap, `StateFlow`, and `Task.await()` for `signInWithCustomToken` |
| `timber` | Logging matching existing app pattern |

The SDK still authenticates to Argus with your Argus apiKey
(`ArgusConfiguration.apiKey`). For the real-time channel it trades that key for
a scoped Firebase custom token **and the Firebase project config** (returned in
the same `issueStreamToken` response), then connects to **Argus's own Firebase
project** using that server-provided config — **you do not create or configure
a Firebase project of your own, and you pass no Firebase config to the SDK**;
the host app's existing default `FirebaseApp` (if any) is left untouched, since
the SDK runs on a separate named app. The documented happy path is to construct
`ArgusConfiguration` with just the Argus apiKey (+ base URL, tenant, user); the
optional `firebase` override exists only for bespoke test harnesses.

**apiKeys are per-Product *and per environment***, not per-Customer: a workspace
with multiple Products has multiple triples of apiKeys (`argus_dev_*` /
`argus_staging_*` / `argus_prod_*` per Product), and each
`ArgusFeatureFlagServiceImpl` instance is bound to exactly one of them. The
server resolves the calling environment from the key bytes, so pairing each
build target with the matching key is what controls which environment's flags
you read.
