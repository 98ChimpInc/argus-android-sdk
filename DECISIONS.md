# Decisions Log

## 2026-05-25 — Auto-detect `environment` from host-app build context

**Decision**: Make `environment` optional via a new `ArgusConfiguration.create(context, ...)` companion factory that defaults to an `autoDetectedEnvironment(context)` value. Detection priority: `ApplicationInfo.FLAG_DEBUGGABLE` → `"dev"`; reflective read of `<hostPackage>.BuildConfig.ARGUS_TRACK` → that value; else `"prod"`. The existing 5-arg `ArgusConfiguration(apiKey, baseURL, tenantId, environment, userId)` constructor is preserved untouched, so all existing call sites compile + behave identically.

**Reason**: Host apps already encode `dev`/`staging`/`prod` in their build configuration. Forcing them to wire `environment` into Hilt manually is redundant + error-prone (we've already seen flag fetches fire against the wrong env in QA). Reflection on `BuildConfig.ARGUS_TRACK` keeps the SDK from taking a compile-time dep on the customer's build config ... if the constant isn't there, the lookup silently falls through to `"prod"`. Caveat: Google Play Internal Testing and Production builds are byte-identical at runtime, so the staging-vs-prod distinction needs a build-time signal the customer sets (documented in the README as a `productFlavors` pattern).

**Alternatives considered**:
- A `lateinit` `environment` populated at first fetch ... rejected because it'd break the immutability contract of the data class.
- Reading a Manifest `<meta-data>` entry ... rejected as more boilerplate than `buildConfigField` for the customer.
- Compile-time API for the customer to register their `BuildConfig::class` ... rejected as over-engineering for the v1.1 surface; the reflection lookup is a single read on cold-start with a single `runCatching` guard.

## 2026-05-23 — M-1: apiKey identifies a Product, not a Customer

**Decision**: The Argus apiKey now identifies a per-Product credential (was
per-Customer). SDK code unchanged ... apiKey already drove identity via
`Authorization: Bearer`. README + example updated to show multiple
`ArgusFeatureFlagServiceImpl` instances for multi-product apps.

**Reason**: M-1 (argus-web-app#107) introduced Product as a first-class
entity. apiKey moved off Customer onto Product so a studio with multiple apps
can have disjoint apiKeys per app.

**Alternatives considered**: None ... the SDK API is unaffected.

## 2026-05-18 — API-key auth, dropping the Firebase Auth dependency

**Decision**: Authenticate to the Argus `resolveFlags` endpoint with a per-Customer
API key (`ArgusConfiguration.apiKey`, sent as `Authorization: Bearer <apiKey>`)
instead of a Firebase Auth ID token.

**Reason**: The `resolveFlags` endpoint became API-key-only (argus-web-app #15) — it
matches the bearer token against a Customer `apiKey`. The SDK was still sending a
Firebase Auth ID token, which never matches an apiKey, so every request 401'd.
API-key auth also removes the Firebase Auth SDK (and `coroutines-play-services`)
dependency entirely. Fixed the request path at the same time (`/api/flags` was
wrong; the endpoint is `/resolveFlags`).

**Alternatives considered**: Keeping Firebase Auth and adding apiKey-token support
server-side. Rejected — the endpoint is intentionally apiKey-only so SDK consumers
need no Firebase project of their own.

## 2026-04-03 — Standalone copies of interface, enum, and data models

**Decision**: Include standalone copies of `FeatureFlagService`, `FeatureFlag`, and all `FeatureFlagData` model classes within the SDK's `cloud.projectargus` package.

**Reason**: The SDK must compile independently as a standalone Gradle module. In the real app integration, the app's own versions from `:core:featureflag` are used via dependency injection. The standalone copies allow the SDK to be developed, tested, and versioned independently.

**Alternatives considered**: Publishing `:core:featureflag` as a shared Maven artefact. Rejected because it adds publishing infrastructure complexity for a prototype-phase SDK.

## 2026-04-03 — compareVersion utility inlined in the service

**Decision**: Inline a `compareVersion` private method in `ArgusFeatureFlagServiceImpl` rather than importing the host app's `compareVersion` utility.

**Reason**: The SDK is standalone and does not depend on the host app's `:core:utility` module. Inlining the simple version comparison avoids an additional module dependency. The logic is straightforward (split on `.`, compare integer parts).

**Alternatives considered**: Adding a dependency on `:core:utility`. Rejected to keep the SDK self-contained.

## 2026-04-03 — Reflection-based testing for flagCache

**Decision**: Unit tests access the private `flagCache` field via reflection to seed test data, avoiding the need for Firebase Auth and real HTTP calls in unit tests.

**Reason**: The `fetchFlags()` method requires a Firebase Auth token and a running server. Unit tests should exercise the getter logic independently. Reflection on a test target is an acceptable trade-off for test isolation.

**Alternatives considered**: Making `flagCache` internal or package-private. Rejected because the spec explicitly calls for it to be private.

## 2026-04-03 — OkHttp over HttpURLConnection

**Decision**: Use OkHttp for HTTP calls, consistent with the host app's existing HTTP stack.

**Reason**: The spec explicitly states OkHttp. A typical host Android app already depends on OkHttp, so it adds no new transitive dependency in the integrated build.

**Alternatives considered**: `HttpURLConnection` for zero-dependency builds. Rejected per spec.

## 2026-04-03 — UInt for FNV-1a hash

**Decision**: Use Kotlin's `UInt` type for the FNV-1a hash implementation.

**Reason**: `UInt` provides native unsigned 32-bit arithmetic, matching JavaScript's `>>> 0` unsigned right-shift semantics. The `*` operator on `UInt` wraps on overflow, equivalent to `Math.imul` in JavaScript. This eliminates manual masking.

**Alternatives considered**: Using `Long` with manual `and 0xFFFFFFFFL` masking. Rejected because `UInt` is cleaner and the spec explicitly recommends it.
