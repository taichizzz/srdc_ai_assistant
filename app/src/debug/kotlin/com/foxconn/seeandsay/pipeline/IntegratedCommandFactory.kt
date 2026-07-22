package com.foxconn.seeandsay.pipeline

import com.foxconn.seeandsay.bridge.AccessibilityBridge
import com.foxconn.seeandsay.config.BuildConfigAccessTokenProvider
import com.foxconn.seeandsay.config.FeatureFlags
import com.foxconn.seeandsay.config.GcpLmConfig
import com.foxconn.seeandsay.decision.DefaultDecisionEngine
import com.foxconn.seeandsay.decision.LmIntentInterpreter
import com.foxconn.seeandsay.decision.VertexLmClient

/**
 * Creates the DEBUG-only live bridge + LM-first coordinator composition root.
 *
 * @return coordinator sharing one [AccessibilityBridge] for snapshots, actions, and event waits.
 *
 * Construction performs no screen action, wait, or network call. The Vertex client remains lazy
 * inside the decision engine and uses only the short-lived BuildConfig token when first invoked.
 * Missing config/token is recoverable and routes through deterministic matching. Keeping this
 * factory in pipeline preserves the sole bridge+decision composition boundary.
 */
fun createIntegratedCommandCoordinator(): IntegratedCommandCoordinator {
    val bridge = AccessibilityBridge()
    val engine =
        DefaultDecisionEngine(
            lmEnabled = FeatureFlags.LM_ENABLED,
            intentInterpreterFactory = {
                LmIntentInterpreter(
                    VertexLmClient(
                        accessTokenProvider = BuildConfigAccessTokenProvider(),
                        config = GcpLmConfig(),
                    ),
                )
            },
        )
    return IntegratedCommandCoordinator(
        uiBridge = bridge,
        screenSettler = bridge,
        decisionEngine = engine,
    )
}
