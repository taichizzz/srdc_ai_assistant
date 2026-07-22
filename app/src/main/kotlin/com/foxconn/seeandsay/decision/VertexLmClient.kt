package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.config.AccessTokenProvider
import com.foxconn.seeandsay.config.CloudSpeechNotConfiguredException
import com.foxconn.seeandsay.config.GcpLmConfig
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Vertex AI REST implementation of the provider-neutral [LmClient] seam.
 *
 * @param accessTokenProvider source of a short-lived OAuth token; long-lived keys are forbidden.
 * @param config non-secret debug-only project, regional location, and publisher model settings.
 * @param baseUrlOverride optional test endpoint; production leaves this null for the regional host.
 * @param requestTimeoutMillis positive end-to-end call bound, including upload and response time.
 *
 * Provider HTTP and wire-format types are confined to this implementation. [complete] performs one
 * cancellable OkHttp request and returns only the candidate's structured text. Missing config,
 * authentication, HTTP status, transport, timeout, and malformed envelope failures use fixed
 * [LmClientException] categories with no response body, token, Authorization header, or credential
 * in their messages. Coroutine cancellation cancels the in-flight OkHttp call and propagates as
 * cancellation rather than a user error.
 *
 * @throws IllegalArgumentException when [requestTimeoutMillis] is not positive.
 */
class VertexLmClient(
    private val accessTokenProvider: AccessTokenProvider,
    private val config: GcpLmConfig = GcpLmConfig(),
    private val baseUrlOverride: String? = null,
    requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) : LmClient {

    private val httpClient =
        OkHttpClient.Builder()
            .callTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()

    init {
        require(requestTimeoutMillis > 0L) { "requestTimeoutMillis must be positive" }
    }

    /**
     * Sends one schema-constrained `generateContent` request to the configured publisher model.
     *
     * @param request provider-neutral transcript, context snapshot, and strict response schema.
     * @return raw JSON response text from the first candidate for strict interpreter validation.
     * @throws LmClientException for recoverable configuration/provider/transport failures.
     * @throws CancellationException when the caller cancels; the OkHttp call is cancelled promptly.
     *
     * This suspend function creates no child coroutine and logs nothing. It obtains one short-lived
     * token per attempt, never persists it, and closes every HTTP response deterministically.
     */
    override suspend fun complete(request: LmRequest): String {
        val target = validatedTarget()
        val token = readToken()
        val httpRequest = buildRequest(target, token, request)
        val response = executeCancellable(httpRequest)
        response.use {
            if (!it.isSuccessful) throw httpFailure(it.code)
            val envelope = it.body?.string().orEmpty()
            return extractCandidateText(envelope)
        }
    }

    /**
     * Trims and validates non-secret path/host settings before any token or network access.
     *
     * @return safe target values suitable for URL construction.
     * @throws LmClientException with NotConfigured for blank or malformed configuration.
     *
     * This pure validation performs no I/O, suspension, logging, or credential work.
     */
    private fun validatedTarget(): VertexTarget {
        val project = config.projectId.trim()
        val location = config.location.trim()
        val model = config.model.trim()
        if (
            !PROJECT_ID.matches(project) ||
            !LOCATION.matches(location) ||
            !MODEL_ID.matches(model)
        ) {
            throw failure(LmClientFailureReason.NotConfigured, NOT_CONFIGURED_MESSAGE)
        }
        val baseUrl =
            if (baseUrlOverride == null) {
                "https://$location-aiplatform.googleapis.com/".toHttpUrlOrNull()
            } else {
                baseUrlOverride.toHttpUrlOrNull()
            } ?: throw failure(LmClientFailureReason.NotConfigured, NOT_CONFIGURED_MESSAGE)
        return VertexTarget(project, location, model, baseUrl)
    }

    /**
     * Obtains and validates one short-lived bearer token without exposing its value.
     *
     * @return trimmed bearer token without an authentication-scheme prefix.
     * @throws LmClientException for absent or failed token acquisition.
     * @throws CancellationException unchanged when token acquisition is cancelled.
     *
     * The provider controls any suspension. This method performs no logging or persistence.
     */
    private suspend fun readToken(): String {
        val token =
            try {
                accessTokenProvider.currentToken().trim()
            } catch (error: CancellationException) {
                throw error
            } catch (_: CloudSpeechNotConfiguredException) {
                throw failure(LmClientFailureReason.NotConfigured, NOT_CONFIGURED_MESSAGE)
            } catch (error: Throwable) {
                throw failure(LmClientFailureReason.Authentication, AUTH_MESSAGE, error)
            }
        if (token.isEmpty()) {
            throw failure(LmClientFailureReason.NotConfigured, NOT_CONFIGURED_MESSAGE)
        }
        return token
    }

    /**
     * Builds the regional publisher-model REST request and its controlled-output generation config.
     *
     * @param target validated project/location/model and base URL.
     * @param token short-lived bearer token, used only in the header and never logged.
     * @param request provider-neutral semantic request.
     * @return immutable OkHttp request ready for one execution.
     * @throws LmClientException when the schema is not a valid JSON object.
     *
     * This CPU-only conversion performs no I/O, suspension, or logging. The screen context omits
     * indices and coordinates because the LM is allowed to return goals only.
     */
    private fun buildRequest(
        target: VertexTarget,
        token: String,
        request: LmRequest,
    ): Request {
        val responseSchema =
            try {
                Json.parseToJsonElement(request.responseSchema).jsonObject.toVertexSchema()
            } catch (error: IllegalArgumentException) {
                throw failure(LmClientFailureReason.Unknown, INVALID_SCHEMA_MESSAGE, error)
            }
        val body =
            buildJsonObject {
                putJsonArray("contents") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", promptFor(request)) })
                            }
                        },
                    )
                }
                putJsonObject("generationConfig") {
                    put("temperature", 0.0)
                    put("responseMimeType", JSON_MEDIA_TYPE_TEXT)
                    put("responseSchema", responseSchema)
                }
            }.toString()
        return Request.Builder()
            .url(target.generateContentUrl())
            .header(AUTHORIZATION_HEADER, "Bearer $token")
            .header(CONTENT_TYPE_HEADER, JSON_MEDIA_TYPE_TEXT)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /**
     * Creates the goal-only semantic prompt and a reduced, index-free snapshot context.
     *
     * @param request provider-neutral transcript and snapshot.
     * @return compact prompt containing visible labels/capabilities but no indices or coordinates.
     *
     * This pure function performs no I/O, suspension, raw matching, logging, or failure-prone work.
     */
    private fun promptFor(request: LmRequest): String {
        val context =
            buildJsonObject {
                put("screen", request.screen.screen)
                putJsonArray("elements") {
                    request.screen.elements.forEach { element ->
                        add(
                            buildJsonObject {
                                put("text", element.text)
                                put("clickable", element.clickable)
                                put("editable", element.editable)
                            },
                        )
                    }
                }
            }
        return """
            Interpret the utterance as one high-level user goal using the supplied schema.
            Return JSON only. Never return an element index, coordinates, or a UI function/action.
            Utterance: ${JsonPrimitive(request.transcript)}
            Current screen context (context only, not an action plan): $context
        """.trimIndent()
    }

    /**
     * Executes one OkHttp call while connecting coroutine and transport cancellation.
     *
     * @param request immutable request ready to send.
     * @return open response owned by the caller, or closed automatically if cancellation wins.
     * @throws LmClientException for network and bounded-timeout failures.
     * @throws CancellationException unchanged when the awaiting coroutine is cancelled.
     *
     * The callback resumes at most once. Cancelling the continuation calls [Call.cancel] promptly;
     * late callbacks are ignored and any late response is closed.
     */
    private suspend fun executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (!continuation.isActive) return
                        val mapped =
                            if (error is SocketTimeoutException || error is InterruptedIOException) {
                                failure(LmClientFailureReason.Timeout, TIMEOUT_MESSAGE, error)
                            } else {
                                failure(LmClientFailureReason.Network, NETWORK_MESSAGE, error)
                            }
                        continuation.resumeWithException(mapped)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }
                        continuation.resume(response) { _, lateResponse, _ -> lateResponse.close() }
                    }
                },
            )
        }

    /**
     * Extracts structured text from the first Vertex candidate envelope.
     *
     * @param envelope raw provider response body, never logged or included in an exception.
     * @return first non-blank text part for strict [LmIntentInterpreter] validation.
     * @throws LmClientException when the successful HTTP body is malformed or has no text candidate.
     *
     * This pure parser performs no I/O, suspension, or logging and reveals no raw body on failure.
     */
    private fun extractCandidateText(envelope: String): String {
        val result =
            try {
                val candidates = Json.parseToJsonElement(envelope).jsonObject["candidates"]?.jsonArray
                val parts =
                    candidates
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("content")
                        ?.jsonObject
                        ?.get("parts")
                        ?.jsonArray
                parts
                    ?.asSequence()
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    ?.firstOrNull(String::isNotBlank)
            } catch (_: IllegalArgumentException) {
                null
            }
        return result ?: throw failure(LmClientFailureReason.Unknown, INVALID_RESPONSE_MESSAGE)
    }

    /**
     * Maps HTTP status to stable recoverable categories without reading or exposing the body.
     *
     * @param status HTTP response status.
     * @return fixed-message typed exception.
     *
     * This pure mapping performs no I/O, suspension, logging, or credential handling.
     */
    private fun httpFailure(status: Int): LmClientException =
        when (status) {
            401 -> failure(LmClientFailureReason.Authentication, AUTH_MESSAGE)
            403 -> failure(LmClientFailureReason.Permission, PERMISSION_MESSAGE)
            408, 504 -> failure(LmClientFailureReason.Timeout, TIMEOUT_MESSAGE)
            429 -> failure(LmClientFailureReason.Quota, QUOTA_MESSAGE)
            in 500..599 -> failure(LmClientFailureReason.Network, NETWORK_MESSAGE)
            else -> failure(LmClientFailureReason.Unknown, UNKNOWN_MESSAGE)
        }

    /**
     * Adapts strict JSON Schema null unions to Vertex's OpenAPI-compatible `nullable` representation.
     *
     * @receiver schema object supplied by the interpreter.
     * @return semantically equivalent Vertex `responseSchema` with fixed enums and required fields.
     *
     * This pure deterministic transform removes unsupported `additionalProperties`, uppercases type
     * enums, converts `[type, null]` into one type plus `nullable: true`, and removes JSON null from
     * enum arrays. It performs no I/O, suspension, logging, or mutation of the source object.
     */
    private fun JsonObject.toVertexSchema(): JsonObject =
        buildJsonObject {
            this@toVertexSchema.forEach { (key, value) ->
                when (key) {
                    "additionalProperties" -> Unit
                    "type" -> {
                        val types =
                            when (value) {
                                is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
                                is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                                else -> emptyList()
                            }
                        val concrete = types.firstOrNull { it != "null" }
                        concrete?.let { put("type", it.uppercase()) }
                        if ("null" in types) put("nullable", true)
                    }
                    "enum" -> {
                        val values = value.jsonArray.filterNot { it is JsonNull }
                        put("enum", JsonArray(values))
                        if (value.jsonArray.any { it is JsonNull }) put("nullable", true)
                    }
                    "properties" -> {
                        putJsonObject("properties") {
                            value.jsonObject.forEach { (name, property) ->
                                put(name, property.jsonObject.toVertexSchema())
                            }
                        }
                    }
                    else -> put(key, value)
                }
            }
        }

    /** Creates one fixed-message typed failure without credential or raw provider content. */
    private fun failure(
        reason: LmClientFailureReason,
        message: String,
        cause: Throwable? = null,
    ): LmClientException = LmClientException(reason, message, cause)

    /** Validated provider target retained only inside the Vertex implementation. */
    private data class VertexTarget(
        val project: String,
        val location: String,
        val model: String,
        val baseUrl: HttpUrl,
    ) {
        /** Returns the v1 regional publisher-model `generateContent` endpoint. */
        fun generateContentUrl(): HttpUrl =
            baseUrl.newBuilder()
                .addPathSegment("v1")
                .addPathSegment("projects")
                .addPathSegment(project)
                .addPathSegment("locations")
                .addPathSegment(location)
                .addPathSegment("publishers")
                .addPathSegment("google")
                .addPathSegment("models")
                .addPathSegment("$model:generateContent")
                .build()
    }

    /** Provider constants, validation patterns, and fixed non-secret messages. */
    companion object {
        /** Bounded default that avoids a hanging voice-loop request. */
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L

        private val PROJECT_ID = Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]")
        private val LOCATION = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val CONTENT_TYPE_HEADER = "Content-Type"
        private const val JSON_MEDIA_TYPE_TEXT = "application/json"
        private val JSON_MEDIA_TYPE = JSON_MEDIA_TYPE_TEXT.toMediaType()
        private const val NOT_CONFIGURED_MESSAGE = "Vertex LM is not configured."
        private const val AUTH_MESSAGE = "Vertex LM authentication failed."
        private const val PERMISSION_MESSAGE = "Vertex LM permission was denied."
        private const val QUOTA_MESSAGE = "Vertex LM quota or capacity was exhausted."
        private const val NETWORK_MESSAGE = "Vertex LM network request failed."
        private const val TIMEOUT_MESSAGE = "Vertex LM request timed out."
        private const val UNKNOWN_MESSAGE = "Vertex LM request was rejected."
        private const val INVALID_SCHEMA_MESSAGE = "Vertex LM response schema is invalid."
        private const val INVALID_RESPONSE_MESSAGE = "Vertex LM returned no structured candidate."
    }
}
