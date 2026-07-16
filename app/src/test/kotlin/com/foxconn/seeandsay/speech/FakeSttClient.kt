package com.foxconn.seeandsay.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Supplies deterministic provider-neutral recognition streams to ViewModel unit tests.
 *
 * @param streamFactory function invoked for each collected audio session; the default completes
 * without emitting recognition results.
 *
 * Construction and [stream] perform no platform or network I/O. Threading, suspension, failures,
 * and cancellation are defined entirely by the injected Flow factory, whose returned Flow remains
 * cold until a test collects it.
 */
class FakeSttClient(
    private val streamFactory: (Flow<ByteArray>) -> Flow<SttResult> = { emptyFlow() },
) : SttClient {

    /**
     * Returns the deterministic test stream associated with the supplied fake audio Flow.
     *
     * @param audio provider-neutral PCM Flow that a test may inspect or ignore.
     * @return cold result Flow produced by the injected factory.
     *
     * This call is synchronous and performs no I/O itself. Collection follows the factory's
     * coroutine context and propagates its cancellation or deliberate test failure unchanged.
     */
    override fun stream(audio: Flow<ByteArray>): Flow<SttResult> = streamFactory(audio)
}
