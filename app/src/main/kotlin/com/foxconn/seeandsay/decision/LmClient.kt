package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenSnapshot

/**
 * Index-free screen context exposed to an LM provider.
 *
 * @property screen stable screen label used only as semantic context.
 * @property elements visible element descriptions containing capabilities but no index or bounds.
 *
 * This immutable pure value contains no executable identifier, credential, Android/provider type,
 * I/O, coroutine, cancellation, logging, or failure behavior.
 */
data class LmScreenContext(
    val screen: String,
    val elements: List<LmScreenElement>,
)

/**
 * Index-free description of one visible element supplied to the intent model.
 *
 * @property text visible/accessibility label used as semantic context.
 * @property clickable whether local Kotlin may later consider click grounding.
 * @property editable whether local Kotlin may later consider text-entry grounding.
 *
 * This immutable pure value deliberately cannot represent an index, bounds, coordinate, or action.
 * It performs no I/O, coroutine, cancellation, logging, or failure-prone work.
 */
data class LmScreenElement(
    val text: String,
    val clickable: Boolean,
    val editable: Boolean,
)

/**
 * Removes executable indices and coordinates from a snapshot before crossing the LM client seam.
 *
 * @receiver immutable current snapshot retained locally for grounding and validation.
 * @return semantic screen name and element text/capabilities only.
 *
 * This pure deterministic projection performs no I/O, suspension, mutation, logging, or raw text
 * comparison and has no expected failure for ordinary snapshot values.
 */
fun ScreenSnapshot.toLmScreenContext(): LmScreenContext =
    LmScreenContext(
        screen = screen,
        elements =
            elements.map { element ->
                LmScreenElement(
                    text = element.text,
                    clickable = element.clickable,
                    editable = element.editable,
                )
            },
    )

/**
 * Provider-neutral request for schema-constrained semantic completion.
 *
 * @property transcript normalized non-empty user utterance supplied as primary semantic input.
 * @property screen immutable context containing no element indices or bounds.
 * @property responseSchema provider-neutral JSON schema/instructions constraining response shape.
 *
 * This immutable pure value contains no credential or provider SDK type, performs no I/O or
 * coroutine work, and has no failure behavior. The [screen] type cannot carry indices or bounds;
 * implementations must not expose credentials through logs or output.
 */
data class LmRequest(
    val transcript: String,
    val screen: LmScreenContext,
    val responseSchema: String,
)

/**
 * Minimal provider-neutral seam for the primary schema-constrained language-model implementation.
 *
 * Implementations may perform network I/O and throw provider failures, but must propagate coroutine
 * cancellation, must not log credentials/raw sensitive output, and must not leak provider types
 * through this interface. Production may use Vertex while deterministic tests use a JVM fake.
 */
interface LmClient {

    /**
     * Returns one raw structured response for the requested transcript/context/schema.
     *
     * @param request provider-neutral input containing no credential.
     * @return raw response text that the interpreter must distrust and validate strictly.
     *
     * The call may suspend and fail with an implementation exception. Cancellation must propagate
     * unchanged; credential and raw-output logging are forbidden.
     */
    suspend fun complete(request: LmRequest): String
}
