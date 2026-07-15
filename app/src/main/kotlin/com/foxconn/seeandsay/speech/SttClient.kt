package com.foxconn.seeandsay.speech

import kotlinx.coroutines.flow.Flow

/**
 * Defines the provider-neutral streaming speech-to-text boundary.
 *
 * Implementations consume raw audio and expose interim and final recognition updates without
 * leaking provider, gRPC, or protobuf types. Implementations must be safe to collect away from the
 * main thread, propagate provider/network/authentication failures through the returned Flow, and
 * release their stream promptly when collection is cancelled.
 */
interface SttClient {

    /**
     * Streams audio bytes to a recognizer and emits ordered recognition updates.
     *
     * @param audio cold or hot audio-byte Flow supplied by the caller; implementations must stop
     * collecting it when the returned Flow is cancelled.
     * @return a cold Flow of provider-neutral interim and final recognition results.
     *
     * No network work should begin until the returned Flow is collected. Authentication, network,
     * quota, timeout, and malformed-response failures are emitted as Flow failures. Implementations
     * choose their I/O dispatcher internally, never block the main thread, and must close network
     * resources when collection is cancelled.
     */
    fun stream(audio: Flow<ByteArray>): Flow<SttResult>
}
