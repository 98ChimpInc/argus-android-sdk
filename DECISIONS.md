# Decisions Log

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
