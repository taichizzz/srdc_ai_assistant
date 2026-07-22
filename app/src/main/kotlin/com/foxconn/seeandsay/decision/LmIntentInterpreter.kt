package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Strict schema-validating semantic interpreter backed by the provider-neutral [LmClient] seam.
 *
 * @param lmClient untrusted structured-completion source; Phase 4 supplies only a JVM fake.
 * @param confidenceThreshold inclusive minimum confidence allowed to resolve without clarification;
 * it must be finite and within 0.0..1.0.
 *
 * The interpreter makes at most two sequential completion attempts. Malformed JSON, provider
 * failure, unknown/missing fields, invalid enums/ranges, forbidden index/UI-call/coordinate output,
 * and semantically incomplete high-confidence goals reject the attempt. Two rejected attempts
 * return [IntentResult.NoMatch]. Explicit clarification or confidence below the default 0.70
 * threshold returns [IntentResult.Clarify] without retrying or guessing. Coroutine cancellation is
 * always rethrown unchanged and is never converted to a user-visible result. No response,
 * transcript, credential, or provider failure is logged.
 *
 * Apart from the injected suspending client call, validation is deterministic CPU-only Kotlin with
 * no Android, accessibility, credential, provider SDK, filesystem, timer, or logging dependency.
 * @throws IllegalArgumentException when [confidenceThreshold] is non-finite or outside 0.0..1.0.
 */
class LmIntentInterpreter(
    private val lmClient: LmClient,
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
) : IntentInterpreter {

    init {
        require(confidenceThreshold.isFinite() && confidenceThreshold in 0.0f..1.0f) {
            "confidenceThreshold must be finite and within 0.0..1.0"
        }
    }

    /**
     * Requests and strictly validates at most two schema-constrained responses.
     *
     * @param transcript normalized non-empty utterance supplied as primary semantic input.
     * @param screen immutable local context projected to index-free [LmScreenContext] before the
     * client call; indices and bounds can never cross the provider seam or appear in the result.
     * @return resolved high-level goal, clarification, or no-match after two rejected attempts.
     *
     * This suspend function invokes [LmClient.complete] sequentially and creates no child coroutine.
     * Client/schema failures are retried once then become NoMatch. [CancellationException] propagates
     * immediately unchanged, with no retry, logging, or user-visible conversion.
     */
    override suspend fun interpret(
        transcript: String,
        screen: ScreenSnapshot,
    ): IntentResult {
        val request = LmRequest(transcript, screen.toLmScreenContext(), RESPONSE_SCHEMA)
        repeat(MAX_ATTEMPTS) {
            val response =
                try {
                    lmClient.complete(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return@repeat
                }
            val validated = validate(response) ?: return@repeat
            if (
                validated.needsClarification ||
                validated.confidence < confidenceThreshold
            ) {
                return IntentResult.Clarify(
                    validated.clarificationQuestion
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: DEFAULT_CLARIFICATION_QUESTION,
                )
            }
            val goal = validated.toGoal() ?: return@repeat
            return IntentResult.Resolved(goal, validated.confidence)
        }
        return IntentResult.NoMatch
    }

    /**
     * Rejects forbidden output, parses strict JSON fields/types, and validates enum/range values.
     *
     * @param rawResponse untrusted response text returned by [LmClient].
     * @return validated intermediate response or `null` for any safety/schema violation.
     *
     * This pure function performs no I/O, logging, coroutine, cancellation, or provider work. JSON
     * parsing exceptions are contained as rejection; ordinary untrusted text never escapes failure.
     */
    private fun validate(rawResponse: String): ValidatedResponse? {
        if (containsForbiddenOutput(rawResponse)) return null
        val root =
            try {
                Json.parseToJsonElement(rawResponse)
            } catch (_: IllegalArgumentException) {
                return null
            }
        val response = root as? JsonObject ?: return null
        if (response.keys != REQUIRED_FIELDS) return null

        val intent = requiredString(response, FIELD_INTENT) ?: return null
        if (intent !in ALLOWED_INTENTS) return null
        val direction = nullableString(response[FIELD_DIRECTION]) ?: return null
        if (direction.value != null && direction.value !in ALLOWED_DIRECTIONS) return null
        val target = nullableString(response[FIELD_TARGET]) ?: return null
        val controlQuery = nullableString(response[FIELD_CONTROL_QUERY]) ?: return null
        if (controlQuery.value != null && controlQuery.value.isBlank()) return null
        val confidence = requiredFloat(response, FIELD_CONFIDENCE) ?: return null
        if (!confidence.isFinite() || confidence !in 0.0f..1.0f) return null
        val needsClarification = requiredBoolean(response, FIELD_NEEDS_CLARIFICATION) ?: return null
        val clarificationQuestion =
            nullableString(response[FIELD_CLARIFICATION_QUESTION]) ?: return null

        return ValidatedResponse(
            intent = intent,
            direction = direction.value,
            target = target.value,
            controlQuery = controlQuery.value?.trim(),
            confidence = confidence,
            needsClarification = needsClarification,
            clarificationQuestion = clarificationQuestion.value,
        )
    }

    /**
     * Detects prohibited element-index, UI-call, or coordinate material before JSON interpretation.
     *
     * @param rawResponse untrusted model response.
     * @return `true` when conservative case-insensitive token/key scanning finds forbidden output.
     *
     * This pure safety check performs no I/O or suspension and cannot fail for Kotlin strings. It
     * intentionally scans the entire raw response, including string values, so hiding a UI call in
     * an otherwise valid field cannot bypass strict object-key validation.
     */
    private fun containsForbiddenOutput(rawResponse: String): Boolean =
        FORBIDDEN_INDEX.containsMatchIn(rawResponse) ||
            FORBIDDEN_UI_CALL.containsMatchIn(rawResponse) ||
            FORBIDDEN_COORDINATE.containsMatchIn(rawResponse)

    /**
     * Reads one required JSON string primitive.
     *
     * @param response strict response object.
     * @param field required field name.
     * @return string content or `null` for null, missing, boolean, or numeric values.
     *
     * This pure helper performs no I/O, coroutine, or failure-prone work.
     */
    private fun requiredString(response: JsonObject, field: String): String? {
        val primitive = response[field] as? JsonPrimitive ?: return null
        return primitive.takeIf(JsonPrimitive::isString)?.content
    }

    /**
     * Reads one schema-required nullable JSON string while preserving valid JSON null.
     *
     * @param element required field value already located by exact-key validation.
     * @return wrapper containing string/null, or `null` wrapper for an invalid JSON type.
     *
     * This pure helper performs no I/O, coroutine, or failure-prone work.
     */
    private fun nullableString(element: kotlinx.serialization.json.JsonElement?): NullableString? =
        when (element) {
            JsonNull -> NullableString(null)
            is JsonPrimitive ->
                element
                    .takeIf(JsonPrimitive::isString)
                    ?.contentOrNull
                    ?.let(::NullableString)
            else -> null
        }

    /**
     * Reads one required finite-capable JSON numeric primitive as Float.
     *
     * @param response strict response object.
     * @param field required field name.
     * @return numeric value or `null` for strings, booleans, null, missing, or non-numeric values.
     *
     * This pure helper performs no I/O, coroutine, or failure-prone work.
     */
    private fun requiredFloat(response: JsonObject, field: String): Float? {
        val primitive = response[field] as? JsonPrimitive ?: return null
        if (primitive.isString || primitive.booleanOrNull != null) return null
        return primitive.floatOrNull
    }

    /**
     * Reads one required JSON boolean primitive.
     *
     * @param response strict response object.
     * @param field required field name.
     * @return boolean value or `null` for strings, numbers, null, or missing fields.
     *
     * This pure helper performs no I/O, coroutine, or failure-prone work.
     */
    private fun requiredBoolean(response: JsonObject, field: String): Boolean? {
        val primitive = response[field] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.booleanOrNull
    }

    /**
     * Shape-valid response awaiting goal-specific semantic validation.
     *
     * @property intent validated schema enum text.
     * @property direction validated increase/decrease/null schema value.
     * @property target schema-valid optional target text.
     * @property controlQuery schema-valid optional semantic control description, never an index.
     * @property confidence finite inclusive-range confidence.
     * @property needsClarification explicit model uncertainty signal.
     * @property clarificationQuestion optional schema-valid question.
     *
     * This private immutable value performs no I/O, coroutine, cancellation, or failure work.
     */
    private data class ValidatedResponse(
        val intent: String,
        val direction: String?,
        val target: String?,
        val controlQuery: String?,
        val confidence: Float,
        val needsClarification: Boolean,
        val clarificationQuestion: String?,
    ) {

        /**
         * Converts a confident response to a goal while enforcing intent-specific nullability.
         *
         * @return index-free goal, or `null` when required direction/target semantics are invalid.
         *
         * This pure function performs no I/O, coroutine, raw comparison, or failure-prone work.
         */
        fun toGoal(): UserGoal? =
            when (intent) {
                INTENT_ADJUST_TEXT_SIZE ->
                    direction?.toDirection()?.let { parsedDirection ->
                        UserGoal.AdjustTextSize(parsedDirection, controlQuery)
                    }
                        ?.takeIf { target == null }
                INTENT_ADJUST_VOLUME ->
                    direction?.toDirection()?.let { parsedDirection ->
                        UserGoal.AdjustVolume(parsedDirection, controlQuery)
                    }
                        ?.takeIf { target == null }
                INTENT_ADJUST_BRIGHTNESS ->
                    direction?.toDirection()?.let { parsedDirection ->
                        UserGoal.AdjustBrightness(parsedDirection, controlQuery)
                    }
                        ?.takeIf { target == null }
                INTENT_OPEN_TARGET ->
                    target
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { parsedTarget -> UserGoal.OpenTarget(parsedTarget, controlQuery) }
                        ?.takeIf { direction == null }
                INTENT_GO_BACK ->
                    UserGoal.GoBack.takeIf {
                        direction == null && target == null && controlQuery == null
                    }
                INTENT_STOP ->
                    UserGoal.Stop.takeIf {
                        direction == null && target == null && controlQuery == null
                    }
                INTENT_UNKNOWN ->
                    UserGoal.Unknown.takeIf {
                        direction == null && target == null && controlQuery == null
                    }
                else -> null
            }

        /**
         * Maps the already validated direction schema value to pure domain direction.
         *
         * @receiver validated `increase` or `decrease` string.
         * @return corresponding [Direction], or `null` defensively for any other value.
         *
         * This pure helper performs no I/O, coroutine, or failure-prone work.
         */
        private fun String.toDirection(): Direction? =
            when (this) {
                DIRECTION_INCREASE -> Direction.Increase
                DIRECTION_DECREASE -> Direction.Decrease
                else -> null
            }
    }

    /**
     * Distinguishes valid JSON null from invalid nullable-string field types.
     *
     * @property value schema-valid string or JSON null.
     *
     * This private immutable value performs no I/O, coroutine, or failure-prone work.
     */
    private data class NullableString(val value: String?)

    /** Stable schema, safety policy, and compile-out factory shared by interpreter instances. */
    companion object {
        /** Inclusive confidence floor; lower valid responses always request clarification. */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.70f

        /** Maximum sequential response attempts for invalid output/provider failure. */
        private const val MAX_ATTEMPTS = 2

        /** Fixed non-secret fallback question for low confidence or explicit uncertainty. */
        private const val DEFAULT_CLARIFICATION_QUESTION =
            "我不太確定你的意思，可以再說清楚一點嗎？"

        /** Required strict schema field names; exact equality also rejects every unknown field. */
        private val REQUIRED_FIELDS =
            setOf(
                "intent",
                "direction",
                "target",
                "control_query",
                "confidence",
                "needs_clarification",
                "clarification_question",
            )

        private const val FIELD_INTENT = "intent"
        private const val FIELD_DIRECTION = "direction"
        private const val FIELD_TARGET = "target"
        private const val FIELD_CONTROL_QUERY = "control_query"
        private const val FIELD_CONFIDENCE = "confidence"
        private const val FIELD_NEEDS_CLARIFICATION = "needs_clarification"
        private const val FIELD_CLARIFICATION_QUESTION = "clarification_question"

        private const val INTENT_ADJUST_TEXT_SIZE = "adjust_text_size"
        private const val INTENT_ADJUST_VOLUME = "adjust_volume"
        private const val INTENT_ADJUST_BRIGHTNESS = "adjust_brightness"
        private const val INTENT_OPEN_TARGET = "open_target"
        private const val INTENT_GO_BACK = "go_back"
        private const val INTENT_STOP = "stop"
        private const val INTENT_UNKNOWN = "unknown"
        private val ALLOWED_INTENTS =
            setOf(
                INTENT_ADJUST_TEXT_SIZE,
                INTENT_ADJUST_VOLUME,
                INTENT_ADJUST_BRIGHTNESS,
                INTENT_OPEN_TARGET,
                INTENT_GO_BACK,
                INTENT_STOP,
                INTENT_UNKNOWN,
            )

        private const val DIRECTION_INCREASE = "increase"
        private const val DIRECTION_DECREASE = "decrease"
        private val ALLOWED_DIRECTIONS = setOf(DIRECTION_INCREASE, DIRECTION_DECREASE)

        /** Rejects an index key or element-index phrase anywhere in untrusted response text. */
        private val FORBIDDEN_INDEX =
            Regex(
                """(?i)(?:\"(?:element[\s_-]*index|index)\"\s*:|\belement[\s_-]*index\b|\bindex\s*[:=#]?\s*\d+\b)""",
            )

        /** Rejects named UI primitives/functions anywhere, including inside allowed string fields. */
        private val FORBIDDEN_UI_CALL =
            Regex("""(?i)\b(?:ui[_-]?click|set[_-]?text|perform[_-]?action)\b""")

        /** Rejects coordinate/bounds keys or coordinate words anywhere in model output. */
        private val FORBIDDEN_COORDINATE =
            Regex(
                """(?i)(?:\"(?:coordinates?|bounds|x|y)\"\s*:|\bcoordinates?\b|[\[(]\s*-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?(?:\s*,\s*-?\d+(?:\.\d+)?){0,2}\s*[\])])""",
            )

        /**
         * Provider-neutral response-shape contract sent with every [LmRequest].
         *
         * The later provider adapter may translate this JSON Schema text into its native constrained
         * response option without changing interpreter/seam contracts. Reading this constant is
         * synchronous and performs no I/O, initialization, logging, or failure-prone work.
         */
        const val RESPONSE_SCHEMA: String =
            """{"type":"object","required":["intent","direction","target","control_query","confidence","needs_clarification","clarification_question"],"additionalProperties":false,"properties":{"intent":{"enum":["adjust_text_size","adjust_volume","adjust_brightness","open_target","go_back","stop","unknown"]},"direction":{"type":["string","null"],"enum":["increase","decrease",null]},"target":{"type":["string","null"]},"control_query":{"type":["string","null"]},"confidence":{"type":"number","minimum":0.0,"maximum":1.0},"needs_clarification":{"type":"boolean"},"clarification_question":{"type":["string","null"]}}}"""

        /**
         * Constructs an interpreter lazily only when a composition root enables the LM feature.
         *
         * @param enabled feature-flag value; production defaults true and false forces fallback.
         * @param clientFactory provider client factory invoked exactly once only when [enabled].
         * @return configured interpreter when enabled, otherwise `null` without client creation.
         *
         * This synchronous factory performs no network/coroutine work itself. With false it cannot
         * fail and does not invoke [clientFactory]; with true, factory/construction failures propagate.
         */
        fun createWhenEnabled(
            enabled: Boolean,
            clientFactory: () -> LmClient,
        ): LmIntentInterpreter? =
            if (enabled) {
                LmIntentInterpreter(clientFactory())
            } else {
                null
            }
    }
}
