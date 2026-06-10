# Decisions Log

## 2026-06-09 — Modernize build toolchain to Kotlin 2.x + KSP + Firebase BoM 34 (#19)

**Decision**: Moved the SDK off its outdated toolchain so the current Firebase
BoM is usable:
- Kotlin `1.9.22` → `2.0.21` (K2 compiler).
- AGP `8.2.2` → `8.7.3`; Gradle wrapper `8.10` → `8.11.1`.
- Hilt `2.50` → `2.52`, migrated its annotation processor from **KAPT → KSP**
  (`kapt(libs.hilt.compiler)` → `ksp(libs.hilt.compiler)`, new
  `com.google.devtools.ksp` plugin pinned to `2.0.21-1.0.28`). KAPT is fully
  removed.
- coroutines `1.7.3` → `1.9.0`; Moshi `1.15.0` → `1.15.1`.
- Firebase BoM `32.7.1` → `34.4.0`, dropping the `-ktx` artifacts:
  `firebase-auth-ktx` → `firebase-auth`, `firebase-firestore-ktx` →
  `firebase-firestore`. BoM 33+ merged the Kotlin extensions into the main
  modules, so the `memoryCacheSettings { }` DSL import
  (`com.google.firebase.firestore.memoryCacheSettings`) is unchanged.

**Reason**: BoM 34 ships Firebase artifacts compiled with Kotlin 2.x metadata
and no longer publishes `-ktx` artifacts, so it cannot be consumed from a
Kotlin 1.9 + KAPT toolchain. A prior BoM-34 bump (PR #17) was reverted (#18)
precisely because the toolchain underneath wasn't modernized first; this change
fixes the root cause. KSP is the supported processor on Kotlin 2.x (KAPT runs in
a slower 1.9-compat mode and is deprecated for new work).

**Moshi note**: `@JsonClass(generateAdapter = true)` annotations remain, but the
SDK provides its `Moshi` instance via host-app DI with the reflective
`moshi-kotlin` adapter — no Moshi codegen processor was wired through KAPT, so no
Moshi KSP processor is needed. Only Hilt required the KAPT → KSP migration.

**Distribution**: the SDK is consumed via `implementation(project(":argus-sdk"))`
and released by git tag (currently `v1.0.2`); there is no `maven-publish` block
or in-source version constant to bump. The recommended next tag is `v1.0.3` —
left for the human to cut (not created here).

**Alternatives considered**: Kotlin 2.1.x — held at 2.0.21 to stay on the
Hilt-2.52 / KSP `2.0.21-1.0.28` combination that is known-good together. Staying
on KAPT — rejected; KAPT is deprecated on K2 and the migration is mechanical
here (single Hilt processor).

## 2026-06-02 — ⭐ CANONICAL: real-time push via Firestore listeners (supersedes the 2026-05-18 HTTP-poll pivot)

> **AUTHORITATIVE / owner-approved (shahin@98chimp.com).** Canonical record:
> argus-web-app/DECISIONS.md (2026-06-02, #215).

**Decision**: `ArgusFeatureFlagServiceImpl` delivers flag changes via **Firestore real-time listeners** (snapshot `Flow` → `StateFlow`), not OkHttp polling. On start it trades its apiKey for a scoped Firebase custom token (`issueStreamToken` callable), signs in, and listens to its product's flag/env docs; changes push into the `StateFlow` in ~1s. Per-user/per-device targeting resolves locally via the SDK's existing `FNV1a` (rollout) + `compareVersion` (conditions). The `resolveFlags` OkHttp call + polling intervals are demoted to a **cold-start / fallback path only**.

**This DIRECTLY SUPERSEDES the 2026-05-18 decision below** ("API-key auth, dropping the Firebase Auth dependency"), which moved the SDK to apiKey-only HTTP polling and removed Firebase/Firestore from the client — that pivot was made **without owner authorization** and silently dropped the real-time push capability. Firebase is re-added on the client (consumers use **Argus's** Firebase project via public config + a scoped custom token; they still need no Firebase project of their own). Do not reintroduce poll-only or SSE as the primary channel.

**Reason / governance**: push-to-client is the core value proposition. No future pivot of the transport/auth/real-time model without explicit owner approval, recorded here as superseding.

## 2026-06-02 — Real-time push implementation notes (#215)

**Decision**: Implemented the canonical architecture above.
- **`ArgusFlagResolver`** — a pure, dependency-free object holding the client-side
  resolution algorithm, an exact mirror of server `resolveFlags`
  (archived/draft skip → env-value-vs-default → tenant-override priority →
  condition priority + version/platform matching → rollout bucketing via the
  shared `FNV1a`). Extracted as a standalone unit so it is testable without a
  live Firebase (15 focused tests, no backend). Snapshot payloads are passed in
  as plain `Map`s so the listeners hand over decoded docs verbatim.
- **`JsonCompat`** — serialises decoded `Map`/`List` flag values back to a JSON
  string via `org.json`, so the cache string round-trips through the exact
  parser the structured getters (`getFeatureData`) already use. Keeps both
  channels byte-compatible downstream.
- **Named secondary `FirebaseApp` (`"argus-sdk"`)** — never the host app's
  default app. Context is borrowed from `FirebaseApp.getInstance()` (the host's
  auto-initialised default); if the host has no Firebase at all, real-time init
  throws and `initialize()` degrades to the HTTP fallback.
- **Listener fan-out** — one query listener on `flags`, one on `conditions`, and
  per-flag listeners on `environments/{env}` (+ `tenants/{tenantId}` when
  tenant-scoped). The per-flag listeners deliver live value changes; the flags
  query handles add/remove/archive/draft. Bound-once guards
  (`CopyOnWriteArrayList` + concurrent sets) prevent duplicate registrations.
- **`ArgusConfiguration.FirebaseConfig`** — optional, fully defaulted (Argus prod
  placeholders + emulator host/ports). The `useEmulator` flag points the named
  app at the Auth + Firestore emulators for harness / local dev parity.
- **`close()`** — detaches listeners and deletes the named app (e.g. on sign-out).

**Supporting build-state fixes (rode along, required to compile/test the branch):**
- Fixed a pre-existing typo in `settings.gradle.kts`: `dependencyResolution`
  → `dependencyResolutionManagement` (the block name was invalid and broke
  every Gradle invocation on this branch).
- Fixed pre-existing unit tests that constructed `ArgusConfiguration` /
  `ArgusConfiguration.create` without the required `apiKey` argument (they
  could not compile).
- Added `org.json:json` as a `testImplementation`. The Android `android.jar`
  stub throws "not mocked" for every `org.json` call; a real implementation on
  the test classpath is required to exercise the JSON-parsing getters and the
  resolver's value stringification (this un-broke 5 pre-existing tests too).

**Reason**: keep the resolver pure + unit-testable (the listener path needs a
device/emulator, the algorithm does not), and keep the dual-channel output
identical so the demotion of `resolveFlags` to fallback is transparent to
callers.

**Alternatives considered**: collecting each listener as its own
`callbackFlow` and `combine`-ing them into the `StateFlow` — rejected as more
moving parts than maintaining the latest-snapshot maps + a single
`recomputeAndPublish()`; the recompute is cheap (in-memory, per-Product flag
counts are small) and the code is easier to reason about.

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

## 2026-05-18 — API-key auth, dropping the Firebase Auth dependency  ⚠️ SUPERSEDED (see 2026-06-02 canonical entry above)

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
