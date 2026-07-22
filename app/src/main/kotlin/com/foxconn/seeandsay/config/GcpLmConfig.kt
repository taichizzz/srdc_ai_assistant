package com.foxconn.seeandsay.config

import com.foxconn.seeandsay.BuildConfig

/**
 * Non-secret Vertex AI location information for the primary LM intent client.
 *
 * @property projectId Google Cloud project containing the enabled Vertex AI API.
 * @property location regional endpoint and resource-path location.
 * @property model publisher model ID, such as the documented GA `gemini-2.5-flash` model.
 *
 * Values are immutable and contain no bearer token, service-account key, or provider object. Reading
 * defaults performs only in-memory BuildConfig access with no I/O, coroutine, cancellation, or
 * logging behavior. Blank values are intentionally retained so the client can report NotConfigured.
 */
data class GcpLmConfig(
    val projectId: String = BuildConfig.GCP_LM_PROJECT_ID,
    val location: String = BuildConfig.GCP_LM_LOCATION,
    val model: String = BuildConfig.GCP_LM_MODEL,
)
