package cloud.projectargus

/**
 * Configuration for the Argus feature flag SDK.
 *
 * @property apiKey Argus API key for this Customer workspace (Argus dashboard → Settings → API key).
 * @property baseURL Root URL of the Argus Cloud Function endpoint.
 * @property tenantId Tenant identifier for multi-tenant resolution.
 * @property environment Target environment for flag resolution (dev, staging, prod).
 * @property userId Stable user identifier for rollout bucketing.
 */
data class ArgusConfiguration(
    val apiKey: String,
    val baseURL: String,
    val tenantId: String,
    val environment: String,
    val userId: String
)
